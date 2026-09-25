package it.unimib.datai.nanofaas.modules.containerddeploymentprovider;

import io.nanofaas.containerd.Container;
import io.nanofaas.containerd.ContainerSpec;
import io.nanofaas.containerd.ContainerState;
import io.nanofaas.containerd.NetworkAttachment;
import io.nanofaas.containerd.RemoveOptions;
import io.nanofaas.containerd.spi.ContainerdClient;
import io.nanofaas.containerd.spi.Containers;
import it.unimib.datai.nanofaas.common.model.ResourceQuantity;
import it.unimib.datai.nanofaas.common.model.ResourceSpec;
import it.unimib.datai.nanofaas.containerdeployment.ContainerInstanceSpec;
import it.unimib.datai.nanofaas.containerdeployment.ContainerRuntimeAdapter;
import it.unimib.datai.nanofaas.containerdeployment.LocalManagedDeploymentProvider;
import it.unimib.datai.nanofaas.containerdeployment.ManagedContainer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

public final class ContainerdRuntimeAdapter implements ContainerRuntimeAdapter {
    public static final String BACKEND_LABEL = "io.nanofaas.backend";
    private static final RemoveOptions REMOVE = RemoveOptions.builder().force(true).removeSnapshot(true).build();
    private static final long CPU_PERIOD_MICROS = 100_000;

    private final ContainerdClient client;
    private final Containers containers;
    private final String networkName;
    private final String cpuset;
    private final String cgroupsPath;
    private final boolean systemdCgroup;
    private final String cgroupScope;
    private final Duration availabilityTimeout;

    public ContainerdRuntimeAdapter(ContainerdClient client, String networkName, String cpuset,
                                    String cgroupsPath, boolean systemdCgroup, String socketPath,
                                    Duration availabilityTimeout) {
        this.client = client;
        this.containers = client.containers();
        this.networkName = networkName;
        this.cpuset = cpuset;
        this.cgroupsPath = cgroupsPath;
        this.systemdCgroup = systemdCgroup;
        this.cgroupScope = cgroupsPath == null || cgroupsPath.isBlank() ? null
                : ContainerdDeploymentProvider.namePrefix(
                        Path.of(socketPath).toAbsolutePath().normalize() + "\0" + client.namespace());
        this.availabilityTimeout = availabilityTimeout;
    }

    public ContainerdRuntimeAdapter(ContainerdClient client, ContainerdProperties properties) {
        this(client, properties.networkName(), properties.cpuset(), properties.cgroupsPath(),
                properties.systemdCgroup(), properties.socketPath(), properties.availabilityTimeout());
    }

    @Override
    public boolean isAvailable() {
        FutureTask<Void> check = new FutureTask<>(() -> { client.version(); return null; });
        Thread.ofVirtual().start(check);
        try {
            check.get(availabilityTimeout.toNanos(), TimeUnit.NANOSECONDS);
            return true;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException _) {
            return false;
        } finally {
            check.cancel(true);
        }
    }

    @Override
    public void pullImage(String image) {
        client.images().pull(image);
    }

    @Override
    public ManagedContainer runContainer(ContainerInstanceSpec instance) {
        ContainerSpec containerSpec = containerSpec(instance);
        boolean created = false;
        try {
            containers.create(containerSpec);
            created = true;
            containers.start(instance.containerName());
            String baseUrl = baseUrl(containers.networkAttachment(instance.containerName()));
            if (baseUrl == null) throw new IllegalStateException("containerd container '"
                    + instance.containerName() + "' has no CNI IP address");
            return new ManagedContainer(instance.containerName(),
                    LocalManagedDeploymentProvider.replicaIndex(instance.containerName()), baseUrl, true);
        } catch (RuntimeException failure) {
            if (created) removeAfterFailure(instance.containerName(), failure);
            throw failure;
        }
    }

    private ContainerSpec containerSpec(ContainerInstanceSpec instance) {
        Map<String, String> labels = new HashMap<>(instance.labels());
        labels.put(BACKEND_LABEL, "containerd");
        ContainerSpec.Builder spec = ContainerSpec.builder()
                .id(instance.containerName())
                .image(instance.image())
                .network(networkName)
                .environment(instance.env() == null ? Map.of() : instance.env())
                .labels(labels);
        // An empty command preserves the image's ENTRYPOINT and CMD in containerd-java.
        if (instance.command() != null && !instance.command().isEmpty()) spec.command(instance.command());
        if (cpuset != null && !cpuset.isBlank()) spec.cpuSetCpus(cpuset);
        if (cgroupScope != null) {
            String leaf = cgroupScope + "-" + instance.containerName();
            String separator = cgroupsPath.endsWith("/") ? "" : "/";
            spec.cgroupsPath(systemdCgroup ? cgroupsPath + ":nanofaas:" + leaf : cgroupsPath + separator + leaf);
        }
        applyResources(spec, instance.resources());
        return spec.build();
    }

    private void removeAfterFailure(String containerName, RuntimeException failure) {
        try {
            containers.remove(containerName, REMOVE);
        } catch (RuntimeException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    @Override
    public void removeContainer(String containerName) {
        Container owned = ownedById(containerName);
        if (owned != null) containers.remove(containerName, REMOVE);
    }

    @Override
    public List<ManagedContainer> listManagedContainers(String functionName) {
        List<Container> pending = containers.pendingRemovals();
        Map<String, Container> inventory = inventory(pending, container -> ours(container)
                && functionName.equals(container.labels().get(LocalManagedDeploymentProvider.FUNCTION_LABEL)));
        Set<String> pendingIds = new HashSet<>();
        for (Container container : pending) pendingIds.add(container.id());
        List<ManagedContainer> managed = new ArrayList<>();
        Set<Integer> indexes = new HashSet<>();
        for (Container container : inventory.values()) {
            int index = requireReplicaIndex(container);
            if (!indexes.add(index)) throw new IllegalArgumentException("Duplicate replica index " + index);
            boolean running = false;
            String baseUrl = null;
            if (!pendingIds.contains(container.id())) {
                // A daemon failure is not evidence of a stopped task: reconciliation must abort,
                // otherwise a transient timeout could cause removal of a healthy deployment.
                running = containers.inspect(container.id()).state() == ContainerState.RUNNING;
                if (running) {
                    baseUrl = baseUrl(containers.networkAttachment(container.id()));
                    running = baseUrl != null;
                }
            }
            managed.add(new ManagedContainer(container.id(), index, baseUrl, running));
        }
        return managed;
    }

    private Container ownedById(String id) {
        Container container = inventory(containers.pendingRemovals(), candidate -> candidate.id().equals(id)).get(id);
        if (container == null) return null;
        if (!ours(container)) throw new IllegalStateException("Refusing to remove foreign container '" + id + "'");
        int index = requireReplicaIndex(container);
        String function = container.labels().get(LocalManagedDeploymentProvider.FUNCTION_LABEL);
        if (!id.equals(ContainerdDeploymentProvider.namePrefix(function) + "-r" + index)) {
            throw new IllegalStateException("Conflicting function ownership for container '" + id + "'");
        }
        return container;
    }

    private Map<String, Container> inventory(List<Container> pending, Predicate<Container> selected) {
        Map<String, Container> inventory = new LinkedHashMap<>();
        for (Container container : containers.list()) inventory.put(container.id(), container);
        for (Container container : pending) {
            Container existing = inventory.putIfAbsent(container.id(), container);
            if (existing != null && (selected.test(existing) || selected.test(container))) {
                for (String label : List.of(BACKEND_LABEL, LocalManagedDeploymentProvider.MANAGED_LABEL,
                        LocalManagedDeploymentProvider.FUNCTION_LABEL, LocalManagedDeploymentProvider.REPLICA_LABEL)) {
                    if (!Objects.equals(existing.labels().get(label), container.labels().get(label))) {
                        throw new IllegalStateException("Conflicting ownership for container '" + container.id() + "'");
                    }
                }
            }
        }
        inventory.values().removeIf(container -> !selected.test(container));
        return inventory;
    }

    private static int requireReplicaIndex(Container container) {
        int index = LocalManagedDeploymentProvider.replicaIndex(container.id());
        String function = container.labels().get(LocalManagedDeploymentProvider.FUNCTION_LABEL);
        if (index < 1 || !Integer.toString(index).equals(container.labels().get(LocalManagedDeploymentProvider.REPLICA_LABEL))
                || function == null || function.isBlank()) {
            throw new IllegalArgumentException("Invalid ownership/replica labels for container '" + container.id() + "'");
        }
        return index;
    }

    private static boolean ours(Container container) {
        Map<String, String> labels = container.labels();
        return "containerd".equals(labels.get(BACKEND_LABEL))
                && "true".equals(labels.get(LocalManagedDeploymentProvider.MANAGED_LABEL));
    }

    private static String baseUrl(NetworkAttachment attachment) {
        if (attachment == null) return null;
        for (String cidr : attachment.addresses()) {
            String address = cidr == null ? "" : cidr.split("/", 2)[0];
            if (!address.isBlank()) {
                return "http://" + (address.contains(":") ? "[" + address + "]" : address) + ":8080";
            }
        }
        return null;
    }

    private static void applyResources(ContainerSpec.Builder target, ResourceSpec resources) {
        if (resources == null) return;
        ResourceQuantity request = resources.requests();
        ResourceQuantity limit = resources.limits();
        if (request != null) {
            if (request.cpu() != null) {
                long shares = request.cpu().multiply(BigDecimal.valueOf(1024))
                        .setScale(0, RoundingMode.HALF_UP).longValueExact();
                target.cpuShares(Math.max(2, shares));
            }
            if (request.memoryMiB() != null) target.memoryReservationBytes(mebibytes(request.memoryMiB()));
        }
        if (limit != null) {
            if (limit.cpu() != null) {
                target.cpuPeriodMicros(CPU_PERIOD_MICROS);
                target.cpuQuotaMicros(limit.cpu().multiply(BigDecimal.valueOf(CPU_PERIOD_MICROS)).longValueExact());
            }
            if (limit.memoryMiB() != null) target.memoryLimitBytes(mebibytes(limit.memoryMiB()));
        }
    }

    private static long mebibytes(int amount) {
        return Math.multiplyExact(amount, 1024L * 1024L);
    }
}
