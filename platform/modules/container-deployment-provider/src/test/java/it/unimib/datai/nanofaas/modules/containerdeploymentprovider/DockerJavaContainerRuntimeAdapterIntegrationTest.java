package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

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
                    18089,
                    List.of("sh", "-c", "sleep 30"),
                    Map.of("NANOFAAS_SPIKE", "true"),
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
                    null,
                    List.of("sh", "-c", "sleep 30"),
                    Map.of(),
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

    private static void pullTestImage(DockerClient client) throws InterruptedException {
        client.pullImageCmd(TEST_IMAGE)
                .exec(new PullImageResultCallback())
                .awaitCompletion();
    }
}
