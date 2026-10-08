package it.unimib.datai.nanofaas.containerdeployment;

import com.sun.net.httpserver.HttpServer;
import it.unimib.datai.nanofaas.common.model.*;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PartialScalingHttpTest {
    @Test
    void partialScaleDownRefreshesProxy() throws Exception {
        var servers = new ArrayList<HttpServer>();
        var adapter = mock(ContainerRuntimeAdapter.class);
        when(adapter.runContainer(any())).thenAnswer(invocation -> {
            ContainerInstanceSpec instance = invocation.getArgument(0);
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/invoke", exchange -> {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write(new byte[]{'o', 'k'});
                exchange.close();
            });
            server.start(); servers.add(server);
            return new ManagedContainer(instance.containerName(), servers.size(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), true);
        });
        doAnswer(invocation -> {
            String name = invocation.getArgument(0);
            if (name.endsWith("-r2")) throw new IllegalStateException("remove failed");
            servers.get(2).stop(0); return null;
        }).when(adapter).removeContainer(anyString());
        try (var provider = provider(adapter, new RoundRobinFunctionProxyFactory("127.0.0.1"));
             var client = HttpClient.newHttpClient()) {
            String endpoint = provider.provision(spec(3)).endpointUrl();
            assertThatThrownBy(() -> provider.setReplicas("echo", 1)).hasMessage("remove failed");
            assertThat(provider.getReplicaStatus("echo").readyReplicas()).isEqualTo(2);
            for (int i = 0; i < 6; i++) {
                var request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(2))
                        .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
                assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            }
            doNothing().when(adapter).removeContainer(anyString());
            provider.setReplicas("echo", 1);
            assertThat(provider.getReplicaStatus("echo").readyReplicas()).isEqualTo(1);
        } finally { servers.forEach(server -> server.stop(0)); }
    }

    @Test
    void partialScaleUpPublishesCreatedReplicaAndRetryCompletes() {
        var adapter = mock(ContainerRuntimeAdapter.class);
        var proxy = mock(ManagedFunctionProxy.class);
        when(adapter.runContainer(any())).thenAnswer(invocation -> {
            ContainerInstanceSpec instance = invocation.getArgument(0);
            if (instance.containerName().endsWith("-r3")) throw new IllegalStateException("create failed");
            return new ManagedContainer(instance.containerName(), 1, instance.containerName(), true);
        });
        try (var provider = provider(adapter, factory(proxy))) {
            provider.provision(spec(1));
            assertThatThrownBy(() -> provider.setReplicas("echo", 3)).hasMessage("create failed");
            verify(proxy).updateBackends(List.of("nanofaas-echo-r1", "nanofaas-echo-r2"));
            doReturn(new ManagedContainer("nanofaas-echo-r3", 3, "nanofaas-echo-r3", true)).when(adapter).runContainer(any());
            provider.setReplicas("echo", 3);
            verify(proxy).updateBackends(List.of("nanofaas-echo-r1", "nanofaas-echo-r2", "nanofaas-echo-r3"));
        }
    }

    @Test
    void occupiedOneShotReplicaRemainsTrackedAndDrainingAfterRefresh() {
        var slots = new ReplicaSlots();
        var adapter = mock(ContainerRuntimeAdapter.class);
        var proxy = mock(ManagedFunctionProxy.class);
        String endpoint = "http://r1";
        when(adapter.runContainer(any())).thenReturn(new ManagedContainer("r1", 1, endpoint, true));
        doAnswer(invocation -> {
            List<String> backends = invocation.getArgument(0);
            var incarnations = new LinkedHashMap<String, String>();
            backends.forEach(url -> incarnations.put(url, "incarnation"));
            slots.update(incarnations); return null;
        }).when(proxy).updateBackends(anyList());
        doAnswer(invocation -> { slots.beginDrain(invocation.getArgument(0)); return null; })
                .when(proxy).beginDrain(anyString());
        when(proxy.awaitDrained(anyString(), any())).thenAnswer(invocation -> slots.drained(invocation.getArgument(0)));
        when(proxy.backendReady(anyString())).thenAnswer(invocation -> slots.routable(invocation.getArgument(0)));
        var base = spec(1);
        var oneShot = new FunctionSpec(base.name(), base.image(), base.command(),
                Map.of("NANOFAAS_ONE_SHOT_PROFILE", "true", "NANOFAAS_MAX_CONCURRENT_HANDLERS", "1"),
                base.resources(), base.timeoutMs(), base.concurrency(), base.queueSize(), base.maxRetries(), null,
                base.executionMode(), base.runtimeMode(), null, base.scalingConfig());
        try (var provider = provider(adapter, factory(proxy))) {
            provider.provision(oneShot);
            var lease = slots.tryAcquire("running").orElseThrow();
            assertThatThrownBy(() -> provider.setReplicas("echo", 0)).hasMessageContaining("physically occupied");
            assertThat(provider.getReplicaStatus("echo").readyReplicas()).isZero();
            assertThat(slots.occupied()).containsExactly(lease);
            verify(adapter, never()).removeContainer(anyString());
            verify(proxy, times(2)).updateBackends(List.of(endpoint));
            assertThat(slots.tryAcquire("another")).isEmpty();
            assertThat(lease.markReleased(new ExecutionObservation("RELEASED", "incarnation", "running", null, false))).isTrue();
            provider.setReplicas("echo", 0);
            verify(adapter).removeContainer("nanofaas-echo-r1");
        }
    }

    @Test
    void firstRemovalFailurePublishesSurvivorsAndSuppressesPublicationFailure() {
        var adapter = mock(ContainerRuntimeAdapter.class);
        var proxy = mock(ManagedFunctionProxy.class);
        when(adapter.runContainer(any())).thenReturn(new ManagedContainer("r1", 1, "http://r1", true));
        try (var provider = provider(adapter, factory(proxy))) {
            provider.provision(spec(1));
            clearInvocations(proxy);
            doThrow(new IllegalStateException("remove failed")).when(adapter).removeContainer(anyString());
            doThrow(new IllegalStateException("publish failed")).when(proxy).updateBackends(anyList());
            var failure = catchThrowable(() -> provider.setReplicas("echo", 0));
            assertThat(failure).hasMessage("remove failed");
            assertThat(failure.getSuppressed()).extracting(Throwable::getMessage).containsExactly("publish failed");
            verify(proxy).updateBackends(List.of("http://r1"));
            assertThat(provider.getReplicaStatus("echo").readyReplicas()).isEqualTo(1);
        }
    }

    private static LocalManagedDeploymentProvider provider(ContainerRuntimeAdapter adapter, RoundRobinFunctionProxyFactory factory) {
        var probe = mock(EndpointProbe.class);
        when(probe.isReady(anyString())).thenReturn(true);
        return new LocalManagedDeploymentProvider("test", new LocalDeploymentSettings(null,
                Duration.ofSeconds(1), Duration.ofMillis(10)), adapter, probe, factory);
    }
    private static RoundRobinFunctionProxyFactory factory(ManagedFunctionProxy proxy) {
        var factory = mock(RoundRobinFunctionProxyFactory.class);
        when(factory.create(anyString())).thenReturn(proxy); return factory;
    }
    private static FunctionSpec spec(int min) {
        return new FunctionSpec("echo", "img", List.of(), Map.of(), null, 1000, 4, 100, 3, null,
                ExecutionMode.DEPLOYMENT, RuntimeMode.HTTP, null,
                new ScalingConfig(ScalingStrategy.INTERNAL, min, 5, List.of()));
    }
}
