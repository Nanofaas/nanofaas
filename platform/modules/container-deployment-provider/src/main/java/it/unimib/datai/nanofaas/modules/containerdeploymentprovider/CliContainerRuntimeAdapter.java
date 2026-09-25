package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import it.unimib.datai.nanofaas.containerdeployment.LocalManagedDeploymentProvider;
import it.unimib.datai.nanofaas.containerdeployment.ContainerRuntimeAdapter;
import it.unimib.datai.nanofaas.containerdeployment.ContainerInstanceSpec;
import it.unimib.datai.nanofaas.containerdeployment.ManagedContainer;

import it.unimib.datai.nanofaas.common.model.ResourceQuantity;
import it.unimib.datai.nanofaas.common.model.ResourceSpec;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class CliContainerRuntimeAdapter implements ContainerRuntimeAdapter {

    private final String runtimeAdapter;
    private final CliCommandExecutor executor;
    private final String cpuset;
    private final String bindHost;
    private final PortAllocator portAllocator;

    CliContainerRuntimeAdapter(String runtimeAdapter, CliCommandExecutor executor) {
        this(runtimeAdapter, executor, null);
    }

    CliContainerRuntimeAdapter(String runtimeAdapter, CliCommandExecutor executor, String cpuset) {
        this(runtimeAdapter, executor, cpuset, "127.0.0.1", new EphemeralPortAllocator("127.0.0.1"));
    }

    CliContainerRuntimeAdapter(String runtimeAdapter, CliCommandExecutor executor, String cpuset,
                               String bindHost, PortAllocator portAllocator) {
        this.bindHost = bindHost;
        this.portAllocator = portAllocator;
        this.runtimeAdapter = runtimeAdapter == null || runtimeAdapter.isBlank() ? "docker" : runtimeAdapter.trim();
        this.executor = executor;
        this.cpuset = cpuset == null || cpuset.isBlank() ? null : cpuset.trim();
    }

    @Override
    public boolean isAvailable() {
        return executor.run(List.of(runtimeAdapter, "version")).isSuccess();
    }

    @Override
    public void pullImage(String image) {
        ExecutionResult result = executor.run(List.of(runtimeAdapter, "pull", image));
        if (!result.isSuccess()) {
            throw new IllegalStateException("Failed to pull image '" + image + "': " + result.output());
        }
    }

    @Override
    public ManagedContainer runContainer(ContainerInstanceSpec spec) {
        int hostPort = portAllocator.nextPort();
        executor.run(List.of(runtimeAdapter, "rm", "-f", spec.containerName()));

        List<String> command = new ArrayList<>();
        command.add(runtimeAdapter);
        command.add("run");
        command.add("-d");
        command.add("--name");
        command.add(spec.containerName());
        command.add("-p");
        command.add(hostPort + ":8080");
        addResourceFlags(command, spec.resources());
        if (cpuset != null) {
            // Every function on the same cores, so the platform's capacity is something they
            // have to divide rather than something each is capped against independently.
            command.add("--cpuset-cpus");
            command.add(cpuset);
        }
        spec.env().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .forEach(entry -> {
                    command.add("-e");
                    command.add(entry.getKey() + "=" + entry.getValue());
                });
        spec.labels().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .forEach(entry -> {
                    command.add("--label");
                    command.add(entry.getKey() + "=" + entry.getValue());
                });
        command.add(spec.image());
        if (spec.command() != null && !spec.command().isEmpty()) {
            command.addAll(spec.command());
        }

        ExecutionResult result = executor.run(command);
        if (!result.isSuccess()) {
            throw new IllegalStateException("Failed to start container '" + spec.containerName() + "': " + result.output());
        }
        return new ManagedContainer(spec.containerName(),
                LocalManagedDeploymentProvider.replicaIndex(spec.containerName()), baseUrl(hostPort), true);
    }

    private static void addResourceFlags(List<String> command, ResourceSpec resources) {
        if (resources == null) {
            return;
        }
        ResourceQuantity requests = resources.requests();
        ResourceQuantity limits = resources.limits();
        if (requests != null && requests.cpu() != null) {
            int shares = Math.max(2, requests.cpu().multiply(BigDecimal.valueOf(1024))
                    .setScale(0, RoundingMode.HALF_UP).intValueExact());
            command.add("--cpu-shares");
            command.add(Integer.toString(shares));
        }
        if (limits != null && limits.cpu() != null) {
            command.add("--cpus");
            command.add(limits.cpu().stripTrailingZeros().toPlainString());
        }
        if (requests != null && requests.memoryMiB() != null
                && (limits == null || !requests.memoryMiB().equals(limits.memoryMiB()))) {
            command.add("--memory-reservation");
            command.add(requests.memoryMiB() + "m");
        }
        if (limits != null && limits.memoryMiB() != null) {
            command.add("--memory");
            command.add(limits.memoryMiB() + "m");
        }
    }

    @Override
    public void removeContainer(String containerName) {
        executor.run(List.of(runtimeAdapter, "rm", "-f", containerName));
    }

    @Override
    public List<ManagedContainer> listManagedContainers(String functionName) {
        ExecutionResult listing = executor.run(List.of(
                runtimeAdapter, "ps", "-a",
                "--filter", "label=" + LocalManagedDeploymentProvider.MANAGED_LABEL + "=true",
                "--filter", "label=" + LocalManagedDeploymentProvider.FUNCTION_LABEL + "=" + functionName,
                "--format", "{{.Names}}\t{{.State}}"));
        if (!listing.isSuccess()) {
            return List.of();
        }

        List<ManagedContainer> containers = new ArrayList<>();
        for (String line : listing.output().lines().toList()) {
            if (line.isBlank()) {
                continue;
            }
            int tab = line.indexOf('\t');
            String name = tab < 0 ? line.strip() : line.substring(0, tab);
            boolean running = tab >= 0 && "running".equalsIgnoreCase(line.substring(tab + 1).strip());
            containers.add(new ManagedContainer(
                    name,
                    LocalManagedDeploymentProvider.replicaIndex(name),
                    running ? baseUrl(publishedPort(name)) : null,
                    running));
        }
        return containers;
    }

    private String baseUrl(Integer hostPort) {
        return hostPort == null ? null : "http://" + bindHost + ":" + hostPort;
    }

    private Integer publishedPort(String containerName) {
        // A transient `docker port` miss (CLI hiccup) must not make a running replica look
        // unaddressable and get removed + recreated by reconcile. Retry briefly.
        for (int attempt = 0; attempt < 3; attempt++) {
            ExecutionResult port = executor.run(List.of(runtimeAdapter, "port", containerName, "8080/tcp"));
            if (port.isSuccess() && !port.output().isBlank()) {
                String output = port.output().strip();
                int colon = output.lastIndexOf(':');
                try {
                    return Integer.parseInt(colon < 0 ? output : output.substring(colon + 1));
                } catch (NumberFormatException _) {
                    return null;
                }
            }
        }
        return null;
    }
}
