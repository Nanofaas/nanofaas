package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import it.unimib.datai.nanofaas.containerdeployment.ContainerInstanceSpec;
import it.unimib.datai.nanofaas.containerdeployment.ManagedContainer;

import it.unimib.datai.nanofaas.common.model.ResourceQuantity;
import it.unimib.datai.nanofaas.common.model.ResourceSpec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CliContainerRuntimeAdapterTest {

    @Test
    void nameConflictNeverDeletesExistingContainer() {
        var executor = new RecordingCliCommandExecutor()
                .withResult(ExecutionResult.failure(125, "container name already in use"));
        var adapter = new CliContainerRuntimeAdapter("docker", executor, null, "127.0.0.1", () -> 18080);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.runContainer(instance(null)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(executor.commands()).noneMatch(command -> command.contains("rm"));
    }

    @Test void immutableLocalImageNeverFallsBackToMutablePull() {
        String id="sha256:"+"a".repeat(64);
        var executor=new RecordingCliCommandExecutor().withResult(ExecutionResult.success(id+"\n")).withResult(ExecutionResult.success("sha256:"+"b".repeat(64)));
        var adapter=new CliContainerRuntimeAdapter("docker",executor,null,"127.0.0.1",()->18080);
        adapter.pullImage(id);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->adapter.pullImage(id)).isInstanceOf(IllegalStateException.class);
        assertThat(executor.commands()).containsExactly(List.of("docker","image","inspect","--format","{{.Id}}",id),List.of("docker","image","inspect","--format","{{.Id}}",id));
    }

    @Test
    void isAvailable_returnsFalseWhenVersionCommandFails() {
        RecordingCliCommandExecutor executor = new RecordingCliCommandExecutor()
                .withResult(ExecutionResult.failure(1, "missing runtime"));
        CliContainerRuntimeAdapter adapter = new CliContainerRuntimeAdapter("nerdctl", executor, null, "127.0.0.1", () -> 18080);

        assertThat(adapter.isAvailable()).isFalse();
        assertThat(executor.commands()).containsExactly(List.of("nerdctl", "version"));
    }

    @Test
    void runContainer_buildsDockerCompatibleCommand() {
        RecordingCliCommandExecutor executor = new RecordingCliCommandExecutor()
                .withResult(ExecutionResult.success(""))
                .withResult(ExecutionResult.success(""));
        CliContainerRuntimeAdapter adapter = new CliContainerRuntimeAdapter("podman", executor, null, "127.0.0.1", () -> 18080);

        ManagedContainer managed = adapter.runContainer(new ContainerInstanceSpec(
                "nanofaas-echo-r1",
                "img:latest",
                List.of("java", "-jar", "app.jar"),
                new LinkedHashMap<>(Map.of(
                        "FUNCTION_NAME", "echo",
                        "WARM", "true"
                )),
                new ResourceSpec(
                        new ResourceQuantity(new BigDecimal("0.25"), 256),
                        new ResourceQuantity(BigDecimal.ONE, 512)
                ),
                null
        ));

        assertThat(managed).isEqualTo(new ManagedContainer("nanofaas-echo-r1", 1, "http://127.0.0.1:18080", true));
        assertThat(executor.commands()).containsExactly(
                List.of(
                        "podman", "run", "-d",
                        "--name", "nanofaas-echo-r1",
                        "-p", "18080:8080",
                        "--cpu-shares", "256",
                        "--cpus", "1",
                        "--memory-reservation", "256m",
                        "--memory", "512m",
                        "-e", "FUNCTION_NAME=echo",
                        "-e", "WARM=true",
                        "img:latest",
                        "java", "-jar", "app.jar"
                )
        );
    }

    @Test
    void runContainer_omitsRedundantMemoryReservation() {
        RecordingCliCommandExecutor executor = new RecordingCliCommandExecutor();
        CliContainerRuntimeAdapter adapter = new CliContainerRuntimeAdapter("docker", executor, null, "127.0.0.1", () -> 18080);

        adapter.runContainer(instance(new ResourceSpec(
                new ResourceQuantity(null, 256),
                new ResourceQuantity(null, 256)
        )));

        assertThat(executor.commands().getFirst())
                .contains("--memory", "256m")
                .doesNotContain("--memory-reservation");
    }

    @Test
    void runContainer_withoutResources_omitsResourceFlags() {
        RecordingCliCommandExecutor executor = new RecordingCliCommandExecutor();
        CliContainerRuntimeAdapter adapter = new CliContainerRuntimeAdapter("docker", executor, null, "127.0.0.1", () -> 18080);

        adapter.runContainer(instance(null));

        assertThat(executor.commands().getFirst())
                .doesNotContain("--cpu-shares", "--cpus", "--memory-reservation", "--memory");
    }

    private static ContainerInstanceSpec instance(ResourceSpec resources) {
        return new ContainerInstanceSpec("fn-r1", "img", List.of(), Map.of(), resources, null);
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

    @Test
    void pinsEveryContainerToTheSharedCoreSetWhenOneIsConfigured() {
        // A per-function CPU limit caps each container separately, so on a host with spare cores
        // the functions never compete: two with four CPUs each on an eleven-core machine barely
        // affected one another. Sharing a core set is what makes capacity a quantity they divide.
        RecordingCliCommandExecutor executor = new RecordingCliCommandExecutor()
                .withResult(ExecutionResult.success(""))
                .withResult(ExecutionResult.success(""));
        CliContainerRuntimeAdapter adapter =
                new CliContainerRuntimeAdapter("docker", executor, "0-3", "127.0.0.1", () -> 18080);

        adapter.runContainer(new ContainerInstanceSpec(
                "nanofaas-echo-r1", "img:latest", List.of(), Map.of(), null, null));

        assertThat(executor.commands().getFirst()).containsSequence("--cpuset-cpus", "0-3");
    }

    @Test
    void leavesTheCoreSetAloneWhenNoneIsConfigured() {
        RecordingCliCommandExecutor executor = new RecordingCliCommandExecutor()
                .withResult(ExecutionResult.success(""))
                .withResult(ExecutionResult.success(""));
        CliContainerRuntimeAdapter adapter = new CliContainerRuntimeAdapter("docker", executor, null, "127.0.0.1", () -> 18080);

        adapter.runContainer(new ContainerInstanceSpec(
                "nanofaas-echo-r1", "img:latest", List.of(), Map.of(), null, null));

        assertThat(executor.commands().getFirst()).doesNotContain("--cpuset-cpus");
    }

    @Test
    void runContainer_passesOwnershipLabels() {
        RecordingCliCommandExecutor executor = new RecordingCliCommandExecutor()
                .withResult(ExecutionResult.success(""))
                .withResult(ExecutionResult.success(""));
        CliContainerRuntimeAdapter adapter = new CliContainerRuntimeAdapter("docker", executor, null, "127.0.0.1", () -> 18080);

        adapter.runContainer(new ContainerInstanceSpec(
                "nanofaas-echo-r1", "img:latest", List.of(), Map.of(), null,
                Map.of(
                        ContainerLocalDeploymentProvider.MANAGED_LABEL, "true",
                        ContainerLocalDeploymentProvider.FUNCTION_LABEL, "echo",
                        ContainerLocalDeploymentProvider.REPLICA_LABEL, "1")
        ));

        assertThat(executor.commands().getFirst()).containsSubsequence(
                "--label", "io.nanofaas.function=echo",
                "--label", "io.nanofaas.managed=true",
                "--label", "io.nanofaas.replica=1"
        );
    }

    @Test
    void listManagedContainers_returnsRunningAndStoppedOwnedReplicasWithIndexAndHostPort() {
        RecordingCliCommandExecutor executor = new RecordingCliCommandExecutor()
                .withResult(ExecutionResult.success("nanofaas-echo-r1\trunning\nnanofaas-echo-r2\texited\n"))
                .withResult(ExecutionResult.success("0.0.0.0:31001"));
        CliContainerRuntimeAdapter adapter = new CliContainerRuntimeAdapter("docker", executor, null, "127.0.0.1", () -> 18080);

        assertThat(adapter.listManagedContainers("echo")).containsExactly(
                new ManagedContainer("nanofaas-echo-r1", 1, "http://127.0.0.1:31001", true),
                new ManagedContainer("nanofaas-echo-r2", 2, null, false)
        );

        assertThat(executor.commands()).containsExactly(
                List.of(
                        "docker", "ps", "-a",
                        "--filter", "label=io.nanofaas.managed=true",
                        "--filter", "label=io.nanofaas.function=echo",
                        "--format", "{{.Names}}\t{{.State}}"),
                List.of("docker", "port", "nanofaas-echo-r1", "8080/tcp")
        );
    }
}