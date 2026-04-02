package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CliContainerRuntimeAdapterTest {

    @Test
    void isAvailable_returnsFalseWhenVersionCommandFails() {
        RecordingCliCommandExecutor executor = new RecordingCliCommandExecutor()
                .withResult(ExecutionResult.failure(1, "missing runtime"));
        CliContainerRuntimeAdapter adapter = new CliContainerRuntimeAdapter("nerdctl", executor);

        assertThat(adapter.isAvailable()).isFalse();
        assertThat(executor.commands()).containsExactly(List.of("nerdctl", "version"));
    }

    @Test
    void runContainer_buildsDockerCompatibleCommand() {
        RecordingCliCommandExecutor executor = new RecordingCliCommandExecutor()
                .withResult(ExecutionResult.success(""))
                .withResult(ExecutionResult.success(""));
        CliContainerRuntimeAdapter adapter = new CliContainerRuntimeAdapter("podman", executor);

        adapter.runContainer(new ContainerInstanceSpec(
                "nanofaas-echo-r1",
                "img:latest",
                18080,
                List.of("java", "-jar", "app.jar"),
                new LinkedHashMap<>(Map.of(
                        "FUNCTION_NAME", "echo",
                        "WARM", "true"
                ))
        ));

        assertThat(executor.commands()).containsExactly(
                List.of("podman", "rm", "-f", "nanofaas-echo-r1"),
                List.of(
                        "podman", "run", "-d",
                        "--name", "nanofaas-echo-r1",
                        "-p", "18080:8080",
                        "-e", "FUNCTION_NAME=echo",
                        "-e", "WARM=true",
                        "img:latest",
                        "java", "-jar", "app.jar"
                )
        );
    }

    private static final class RecordingCliCommandExecutor implements CliCommandExecutor {
        private final List<List<String>> commands = new ArrayList<>();
        private final List<ExecutionResult> results = new ArrayList<>();

        RecordingCliCommandExecutor withResult(ExecutionResult result) {
            results.add(result);
            return this;
        }

        List<List<String>> commands() {
            return commands;
        }

        @Override
        public ExecutionResult run(List<String> command) {
            commands.add(List.copyOf(command));
            if (results.isEmpty()) {
                return ExecutionResult.success("");
            }
            return results.removeFirst();
        }
    }
}
