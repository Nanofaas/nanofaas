package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import it.unimib.datai.nanofaas.containerdeployment.LocalManagedDeploymentProvider;
import it.unimib.datai.nanofaas.containerdeployment.ContainerRuntimeAdapter;
import it.unimib.datai.nanofaas.containerdeployment.ContainerInstanceSpec;
import it.unimib.datai.nanofaas.containerdeployment.ManagedContainer;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.core.command.PullImageResultCallback;
import it.unimib.datai.nanofaas.common.model.ResourceQuantity;
import it.unimib.datai.nanofaas.common.model.ResourceSpec;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class DockerJavaContainerRuntimeAdapter implements ContainerRuntimeAdapter, AutoCloseable {

    private final DockerClient client;

    it.unimib.datai.nanofaas.controlplane.deployment.ImageInventorySource imageInventorySource() {
        return new DockerImageInventorySource(client);
    }
    private final String networkName;
    private final String cpuset;
    private final String bindHost;
    private final PortAllocator portAllocator;

    DockerJavaContainerRuntimeAdapter(DockerClient client) {
        this(client, null);
    }

    DockerJavaContainerRuntimeAdapter(DockerClient client, String networkName) {
        this(client, networkName, null);
    }

    DockerJavaContainerRuntimeAdapter(DockerClient client, String networkName, String cpuset) {
        this(client, networkName, cpuset, "127.0.0.1", new EphemeralPortAllocator("127.0.0.1"));
    }

    DockerJavaContainerRuntimeAdapter(DockerClient client, String networkName, String cpuset,
                                      String bindHost, PortAllocator portAllocator) {
        this.bindHost = bindHost;
        this.portAllocator = portAllocator;
        this.client = client;
        this.networkName = networkName;
        this.cpuset = cpuset;
    }

    @Override
    public boolean isAvailable() {
        try {
            client.pingCmd().exec();
            return true;
        } catch (RuntimeException _) {
            return false;
        }
    }

    @Override
    public void pullImage(String image) {
        if(image.matches("sha256:[0-9a-f]{64}")) {
            if(!image.equals(client.inspectImageCmd(image).exec().getId())) throw new IllegalStateException("local immutable image ID mismatch");
            return;
        }
        try {
            client.pullImageCmd(image).exec(new PullImageResultCallback()).awaitCompletion();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while pulling image '" + image + "'");
        }
    }

    @Override
    public ManagedContainer runContainer(ContainerInstanceSpec spec) {

        Integer hostPort = networkName == null ? portAllocator.nextPort() : null;
        ExposedPort functionPort = ExposedPort.tcp(8080);
        HostConfig hostConfig = HostConfig.newHostConfig();
        if (networkName == null) {
            hostConfig.withPortBindings(new PortBinding(
                    Ports.Binding.bindPort(hostPort),
                    functionPort
            ));
        } else {
            hostConfig.withNetworkMode(networkName);
        }
        addResourceLimits(hostConfig, spec.resources());
        if (cpuset != null && !cpuset.isBlank()) {
            // Every function on the same cores, so the platform's capacity is something they
            // have to divide rather than something each is capped against independently.
            hostConfig.withCpusetCpus(cpuset);
        }

        try (CreateContainerCmd create = client.createContainerCmd(spec.image())
                .withName(spec.containerName())
                .withExposedPorts(functionPort)
                .withHostConfig(hostConfig)
                .withLabels(spec.labels())
                .withEnv(environment(spec.env()))) {
            if (spec.command() != null && !spec.command().isEmpty()) {
                create.withCmd(spec.command());
            }

            CreateContainerResponse created;
            try {
                created = create.exec();
            } catch (com.github.dockerjava.api.exception.ConflictException conflict) {
                throw new it.unimib.datai.nanofaas.containerdeployment.ContainerNameConflictException(spec.containerName(), conflict);
            }
            client.startContainerCmd(created.getId()).exec();
        }
        return new ManagedContainer(spec.containerName(),
                LocalManagedDeploymentProvider.replicaIndex(spec.containerName()),
                baseUrl(spec.containerName(), hostPort), true);
    }

    @Override
    public void removeContainer(String containerName) {
        try {
            client.removeContainerCmd(containerName).withForce(true).exec();
        } catch (NotFoundException _) {
            // Removal is idempotent.
        }
    }

    @Override
    public List<ManagedContainer> listManagedContainers(String functionName) {
        return client.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(Map.of(
                        LocalManagedDeploymentProvider.MANAGED_LABEL, "true",
                        LocalManagedDeploymentProvider.FUNCTION_LABEL, functionName))
                .exec().stream()
                .map(this::toManagedContainer)
                .toList();
    }

    private ManagedContainer toManagedContainer(Container container) {
        String name = containerName(container);
        return new ManagedContainer(
                name,
                LocalManagedDeploymentProvider.replicaIndex(name),
                baseUrl(name, publishedHostPort(container)),
                "running".equalsIgnoreCase(container.getState()));
    }

    private String baseUrl(String name, Integer hostPort) {
        if (networkName != null) {
            return "http://" + name + ":8080";
        }
        return hostPort == null ? null : "http://" + bindHost + ":" + hostPort;
    }

    private static String containerName(Container container) {
        String[] names = container.getNames();
        if (names == null || names.length == 0) {
            return "";
        }
        String name = names[0];
        return name.startsWith("/") ? name.substring(1) : name;
    }

    private static Integer publishedHostPort(Container container) {
        ContainerPort[] ports = container.getPorts();
        if (ports == null) {
            return null;
        }
        for (ContainerPort port : ports) {
            if ("tcp".equalsIgnoreCase(port.getType()) && Integer.valueOf(8080).equals(port.getPrivatePort())) {
                return port.getPublicPort();
            }
        }
        return null;
    }

    @Override
    public void close() throws Exception {
        client.close();
    }

    private static List<String> environment(Map<String, String> env) {
        if (env == null || env.isEmpty()) {
            return List.of();
        }
        return env.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toList();
    }

    private static void addResourceLimits(HostConfig hostConfig, ResourceSpec resources) {
        if (resources == null) {
            return;
        }
        ResourceQuantity requests = resources.requests();
        ResourceQuantity limits = resources.limits();
        if (requests != null && requests.cpu() != null) {
            int shares = Math.max(2, requests.cpu().multiply(BigDecimal.valueOf(1024))
                    .setScale(0, RoundingMode.HALF_UP).intValueExact());
            hostConfig.withCpuShares(shares);
        }
        if (limits != null && limits.cpu() != null) {
            hostConfig.withNanoCPUs(limits.cpu().multiply(BigDecimal.valueOf(1_000_000_000L))
                    .setScale(0, RoundingMode.HALF_UP).longValueExact());
        }
        if (requests != null && requests.memoryMiB() != null
                && (limits == null || !requests.memoryMiB().equals(limits.memoryMiB()))) {
            hostConfig.withMemoryReservation(mebibytes(requests.memoryMiB()));
        }
        if (limits != null && limits.memoryMiB() != null) {
            hostConfig.withMemory(mebibytes(limits.memoryMiB()));
        }
    }

    private static long mebibytes(int value) {
        return Math.multiplyExact(value, 1024L * 1024L);
    }
}
