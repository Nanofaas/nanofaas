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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ContainerdRuntimeAdapter implements ContainerRuntimeAdapter {
    public static final String BACKEND_LABEL = "io.nanofaas.backend";
    private static final RemoveOptions REMOVE = RemoveOptions.builder().force(true).removeSnapshot(true).build();
    private static final long CPU_PERIOD_MICROS = 100_000;

    private final ContainerdClient client;
    private final Containers containers;
    private final String networkName;
    private final String cpuset;
    private final String cgroupsPath;
    private final Duration availabilityTimeout;

    public ContainerdRuntimeAdapter(ContainerdClient client, String networkName, String cpuset,
                                    String cgroupsPath, Duration availabilityTimeout) {
        this.client = client;
        this.containers = client.containers();
        this.networkName = networkName;
        this.cpuset = cpuset;
        this.cgroupsPath = cgroupsPath;
        this.availabilityTimeout = availabilityTimeout;
    }

    public ContainerdRuntimeAdapter(ContainerdClient client, ContainerdProperties properties) {
        this(client, properties.networkName(), properties.cpuset(), properties.cgroupsPath(),
                properties.availabilityTimeout());
    }

    @Override
    public boolean isAvailable() {
        FutureTask<Void> check = new FutureTask<>(() -> { client.version(); return null; });
        Thread.ofVirtual().start(check);
        try {
            check.get(availabilityTimeout.toNanos(), TimeUnit.NANOSECONDS);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException failure) {
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
        if (cgroupsPath != null && !cgroupsPath.isBlank()) spec.cgroupsPath(cgroupsPath);
        applyResources(spec, instance.resources());

        boolean created = false;
        try {
            containers.create(spec.build());
            created = true;
            containers.start(instance.containerName());
            String baseUrl = baseUrl(containers.networkAttachment(instance.containerName()));
            if (baseUrl == null) throw new IllegalStateException("containerd container '"
                    + instance.containerName() + "' has no CNI IP address");
            return new ManagedContainer(instance.containerName(),
                    LocalManagedDeploymentProvider.replicaIndex(instance.containerName()), baseUrl, true);
        } catch (RuntimeException failure) {
            if (created) {
                try {
                    containers.remove(instance.containerName(), REMOVE);
                } catch (RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
    }

    @Override
    public void removeContainer(String containerName) {
        Container owned = ownedById(containerName);
        if (owned != null) containers.remove(containerName, REMOVE);
    }

    @Override
    public List<ManagedContainer> listManagedContainers(String functionName) {
        Set<String> pending = new HashSet<>();
        for (Container container : containers.pendingRemovals()) pending.add(container.id());
        List<ManagedContainer> managed = new ArrayList<>();
        for (Container container : containers.list()) {
            if (!ours(container) || !functionName.equals(container.labels().get(LocalManagedDeploymentProvider.FUNCTION_LABEL))) {
                continue;
            }
            boolean running = false;
            String baseUrl = null;
            if (!pending.contains(container.id())) {
                try {
                    running = containers.inspect(container.id()).state() == ContainerState.RUNNING;
                    if (running) {
                        baseUrl = baseUrl(containers.networkAttachment(container.id()));
                        running = baseUrl != null;
                    }
                } catch (RuntimeException unavailable) {
                    // Discovery must never advertise an instance whose task or CNI state cannot be proved.
                    running = false;
                }
            }
            managed.add(new ManagedContainer(container.id(),
                    LocalManagedDeploymentProvider.replicaIndex(container.id()), baseUrl, running));
        }
        return managed;
    }

    private Container ownedById(String id) {
        for (Container container : containers.pendingRemovals()) {
            if (container.id().equals(id)) return requireOwned(container);
        }
        for (Container container : containers.list()) {
            if (container.id().equals(id)) return requireOwned(container);
        }
        return null;
    }

    private static Container requireOwned(Container container) {
        if (!ours(container)) throw new IllegalStateException("Refusing to remove foreign container '" + container.id() + "'");
        return container;
    }

    private static boolean ours(Container container) {
        Map<String, String> labels = container.labels();
        return "containerd".equals(labels.get(BACKEND_LABEL))
                && "true".equals(labels.get(LocalManagedDeploymentProvider.MANAGED_LABEL));
    }

    private static String baseUrl(NetworkAttachment attachment) {
        if (attachment == null) return null;
        for (String cidr : attachment.addresses()) {
            if (cidr == null || cidr.isBlank()) continue;
            String address = cidr.split("/", 2)[0];
            if (address.isBlank()) continue;
            return "http://" + (address.contains(":") ? "[" + address + "]" : address) + ":8080";
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
