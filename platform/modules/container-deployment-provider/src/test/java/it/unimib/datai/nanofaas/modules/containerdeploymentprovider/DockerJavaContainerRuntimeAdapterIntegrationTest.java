package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import it.unimib.datai.nanofaas.containerdeployment.ContainerInstanceSpec;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.core.command.PullImageResultCallback;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DockerJavaContainerRuntimeAdapterIntegrationTest {

    private static final String TEST_IMAGE = "busybox:1.36";

    @Test
    void adapterCompletesLifecycleAgainstRealDockerEngine() throws Exception {
        String containerName = "nanofaas-docker-java-spike-" + UUID.randomUUID();
        DockerClient client = ContainerDeploymentProviderConfiguration.createDockerClient();
        DockerJavaContainerRuntimeAdapter adapter = new DockerJavaContainerRuntimeAdapter(client);
        assumeTrue(adapter.isAvailable(), "Docker Engine is unavailable");
        pullTestImage(client);

        try {
            adapter.runContainer(new ContainerInstanceSpec(
                    containerName,
                    TEST_IMAGE,
                    List.of("sh", "-c", "sleep 30"),
                    Map.of("NANOFAAS_SPIKE", "true"),
                    null,
                    null
            ));

            InspectContainerResponse inspected = client.inspectContainerCmd(containerName).exec();
            assertThat(inspected.getState().getRunning()).isTrue();
            assertThat(inspected.getConfig().getEnv()).contains("NANOFAAS_SPIKE=true");
            assertThat(client.listContainersCmd().exec())
                    .anySatisfy(container -> assertThat(container.getNames()).contains("/" + containerName));

            adapter.removeContainer(containerName);

            InspectContainerCmd inspectCmd = client.inspectContainerCmd(containerName);
            assertThatThrownBy(inspectCmd::exec)
                    .isInstanceOf(NotFoundException.class);
        } finally {
            adapter.removeContainer(containerName);
            adapter.close();
        }
    }

    @Test
    void adapterJoinsReplicaToDockerNetworkWithoutPublishingPorts() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String networkName = "nanofaas-spike-" + suffix;
        String containerName = "nanofaas-network-spike-" + suffix;
        DockerClient client = ContainerDeploymentProviderConfiguration.createDockerClient();
        DockerJavaContainerRuntimeAdapter adapter = new DockerJavaContainerRuntimeAdapter(client, networkName);
        assumeTrue(adapter.isAvailable(), "Docker Engine is unavailable");
        pullTestImage(client);
        client.createNetworkCmd().withName(networkName).exec();

        try {
            adapter.runContainer(new ContainerInstanceSpec(
                    containerName,
                    TEST_IMAGE,
                    List.of("sh", "-c", "sleep 30"),
                    Map.of(),
                    null,
                    null
            ));

            InspectContainerResponse inspected = client.inspectContainerCmd(containerName).exec();
            assertThat(inspected.getHostConfig().getNetworkMode()).isEqualTo(networkName);
            assertThat(inspected.getNetworkSettings().getNetworks()).containsKey(networkName);
            assertThat(inspected.getNetworkSettings().getPorts().getBindings().values())
                    .allMatch(Objects::isNull);
        } finally {
            adapter.removeContainer(containerName);
            client.removeNetworkCmd(networkName).exec();
            adapter.close();
        }
    }

    @Test
    void creatingOccupiedNamePreservesTheOriginalDockerContainer() throws Exception {
        String name = "nanofaas-ownership-audit-" + UUID.randomUUID();
        var client = ContainerDeploymentProviderConfiguration.createDockerClient();
        var adapter = new DockerJavaContainerRuntimeAdapter(client);
        assumeTrue(adapter.isAvailable(), "Docker Engine is unavailable");
        pullTestImage(client);
        try {
            adapter.runContainer(new ContainerInstanceSpec(name, TEST_IMAGE,
                    List.of("sh", "-c", "sleep 30"), Map.of(), null, Map.of("audit-owner", "original")));
            String original = client.inspectContainerCmd(name).exec().getId();
            assertThatThrownBy(() -> adapter.runContainer(new ContainerInstanceSpec(name, TEST_IMAGE,
                    List.of("sh", "-c", "sleep 30"), Map.of(), null, Map.of("audit-owner", "other"))))
                    .isInstanceOf(it.unimib.datai.nanofaas.containerdeployment.ContainerNameConflictException.class);
            var retained = client.inspectContainerCmd(name).exec();
            assertThat(retained.getId()).isEqualTo(original);
            assertThat(retained.getState().getRunning()).isTrue();
            assertThat(retained.getConfig().getLabels()).containsEntry("audit-owner", "original");
        } finally {
            adapter.removeContainer(name);
            adapter.close();
        }
    }

    /** A scale-up starts its replicas at once, through one adapter and one Docker client. */
    @Test
    void startsReplicasConcurrentlyOnDistinctHostPorts() throws Exception {
        String prefix = "nanofaas-concurrent-start-" + UUID.randomUUID() + "-r";
        var client = ContainerDeploymentProviderConfiguration.createDockerClient();
        var adapter = new DockerJavaContainerRuntimeAdapter(client);
        assumeTrue(adapter.isAvailable(), "Docker Engine is unavailable");
        pullTestImage(client);
        List<String> names = java.util.stream.IntStream.rangeClosed(1, 6).mapToObj(index -> prefix + index).toList();
        try {
            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var starts = names.stream().map(name -> executor.submit(() -> adapter.runContainer(new ContainerInstanceSpec(
                        name, TEST_IMAGE, List.of("sh", "-c", "sleep 30"), Map.of(), null, null)))).toList();
                for (var start : starts) start.get();
            }
            var hostPorts = new java.util.HashSet<String>();
            for (String name : names) {
                var inspected = client.inspectContainerCmd(name).exec();
                assertThat(inspected.getState().getRunning()).isTrue();
                for (var bindings : inspected.getNetworkSettings().getPorts().getBindings().values()) {
                    // Docker may bind the same port on both IPv4 and IPv6: count ports, not bindings.
                    for (var binding : bindings) hostPorts.add(binding.getHostPortSpec());
                }
            }
            assertThat(hostPorts).hasSize(names.size());
        } finally {
            names.forEach(adapter::removeContainer);
            adapter.close();
        }
    }

    private static void pullTestImage(DockerClient client) throws InterruptedException {
        client.pullImageCmd(TEST_IMAGE)
                .exec(new PullImageResultCallback())
                .awaitCompletion();
    }
}
