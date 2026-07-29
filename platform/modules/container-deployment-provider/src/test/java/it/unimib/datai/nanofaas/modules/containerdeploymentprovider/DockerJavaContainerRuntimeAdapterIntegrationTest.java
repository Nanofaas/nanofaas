package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DockerJavaContainerRuntimeAdapterIntegrationTest {

    @Test
    void adapterCompletesLifecycleAgainstRealDockerEngine() throws Exception {
        String containerName = "nanofaas-docker-java-spike-" + UUID.randomUUID();
        DockerClient client = ContainerDeploymentProviderConfiguration.createDockerClient();
        DockerJavaContainerRuntimeAdapter adapter = new DockerJavaContainerRuntimeAdapter(client);
        assumeTrue(adapter.isAvailable(), "Docker Engine is unavailable");

        try {
            adapter.runContainer(new ContainerInstanceSpec(
                    containerName,
                    "eclipse-temurin:21-jre",
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

            assertThatThrownBy(() -> client.inspectContainerCmd(containerName).exec())
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
        client.createNetworkCmd().withName(networkName).exec();

        try {
            adapter.runContainer(new ContainerInstanceSpec(
                    containerName,
                    "eclipse-temurin:21-jre",
                    null,
                    List.of("sh", "-c", "sleep 30"),
                    Map.of(),
                    null
            ));

            InspectContainerResponse inspected = client.inspectContainerCmd(containerName).exec();
            assertThat(inspected.getHostConfig().getNetworkMode()).isEqualTo(networkName);
            assertThat(inspected.getNetworkSettings().getNetworks()).containsKey(networkName);
            assertThat(inspected.getNetworkSettings().getPorts().getBindings().values())
                    .allMatch(bindings -> bindings == null);
        } finally {
            adapter.removeContainer(containerName);
            client.removeNetworkCmd(networkName).exec();
            adapter.close();
        }
    }
}
