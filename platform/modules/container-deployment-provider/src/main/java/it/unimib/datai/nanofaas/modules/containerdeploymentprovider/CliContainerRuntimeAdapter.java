package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

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

    CliContainerRuntimeAdapter(String runtimeAdapter, CliCommandExecutor executor) {
        this.runtimeAdapter = runtimeAdapter == null || runtimeAdapter.isBlank() ? "docker" : runtimeAdapter.trim();
        this.executor = executor;
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
    public void runContainer(ContainerInstanceSpec spec) {
        if (spec.hostPort() == null) {
            throw new IllegalArgumentException("hostPort is required by the CLI container runtime adapter");
        }
        executor.run(List.of(runtimeAdapter, "rm", "-f", spec.containerName()));

        List<String> command = new ArrayList<>();
        command.add(runtimeAdapter);
        command.add("run");
        command.add("-d");
        command.add("--name");
        command.add(spec.containerName());
        command.add("-p");
        command.add(spec.hostPort() + ":8080");
        addResourceFlags(command, spec.resources());
        spec.env().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .forEach(entry -> {
                    command.add("-e");
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
}
