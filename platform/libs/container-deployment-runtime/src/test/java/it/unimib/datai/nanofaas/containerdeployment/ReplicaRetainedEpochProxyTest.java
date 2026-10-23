package it.unimib.datai.nanofaas.containerdeployment;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ReplicaRetainedEpochProxyTest {
    @Test
    void retainedEpochCanUseFreeReplicaWhileOldTimeoutStillOwnsAnotherReplica() throws Exception {
        var calls = new AtomicInteger();
        var released = new AtomicBoolean();
        var first = backend(calls, released);
        var second = backend(calls, released);
        try (var client = HttpClient.newHttpClient();
             var proxy = new RoundRobinFunctionProxy("127.0.0.1", 2, Duration.ofSeconds(2))) {
            proxy.enablePhysicalSlots();
            proxy.updateBackends(List.of(url(first), url(second)));
            var initial = proxy.beginQuiesce();
            assertThat(proxy.resume(initial, initial.admissionEpoch())).isTrue();
            assertThat(invoke(client, proxy.endpointUrl(), "old", initial.admissionEpoch()).statusCode()).isEqualTo(504);
            var next = proxy.beginQuiesce();
            assertThat(proxy.awaitQuiesced(Duration.ofMillis(100))).isFalse();
            assertThat(proxy.resume(next, next.admissionEpoch())).isFalse();
            assertThat(proxy.resumeRetained(next, next.admissionEpoch())).isTrue();
            // Work accepted before rotation may use the free replica of the current pool.
            assertThat(invoke(client, proxy.endpointUrl(), "queued-before-rotation", initial.admissionEpoch()).statusCode()).isEqualTo(200);
            assertThat(calls.get()).isEqualTo(2);
            var retirement = proxy.beginQuiesce();
            assertThat(proxy.awaitQuiesced(Duration.ofMillis(100))).isFalse();
            assertThat(proxy.resume(retirement, retirement.admissionEpoch())).isFalse();
            released.set(true);
            assertThat(proxy.awaitQuiesced(Duration.ofSeconds(2))).isTrue();
            assertThat(proxy.resume(retirement, retirement.admissionEpoch())).isTrue();
        } finally {
            first.stop(0);
            second.stop(0);
        }
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static HttpServer backend(AtomicInteger calls, AtomicBoolean released) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/runtime/status", e -> reply(e, 200,
                "{\"schemaVersion\":1,\"incarnation\":\"warm\",\"physicalReleaseProof\":true,\"maxConcurrentHandlers\":1,\"activeHandlers\":0}"));
        server.createContext("/invoke", e -> {
            calls.incrementAndGet();
            reply(e, "old".equals(e.getRequestHeaders().getFirst("X-Execution-Id")) ? 504 : 200, "{\"result\":1}");
        });
        server.createContext("/runtime/executions/", e -> {
            String id = e.getRequestURI().getPath().substring("/runtime/executions/".length());
            String state = "old".equals(id) && !released.get() ? "ACTIVE" : "RELEASED";
            reply(e, 200, "{\"state\":\"" + state + "\",\"incarnation\":\"warm\",\"executionId\":\"" + id
                    + "\",\"dispatchAttempt\":\"1\",\"occupancySeconds\":1,\"handlerStarted\":true}");
        });
        server.start();
        return server;
    }

    private static HttpResponse<String> invoke(HttpClient client, String url, String id, String epoch) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).header("X-Execution-Id", id)
                .header("X-Dispatch-Attempt", "1").header("X-NanoFaaS-Replica-Epoch", epoch);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString("{\"input\":null}")).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void reply(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
