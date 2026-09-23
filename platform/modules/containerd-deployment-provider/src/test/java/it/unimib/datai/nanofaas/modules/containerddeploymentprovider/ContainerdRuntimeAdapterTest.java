package it.unimib.datai.nanofaas.modules.containerddeploymentprovider;

import io.nanofaas.containerd.ContainerSpec;
import io.nanofaas.containerd.NetworkAttachment;
import io.nanofaas.containerd.Container;
import io.nanofaas.containerd.ContainerState;
import io.nanofaas.containerd.ContainerStatus;
import io.nanofaas.containerd.spi.ContainerdClient;
import io.nanofaas.containerd.spi.Containers;
import io.nanofaas.containerd.spi.Images;
import it.unimib.datai.nanofaas.common.model.ResourceQuantity;
import it.unimib.datai.nanofaas.common.model.ResourceSpec;
import it.unimib.datai.nanofaas.containerdeployment.ContainerInstanceSpec;
import it.unimib.datai.nanofaas.containerdeployment.ManagedContainer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ContainerdRuntimeAdapterTest {
    private static final String SOCKET = "/run/user/1000/containerd/containerd.sock";
    private final ContainerdClient client = mock(ContainerdClient.class);
    private final Containers containers = mock(Containers.class);
    private final Images images = mock(Images.class);
    private final ContainerdRuntimeAdapter adapter;

    ContainerdRuntimeAdapterTest() {
        when(client.containers()).thenReturn(containers);
        when(client.namespace()).thenReturn("nanofaas");
        when(client.images()).thenReturn(images);
        when(containers.create(any())).thenReturn(mock(io.nanofaas.containerd.Container.class));
        adapter = new ContainerdRuntimeAdapter(client, "nanofaas", "0-3", "user.slice",
                true, SOCKET, Duration.ofSeconds(2));
    }

    @Test
    void runUsesCniAddressAndExactResources() {
        when(containers.networkAttachment("nanofaas-echo-092c79e8f8-r1")).thenReturn(
                new NetworkAttachment(List.of("10.90.0.2/24"), List.of(), List.of(), List.of(), null));
        ResourceSpec resources = new ResourceSpec(
                new ResourceQuantity(new BigDecimal("0.5"), 64),
                new ResourceQuantity(new BigDecimal("1.25"), 128));
        ContainerInstanceSpec instance = new ContainerInstanceSpec("nanofaas-echo-092c79e8f8-r1", "example/echo:1",
                List.of("/app/echo", "--warm"), Map.of("FUNCTION_NAME", "echo"), resources,
                Map.of("io.nanofaas.managed", "true", "io.nanofaas.function", "echo", "io.nanofaas.replica", "1"));

        ManagedContainer running = adapter.runContainer(instance);

        assertThat(running.baseUrl()).isEqualTo("http://10.90.0.2:8080");
        verify(containers).start("nanofaas-echo-092c79e8f8-r1");
        var spec = org.mockito.ArgumentCaptor.forClass(ContainerSpec.class);
        verify(containers).create(spec.capture());
        assertThat(spec.getValue().command()).containsExactly("/app/echo", "--warm");
        assertThat(spec.getValue().environment()).containsEntry("FUNCTION_NAME", "echo");
        assertThat(spec.getValue().labels()).containsEntry("io.nanofaas.backend", "containerd");
        assertThat(spec.getValue().network()).isEqualTo("nanofaas");
        assertThat(spec.getValue().cpuShares()).isEqualTo(512);
        assertThat(spec.getValue().cpuPeriodMicros()).isEqualTo(100_000);
        assertThat(spec.getValue().cpuQuotaMicros()).isEqualTo(125_000);
        assertThat(spec.getValue().memoryReservationBytes()).isEqualTo(67_108_864);
        assertThat(spec.getValue().memoryLimitBytes()).isEqualTo(134_217_728);
        assertThat(spec.getValue().cpuSetCpus()).isEqualTo("0-3");
        assertThat(spec.getValue().cgroupsPath()).isEqualTo("user.slice:nanofaas:"
                + ContainerdDeploymentProvider.namePrefix(SOCKET + "\0nanofaas")
                + "-nanofaas-echo-092c79e8f8-r1");
    }

    @Test
    void runBracketsIpv6Address() {
        when(containers.networkAttachment("nanofaas-echo-092c79e8f8-r1")).thenReturn(
                new NetworkAttachment(List.of("fd00::2/64"), List.of(), List.of(), List.of(), null));
        assertThat(adapter.runContainer(instance(List.of())).baseUrl()).isEqualTo("http://[fd00::2]:8080");
    }

    @Test
    void systemdScopesAreUniquePerReplicaAndStableAfterRestart() {
        when(containers.networkAttachment(any())).thenReturn(
                new NetworkAttachment(List.of("10.90.0.2/24"), List.of(), List.of(), List.of(), null));
        String first = ContainerdDeploymentProvider.namePrefix("echo") + "-r1";
        String second = ContainerdDeploymentProvider.namePrefix("echo") + "-r2";
        String other = ContainerdDeploymentProvider.namePrefix("other") + "-r1";
        String scope = ContainerdDeploymentProvider.namePrefix(SOCKET + "\0nanofaas");
        for (String id : List.of(first, second, other)) {
            adapter.runContainer(new ContainerInstanceSpec(id, "echo:1", List.of(), Map.of(), null, Map.of()));
        }

        var specs = org.mockito.ArgumentCaptor.forClass(ContainerSpec.class);
        verify(containers, times(3)).create(specs.capture());
        assertThat(specs.getAllValues()).extracting(ContainerSpec::cgroupsPath).containsExactly(
                "user.slice:nanofaas:" + scope + "-" + first,
                "user.slice:nanofaas:" + scope + "-" + second,
                "user.slice:nanofaas:" + scope + "-" + other);

        clearInvocations(containers);
        new ContainerdRuntimeAdapter(client, "nanofaas", "0-3", "user.slice", true, SOCKET,
                Duration.ofSeconds(2))
                .runContainer(new ContainerInstanceSpec(first, "echo:1", List.of(), Map.of(), null, Map.of()));
        verify(containers).create(specs.capture());
        assertThat(specs.getValue().cgroupsPath()).isEqualTo("user.slice:nanofaas:" + scope + "-" + first);

        clearInvocations(containers);
        when(client.namespace()).thenReturn("other");
        new ContainerdRuntimeAdapter(client, "nanofaas", "0-3", "user.slice", true, SOCKET,
                Duration.ofSeconds(2))
                .runContainer(new ContainerInstanceSpec(first, "echo:1", List.of(), Map.of(), null, Map.of()));
        verify(containers).create(specs.capture());
        assertThat(specs.getValue().cgroupsPath()).isEqualTo("user.slice:nanofaas:"
                + ContainerdDeploymentProvider.namePrefix(SOCKET + "\0other") + "-" + first);

        clearInvocations(containers);
        when(client.namespace()).thenReturn("nanofaas");
        String otherSocket = "/run/user/1000/another-containerd.sock";
        new ContainerdRuntimeAdapter(client, "nanofaas", "0-3", "user.slice", true, otherSocket,
                Duration.ofSeconds(2))
                .runContainer(new ContainerInstanceSpec(first, "echo:1", List.of(), Map.of(), null, Map.of()));
        verify(containers).create(specs.capture());
        assertThat(specs.getValue().cgroupsPath()).isEqualTo("user.slice:nanofaas:"
                + ContainerdDeploymentProvider.namePrefix(otherSocket + "\0nanofaas") + "-" + first);
    }

    @Test
    void filesystemCgroupPathUsesConfiguredParentAndStableScopedLeaf() {
        String id = ContainerdDeploymentProvider.namePrefix("echo") + "-r1";
        when(containers.networkAttachment(id)).thenReturn(
                new NetworkAttachment(List.of("10.90.0.2/24"), List.of(), List.of(), List.of(), null));
        new ContainerdRuntimeAdapter(client, "nanofaas", null, "/delegated/functions", false, SOCKET,
                Duration.ofSeconds(2))
                .runContainer(new ContainerInstanceSpec(id, "echo:1", List.of(), Map.of(), null, Map.of()));
        var spec = org.mockito.ArgumentCaptor.forClass(ContainerSpec.class);
        verify(containers).create(spec.capture());
        assertThat(spec.getValue().cgroupsPath()).isEqualTo("/delegated/functions/"
                + ContainerdDeploymentProvider.namePrefix(SOCKET + "\0nanofaas") + "-" + id);
    }

    @Test
    void missingAddressFailsAndRemovesOwnedContainer() {
        when(containers.networkAttachment("nanofaas-echo-092c79e8f8-r1")).thenReturn(NetworkAttachment.EMPTY);
        var emptyInstance = instance(List.of());
        assertThatThrownBy(() -> adapter.runContainer(emptyInstance))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("IP address");
        verify(containers).remove(eq("nanofaas-echo-092c79e8f8-r1"), any());
    }

    @Test
    void emptyCommandLetsImageSupplyEntrypointAndCmd() {
        for (String image : List.of("java-word-stats", "python-word-stats", "go-word-stats",
                "javascript-word-stats", "watchdog")) {
            reset(containers);
            when(containers.create(any())).thenReturn(mock(io.nanofaas.containerd.Container.class));
            when(containers.networkAttachment("nanofaas-echo-092c79e8f8-r1")).thenReturn(
                    new NetworkAttachment(List.of("10.90.0.2/24"), List.of(), List.of(), List.of(), null));
            adapter.runContainer(new ContainerInstanceSpec("nanofaas-echo-092c79e8f8-r1", image,
                    List.of(), Map.of(), null, Map.of()));
            var spec = org.mockito.ArgumentCaptor.forClass(ContainerSpec.class);
            verify(containers).create(spec.capture());
            assertThat(spec.getValue().command()).as(image).isEmpty();
        }
    }

    @Test
    void pullDelegatesToContainerdImageService() {
        adapter.pullImage("localhost:5000/echo:v1");
        verify(images).pull("localhost:5000/echo:v1");
    }

    @Test
    void discoveryExcludesForeignAndPendingContainers() {
        Container owned = container("nanofaas-echo-092c79e8f8-r1", "echo", true);
        Container pending = container("nanofaas-echo-092c79e8f8-r2", "echo", true);
        Container foreign = container("nanofaas-echo-092c79e8f8-r3", "echo", false);
        when(containers.list()).thenReturn(List.of(owned, pending, foreign));
        when(containers.pendingRemovals()).thenReturn(List.of(pending));
        when(containers.inspect(owned.id())).thenReturn(
                new ContainerStatus(owned.id(), "echo:1", ContainerState.RUNNING, 123, null, "snapshot", Instant.now()));
        when(containers.networkAttachment(owned.id())).thenReturn(
                new NetworkAttachment(List.of("10.90.0.2/24"), List.of(), List.of(), List.of(), null));

        assertThat(adapter.listManagedContainers("echo"))
                .containsExactly(new ManagedContainer(owned.id(), 1, "http://10.90.0.2:8080", true),
                        new ManagedContainer(pending.id(), 2, null, false));
        verify(containers, never()).inspect(pending.id());
    }

    @Test
    void removeRejectsForeignContainerWithSameName() {
        when(containers.list()).thenReturn(List.of(container("nanofaas-echo-092c79e8f8-r1", "echo", false)));
        assertThatThrownBy(() -> adapter.removeContainer("nanofaas-echo-092c79e8f8-r1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("foreign container");
        verify(containers, never()).remove(any(), any());
    }

    @Test
    void removeRetriesPendingOwnedContainerEvenAfterDaemonMetadataIsGone() {
        when(containers.pendingRemovals()).thenReturn(List.of(container("nanofaas-echo-092c79e8f8-r1", "echo", true)));
        adapter.removeContainer("nanofaas-echo-092c79e8f8-r1");
        verify(containers).remove(eq("nanofaas-echo-092c79e8f8-r1"), any());
    }

    @Test
    void availabilityChecksClientRatherThanSocketPresence() {
        when(client.version()).thenThrow(new IllegalStateException("unavailable"));
        assertThat(adapter.isAvailable()).isFalse();
        reset(client);
        assertThat(adapter.isAvailable()).isTrue();
    }

    private static Container container(String id, String function, boolean owned) {
        return new Container(id, "echo:1", "native", id, Instant.EPOCH,
                Map.of("io.nanofaas.backend", owned ? "containerd" : "other",
                        "io.nanofaas.managed", "true", "io.nanofaas.function", function,
                        "io.nanofaas.replica", Integer.toString(ContainerdDeploymentProvider.replicaIndex(id))));
    }

    private static ContainerInstanceSpec instance(List<String> command) {
        return new ContainerInstanceSpec("nanofaas-echo-092c79e8f8-r1", "echo:1", command, Map.of(), null, Map.of());
    }
}
