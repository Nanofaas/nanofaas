package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ContainerLocalDeploymentProvider implements ManagedDeploymentProvider {

    static final String BACKEND_ID = "container-local";
    private static final Set<String> RESERVED_ENV = Set.of(
            "FUNCTION_NAME", "WARM", "TIMEOUT_MS", "EXECUTION_MODE", "WATCHDOG_CMD", "CALLBACK_URL"
    );

    private final ContainerRuntimeAdapter adapter;
    private final ContainerLocalProperties properties;
    private final EndpointProbe endpointProbe;
    private final PortAllocator portAllocator;
    private final ManagedFunctionProxyFactory proxyFactory;
    private final Map<String, FunctionState> states = new ConcurrentHashMap<>();

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
    public synchronized ProvisionResult provision(FunctionSpec spec) {
        FunctionState existing = states.get(spec.name());
        if (existing != null) {
            return new ProvisionResult(existing.proxy.endpointUrl(), backendId());
        }

        ManagedFunctionProxy proxy = proxyFactory.create(spec.name());
        FunctionState state = new FunctionState(spec, proxy);
        states.put(spec.name(), state);
        try {
            scaleTo(state, desiredReplicas(spec));
            return new ProvisionResult(proxy.endpointUrl(), backendId());
        } catch (RuntimeException e) {
            states.remove(spec.name());
            safeClose(proxy);
            throw e;
        }
    }

    @Override
    public synchronized void deprovision(String functionName) {
        FunctionState state = states.remove(functionName);
        if (state == null) {
            return;
        }

        for (int replicaIndex : List.copyOf(state.replicas.keySet()).reversed()) {
            removeReplica(state, replicaIndex);
        }
        safeClose(state.proxy);
    }

    @Override
    public synchronized void setReplicas(String functionName, int replicas) {
        FunctionState state = states.get(functionName);
        if (state == null) {
            return;
        }
        scaleTo(state, Math.max(0, replicas));
    }

    @Override
    public synchronized int getReadyReplicas(String functionName) {
        FunctionState state = states.get(functionName);
        if (state == null) {
            return 0;
        }
        return (int) state.replicas.values().stream()
                .filter(replica -> endpointProbe.isReady(replica.baseUrl()))
                .count();
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
        int hostPort = portAllocator.nextPort();
        String baseUrl = baseUrl(hostPort);
        ContainerInstanceSpec instanceSpec = new ContainerInstanceSpec(
                containerName,
                state.spec.image(),
                hostPort,
                state.spec.command() == null ? List.of() : state.spec.command(),
                buildEnv(state.spec)
        );

        adapter.runContainer(instanceSpec);
        try {
            endpointProbe.awaitReady(baseUrl, properties.readinessTimeout(), properties.readinessPollInterval());
        } catch (RuntimeException e) {
            adapter.removeContainer(containerName);
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

    private String containerName(String functionName, int replicaIndex) {
        String normalized = normalizeName(functionName);
        return "nanofaas-" + normalized + "-r" + replicaIndex;
    }

    private String baseUrl(int hostPort) {
        return "http://" + properties.bindHost() + ":" + hostPort;
    }

    private static String normalizeName(String functionName) {
        String normalized = functionName == null ? "fn" : functionName.toLowerCase()
                .replaceAll("[^a-z0-9-]", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        return normalized.isBlank() ? "fn" : normalized;
    }

    private static void safeClose(ManagedFunctionProxy proxy) {
        try {
            proxy.close();
        } catch (RuntimeException ignored) {
            // Best-effort cleanup.
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

    private record ReplicaState(String containerName, int hostPort, String baseUrl) {
    }
}
