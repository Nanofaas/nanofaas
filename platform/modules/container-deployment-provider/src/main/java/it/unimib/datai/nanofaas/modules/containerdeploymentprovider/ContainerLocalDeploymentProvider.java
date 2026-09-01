package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatus;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class ContainerLocalDeploymentProvider implements ManagedDeploymentProvider {

    static final String BACKEND_ID = "container-local";
    static final String MANAGED_LABEL = "io.nanofaas.managed";
    static final String FUNCTION_LABEL = "io.nanofaas.function";
    static final String REPLICA_LABEL = "io.nanofaas.replica";
    private static final Set<String> RESERVED_ENV = Set.of(
            "FUNCTION_NAME", "WARM", "TIMEOUT_MS", "EXECUTION_MODE", "WATCHDOG_CMD", "CALLBACK_URL"
    );

    private final ContainerRuntimeAdapter adapter;
    private final ContainerLocalProperties properties;
    private final EndpointProbe endpointProbe;
    private final PortAllocator portAllocator;
    private final ManagedFunctionProxyFactory proxyFactory;
    private final Map<String, FunctionState> states = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ContainerLocalDeploymentProvider(ContainerRuntimeAdapter adapter,
                                            ContainerLocalProperties properties,
                                            EndpointProbe endpointProbe,
                                            PortAllocator portAllocator,
                                            ManagedFunctionProxyFactory proxyFactory) {
        this.adapter = adapter;
        this.properties = properties;
        this.endpointProbe = endpointProbe;
        this.portAllocator = portAllocator;
        this.proxyFactory = proxyFactory;
    }

    @Override
    public String backendId() {
        return BACKEND_ID;
    }

    @Override
    public boolean isAvailable() {
        return adapter.isAvailable();
    }

    @Override
    public boolean supports(FunctionSpec spec) {
        return spec.executionMode() == ExecutionMode.DEPLOYMENT
                && (spec.imagePullSecrets() == null || spec.imagePullSecrets().isEmpty());
    }

    @Override
    public ProvisionResult provision(FunctionSpec spec) {
        ReentrantLock lock = locks.computeIfAbsent(spec.name(), k -> new ReentrantLock());
        lock.lock();
        try {
            FunctionState existing = states.get(spec.name());
            if (existing != null) {
                return new ProvisionResult(existing.proxy.endpointUrl(), backendId(), deploymentObjects(spec.name()));
            }

            ManagedFunctionProxy proxy = proxyFactory.create(spec.name());
            FunctionState state = new FunctionState(spec, proxy);
            states.put(spec.name(), state);
            try {
                scaleTo(state, desiredReplicas(spec));
                return new ProvisionResult(proxy.endpointUrl(), backendId(), deploymentObjects(spec.name()));
            } catch (RuntimeException e) {
                for (int replicaIndex : List.copyOf(state.replicas.keySet()).reversed()) {
                    suppressCleanupFailure(e, () -> removeReplica(state, replicaIndex));
                }
                states.remove(spec.name());
                suppressCleanupFailure(e, proxy::close);
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public ProvisionResult reconcile(FunctionSpec spec,
                                     int desiredReplicas,
                                     Map<String, String> deploymentObjects) {
        ReentrantLock lock = locks.computeIfAbsent(spec.name(), k -> new ReentrantLock());
        lock.lock();
        try {
            String prefix = requirePersistedPrefix(spec.name(), deploymentObjects);
            List<ManagedContainer> discovered = adapter.listManagedContainers(spec.name()).stream()
                    .sorted(Comparator.comparingInt(ManagedContainer::replicaIndex))
                    .toList();
            validateReplicaIndexes(prefix, discovered);

            ManagedFunctionProxy proxy = proxyFactory.create(spec.name());
            FunctionState state = new FunctionState(spec, proxy);
            Set<String> createdDuringReconcile = new HashSet<>();
            try {
                for (ManagedContainer container : discovered) {
                    if (container.replicaIndex() > desiredReplicas) {
                        adapter.removeContainer(container.name());
                    } else if (adoptable(container)) {
                        String url = baseUrl(container.name(), container.hostPort());
                        endpointProbe.awaitReady(url, properties.readinessTimeout(), properties.readinessPollInterval());
                        state.replicas.put(container.replicaIndex(),
                                new ReplicaState(container.name(), container.hostPort(), url));
                    } else {
                        adapter.removeContainer(container.name());
                        addReplica(state, container.replicaIndex());
                        createdDuringReconcile.add(container.name());
                    }
                }
                createMissingReplicas(state, desiredReplicas, createdDuringReconcile);
                states.put(spec.name(), state);
                return new ProvisionResult(proxy.endpointUrl(), backendId(), deploymentObjects(spec.name()));
            } catch (RuntimeException failure) {
                for (String createdName : createdDuringReconcile) {
                    suppressCleanupFailure(failure, () -> adapter.removeContainer(createdName));
                }
                safeClose(proxy);
                throw failure;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void deprovision(String functionName) {
        ReentrantLock lock = locks.computeIfAbsent(functionName, k -> new ReentrantLock());
        lock.lock();
        try {
            FunctionState state = states.remove(functionName);
            if (state == null) {
                adapter.listManagedContainers(functionName)
                        .forEach(container -> adapter.removeContainer(container.name()));
                return;
            }
            for (int replicaIndex : List.copyOf(state.replicas.keySet()).reversed()) {
                removeReplica(state, replicaIndex);
            }
            safeClose(state.proxy);
        } finally {
            lock.unlock();
            locks.remove(functionName);
        }
    }

    @Override
    public void setReplicas(String functionName, int replicas) {
        ReentrantLock lock = locks.get(functionName);
        if (lock == null) {
            return;
        }
        lock.lock();
        try {
            FunctionState state = states.get(functionName);
            if (state == null) {
                return;
            }
            scaleTo(state, Math.max(0, replicas));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int getReadyReplicas(String functionName) {
        return getReplicaStatus(functionName).readyReplicas();
    }

    @Override
    public ReplicaStatus getReplicaStatus(String functionName) {
        ReentrantLock lock = locks.get(functionName);
        if (lock == null) {
            return new ReplicaStatus(0, 0);
        }
        lock.lock();
        try {
            FunctionState state = states.get(functionName);
            if (state == null) {
                return new ReplicaStatus(0, 0);
            }
            int readyReplicas = (int) state.replicas.values().stream()
                    .filter(replica -> endpointProbe.isReady(replica.baseUrl()))
                    .count();
            return new ReplicaStatus(state.replicas.size(), readyReplicas);
        } finally {
            lock.unlock();
        }
    }

    private void scaleTo(FunctionState state, int desiredReplicas) {
        int currentReplicas = state.replicas.size();
        if (desiredReplicas > currentReplicas) {
            for (int replicaIndex = currentReplicas + 1; replicaIndex <= desiredReplicas; replicaIndex++) {
                addReplica(state, replicaIndex);
            }
        } else if (desiredReplicas < currentReplicas) {
            for (int replicaIndex = currentReplicas; replicaIndex > desiredReplicas; replicaIndex--) {
                removeReplica(state, replicaIndex);
            }
        }
        state.proxy.updateBackends(state.replicas.values().stream().map(ReplicaState::baseUrl).toList());
    }

    private void addReplica(FunctionState state, int replicaIndex) {
        String containerName = containerName(state.spec.name(), replicaIndex);
        Integer hostPort = properties.networkName() == null ? portAllocator.nextPort() : null;
        String baseUrl = baseUrl(containerName, hostPort);
        ContainerInstanceSpec instanceSpec = new ContainerInstanceSpec(
                containerName,
                state.spec.image(),
                hostPort,
                state.spec.command() == null ? List.of() : state.spec.command(),
                buildEnv(state.spec),
                state.spec.resources(),
                Map.of(
                        MANAGED_LABEL, "true",
                        FUNCTION_LABEL, state.spec.name(),
                        REPLICA_LABEL, Integer.toString(replicaIndex))
        );

        try {
            adapter.runContainer(instanceSpec);
            endpointProbe.awaitReady(baseUrl, properties.readinessTimeout(), properties.readinessPollInterval());
        } catch (RuntimeException e) {
            suppressCleanupFailure(e, () -> adapter.removeContainer(containerName));
            throw e;
        }
        state.replicas.put(replicaIndex, new ReplicaState(containerName, hostPort, baseUrl));
    }

    private void removeReplica(FunctionState state, int replicaIndex) {
        ReplicaState removed = state.replicas.remove(replicaIndex);
        if (removed != null) {
            adapter.removeContainer(removed.containerName());
        }
    }

    private String requirePersistedPrefix(String functionName, Map<String, String> deploymentObjects) {
        String expected = containerNamePrefix(functionName);
        String persisted = deploymentObjects == null ? null
                : deploymentObjects.get(ProvisionResult.CONTAINER_NAME_PREFIX);
        if (persisted == null || persisted.isBlank() || !expected.equals(persisted)) {
            throw new IllegalArgumentException("Persisted container name prefix '" + persisted
                    + "' does not match function '" + functionName + "'");
        }
        return expected;
    }

    private static void validateReplicaIndexes(String prefix, List<ManagedContainer> discovered) {
        Set<Integer> seen = new HashSet<>();
        for (ManagedContainer container : discovered) {
            if (container.replicaIndex() < 1) {
                throw new IllegalArgumentException("Managed container '" + container.name()
                        + "' has an invalid replica index");
            }
            if (!container.name().equals(prefix + "-r" + container.replicaIndex())) {
                throw new IllegalArgumentException("Managed container '" + container.name()
                        + "' does not match the persisted prefix '" + prefix + "'");
            }
            if (!seen.add(container.replicaIndex())) {
                throw new IllegalArgumentException("Duplicate replica index " + container.replicaIndex());
            }
        }
    }

    private boolean adoptable(ManagedContainer container) {
        if (!container.running()) {
            return false;
        }
        return properties.networkName() != null || container.hostPort() != null;
    }

    private void createMissingReplicas(FunctionState state, int desiredReplicas, Set<String> createdDuringReconcile) {
        for (int replicaIndex = 1; replicaIndex <= desiredReplicas; replicaIndex++) {
            if (!state.replicas.containsKey(replicaIndex)) {
                addReplica(state, replicaIndex);
                createdDuringReconcile.add(containerName(state.spec.name(), replicaIndex));
            }
        }
        state.proxy.updateBackends(state.replicas.values().stream().map(ReplicaState::baseUrl).toList());
    }

    private int desiredReplicas(FunctionSpec spec) {
        ScalingConfig scalingConfig = spec.scalingConfig();
        if (scalingConfig == null || scalingConfig.minReplicas() == null) {
            return 1;
        }
        return Math.max(0, scalingConfig.minReplicas());
    }

    private Map<String, String> buildEnv(FunctionSpec spec) {
        LinkedHashMap<String, String> env = new LinkedHashMap<>();
        env.put("FUNCTION_NAME", spec.name());
        env.put("WARM", "true");
        env.put("TIMEOUT_MS", String.valueOf(spec.timeoutMs()));

        if (spec.runtimeMode() != null) {
            env.put("EXECUTION_MODE", spec.runtimeMode().name());
        }
        if (spec.runtimeCommand() != null && !spec.runtimeCommand().isBlank()) {
            env.put("WATCHDOG_CMD", spec.runtimeCommand());
        }
        if (properties.callbackUrl() != null && !properties.callbackUrl().isBlank()) {
            env.put("CALLBACK_URL", properties.callbackUrl());
        }
        if (spec.env() != null) {
            spec.env().forEach((key, value) -> {
                if (!RESERVED_ENV.contains(key) && value != null) {
                    env.put(key, value);
                }
            });
        }
        return env;
    }

    private static Map<String, String> deploymentObjects(String functionName) {
        return Map.of(ProvisionResult.CONTAINER_NAME_PREFIX, containerNamePrefix(functionName));
    }

    private static String containerNamePrefix(String functionName) {
        return "nanofaas-" + normalizeName(functionName);
    }

    static int replicaIndex(String containerName) {
        if (containerName == null) {
            return -1;
        }
        int separator = containerName.lastIndexOf("-r");
        if (separator < 0) {
            return -1;
        }
        try {
            return Integer.parseInt(containerName.substring(separator + 2));
        } catch (NumberFormatException _) {
            return -1;
        }
    }

    private String containerName(String functionName, int replicaIndex) {
        return containerNamePrefix(functionName) + "-r" + replicaIndex;
    }

    private String baseUrl(String containerName, Integer hostPort) {
        if (properties.networkName() != null) {
            return "http://" + containerName + ":8080";
        }
        return "http://" + properties.bindHost() + ":" + hostPort;
    }

    private static String normalizeName(String functionName) {
        String normalized = functionName == null ? "fn" : functionName.toLowerCase()
                .replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^(?>-+)", "");
        normalized = stripTrailingDashes(normalized);
        return normalized.isBlank() ? "fn" : normalized;
    }

    private static String stripTrailingDashes(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '-') {
            end--;
        }
        return s.substring(0, end);
    }

    private static void safeClose(ManagedFunctionProxy proxy) {
        try {
            proxy.close();
        } catch (RuntimeException _) {
            // Best-effort cleanup.
        }
    }

    private static void suppressCleanupFailure(RuntimeException provisioningFailure, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException cleanupFailure) {
            provisioningFailure.addSuppressed(cleanupFailure);
        }
    }

    private static final class FunctionState {
        private final FunctionSpec spec;
        private final ManagedFunctionProxy proxy;
        private final LinkedHashMap<Integer, ReplicaState> replicas = new LinkedHashMap<>();

        private FunctionState(FunctionSpec spec, ManagedFunctionProxy proxy) {
            this.spec = spec;
            this.proxy = proxy;
        }
    }

    private record ReplicaState(String containerName, Integer hostPort, String baseUrl) {
    }
}
