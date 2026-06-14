package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

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
    public void runContainer(ContainerInstanceSpec spec) {
        executor.run(List.of(runtimeAdapter, "rm", "-f", spec.containerName()));

        List<String> command = new ArrayList<>();
        command.add(runtimeAdapter);
        command.add("run");
        command.add("-d");
        command.add("--name");
        command.add(spec.containerName());
        command.add("-p");
        command.add(spec.hostPort() + ":8080");
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

    @Override
    public void removeContainer(String containerName) {
        executor.run(List.of(runtimeAdapter, "rm", "-f", containerName));
    }
}
