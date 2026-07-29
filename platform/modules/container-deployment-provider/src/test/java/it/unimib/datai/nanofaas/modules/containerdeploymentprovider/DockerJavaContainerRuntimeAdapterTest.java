package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.PingCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.HostConfig;
import it.unimib.datai.nanofaas.common.model.ResourceQuantity;
import it.unimib.datai.nanofaas.common.model.ResourceSpec;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DockerJavaContainerRuntimeAdapterTest {

    @Test
    void isAvailable_returnsTrueWhenDockerPingSucceeds() {
        DockerClient client = mock(DockerClient.class);
        PingCmd ping = mock(PingCmd.class);
        when(client.pingCmd()).thenReturn(ping);

        DockerJavaContainerRuntimeAdapter adapter = new DockerJavaContainerRuntimeAdapter(client);

        assertThat(adapter.isAvailable()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void runContainer_createsAndStartsDockerContainer() {
        DockerClient client = mock(DockerClient.class);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        CreateContainerCmd create = mock(CreateContainerCmd.class, RETURNS_SELF);
        StartContainerCmd start = mock(StartContainerCmd.class);
        CreateContainerResponse response = new CreateContainerResponse();
        response.setId("container-id");
        when(client.removeContainerCmd("nanofaas-echo-r1")).thenReturn(remove);
        when(client.createContainerCmd("example/echo:latest")).thenReturn(create);
        when(create.exec()).thenReturn(response);
        when(client.startContainerCmd("container-id")).thenReturn(start);

        DockerJavaContainerRuntimeAdapter adapter = new DockerJavaContainerRuntimeAdapter(client);
        adapter.runContainer(new ContainerInstanceSpec(
                "nanofaas-echo-r1",
                "example/echo:latest",
                18080,
                List.of("java", "-jar", "app.jar"),
                new LinkedHashMap<>(Map.of("FUNCTION_NAME", "echo", "WARM", "true")),
                new ResourceSpec(
                        new ResourceQuantity(new BigDecimal("0.25"), 256),
                        new ResourceQuantity(BigDecimal.ONE, 512)
                )
        ));

        ArgumentCaptor<List<String>> env = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<HostConfig> hostConfig = ArgumentCaptor.forClass(HostConfig.class);
        verify(remove).withForce(true);
        verify(remove).exec();
        verify(create).withName("nanofaas-echo-r1");
        verify(create).withEnv(env.capture());
        verify(create).withCmd(List.of("java", "-jar", "app.jar"));
        verify(create).withHostConfig(hostConfig.capture());
        verify(create).exec();
        verify(start).exec();
        assertThat(env.getValue()).containsExactly("FUNCTION_NAME=echo", "WARM=true");
        assertThat(hostConfig.getValue().getCpuShares()).isEqualTo(256);
        assertThat(hostConfig.getValue().getNanoCPUs()).isEqualTo(1_000_000_000L);
        assertThat(hostConfig.getValue().getMemoryReservation()).isEqualTo(256L * 1024 * 1024);
        assertThat(hostConfig.getValue().getMemory()).isEqualTo(512L * 1024 * 1024);
        assertThat(hostConfig.getValue().getPortBindings().getBindings().values())
                .singleElement()
                .satisfies(bindings -> assertThat(bindings[0].getHostPortSpec()).isEqualTo("18080"));
    }

    @Test
    void runContainer_onDockerNetworkDoesNotPublishAHostPort() {
        DockerClient client = mock(DockerClient.class);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        CreateContainerCmd create = mock(CreateContainerCmd.class, RETURNS_SELF);
        StartContainerCmd start = mock(StartContainerCmd.class);
        CreateContainerResponse response = new CreateContainerResponse();
        response.setId("container-id");
        when(client.removeContainerCmd("nanofaas-echo-r1")).thenReturn(remove);
        when(client.createContainerCmd("example/echo:latest")).thenReturn(create);
        when(create.exec()).thenReturn(response);
        when(client.startContainerCmd("container-id")).thenReturn(start);

        DockerJavaContainerRuntimeAdapter adapter = new DockerJavaContainerRuntimeAdapter(client, "nanofaas");
        adapter.runContainer(new ContainerInstanceSpec(
                "nanofaas-echo-r1",
                "example/echo:latest",
                null,
                List.of(),
                Map.of(),
                null
        ));

        ArgumentCaptor<HostConfig> hostConfig = ArgumentCaptor.forClass(HostConfig.class);
        verify(create).withHostConfig(hostConfig.capture());
        assertThat(hostConfig.getValue().getNetworkMode()).isEqualTo("nanofaas");
        assertThat(hostConfig.getValue().getPortBindings()).isNull();
    }

    @Test
    void removeContainer_ignoresAnAlreadyAbsentContainer() {
        DockerClient client = mock(DockerClient.class);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(client.removeContainerCmd("missing")).thenReturn(remove);
        when(remove.exec()).thenThrow(new NotFoundException("missing"));

        DockerJavaContainerRuntimeAdapter adapter = new DockerJavaContainerRuntimeAdapter(client);

        assertThatCode(() -> adapter.removeContainer("missing")).doesNotThrowAnyException();
        verify(remove).withForce(true);
    }
}
