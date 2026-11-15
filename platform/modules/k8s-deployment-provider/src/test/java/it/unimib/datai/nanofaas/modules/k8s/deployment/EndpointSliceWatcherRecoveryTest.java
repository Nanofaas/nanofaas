package it.unimib.datai.nanofaas.modules.k8s.deployment;

import io.fabric8.kubernetes.api.model.discovery.v1.EndpointBuilder;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSliceBuilder;
import io.fabric8.kubernetes.client.server.mock.KubernetesCrudDispatcher;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.fabric8.mockwebserver.Context;
import io.fabric8.mockwebserver.MockWebServer;
import io.fabric8.mockwebserver.http.Dispatcher;
import io.fabric8.mockwebserver.http.MockResponse;
import io.fabric8.mockwebserver.http.RecordedRequest;
import io.fabric8.mockwebserver.http.Response;
import io.fabric8.mockwebserver.http.WebSocket;
import io.fabric8.mockwebserver.http.WebSocketListener;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointSliceWatcherRecoveryTest {

    /**
     * A started informer whose watch is closed by the server and then fails in the stream must keep
     * following the slices: a frozen store would keep dispatch on old pod IPs. Fabric8's watch
     * manager reopens the watch after a relist; this pins that it still does.
     */
    @Test
    void aWatchClosedAndFailingAfterStartIsRecovered() {
        // The first watch opens (the informer is started) and the server closes it, as an API server
        // rotating a watch does; the next watch reports an ERROR event with a 403 Status in the
        // stream, as the API server does for a failure on an open watch; later watches are served
        // normally.
        var crud = new KubernetesCrudDispatcher();
        var watches = new AtomicInteger();
        var refused = new AtomicBoolean();
        var dispatcher = new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath();
                if (!path.contains("/endpointslices") || !path.contains("watch=true")) return crud.dispatch(request);
                int watch = watches.incrementAndGet();
                if (watch == 1) {
                    return new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                        @Override
                        public void onOpen(WebSocket webSocket, Response response) {
                            Thread.ofVirtual().start(() -> {
                                try { Thread.sleep(500); } catch (InterruptedException ignored) { return; }
                                webSocket.close(1000, "rotate");
                            });
                        }
                    });
                }
                if (watch == 2) {
                    refused.set(true);
                    return new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                        @Override
                        public void onOpen(WebSocket webSocket, Response response) {
                            webSocket.send("{\"type\":\"ERROR\",\"object\":{\"kind\":\"Status\",\"apiVersion\":\"v1\","
                                    + "\"status\":\"Failure\",\"reason\":\"Forbidden\",\"code\":403}}");
                            // The API server ends a watch after reporting an error on it.
                            webSocket.close(1000, "error reported");
                        }
                    });
                }
                return crud.dispatch(request);
            }
        };
        var server = new KubernetesMockServer(new Context(), new MockWebServer(), new HashMap<>(), dispatcher, false);
        server.init();
        var client = server.createClient();
        try {
            var watcher = new EndpointSliceWatcher(client, "default", ignored -> { });
            watcher.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!refused.get() && System.nanoTime() < deadline) Thread.onSpinWait();
            assertThat(refused).as("an ERROR event reached the watch").isTrue();

            // Created only after the error: it reaches the store only if the informer kept going.
            client.discovery().v1().endpointSlices().inNamespace("default").resource(new EndpointSliceBuilder()
                    .withNewMetadata().withName("fn-echo-a").addToLabels("kubernetes.io/service-name", "fn-echo").endMetadata()
                    .withAddressType("IPv4")
                    .withEndpoints(new EndpointBuilder().withAddresses("10.0.0.1")
                            .withNewConditions().withReady(true).endConditions().build())
                    .build()).create();

            while (watcher.readyBackends("fn-echo").isEmpty() && System.nanoTime() < deadline) Thread.onSpinWait();
            assertThat(watcher.readyBackends("fn-echo")).isEqualTo(List.of("http://10.0.0.1:8080"));
        } finally {
            // Server first: closing the watch from the client side races the mock server (see EndpointSliceWatcherTest).
            server.destroy();
            client.close();
        }
    }
}
