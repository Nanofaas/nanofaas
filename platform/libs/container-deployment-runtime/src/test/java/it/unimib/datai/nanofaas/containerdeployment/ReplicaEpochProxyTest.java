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

class ReplicaEpochProxyTest {
    @Test
    void earlyErrorCannotAuthorizeResumeUntilPhysicalProofArrives() throws Exception {
        var calls = new AtomicInteger();
        var released = new AtomicBoolean();
        var server = backend(calls, released, 504);
        try (var client = HttpClient.newHttpClient();
             var proxy = new RoundRobinFunctionProxy("127.0.0.1", 2, Duration.ofSeconds(2))) {
            proxy.enablePhysicalSlots();
            proxy.updateBackends(List.of("http://127.0.0.1:" + server.getAddress().getPort()));
            var initial = proxy.beginQuiesce();
            assertThat(proxy.resume(initial, initial.admissionEpoch())).isTrue();
            assertThat(invoke(client, proxy.endpointUrl(), "work", initial.admissionEpoch()).statusCode()).isEqualTo(504);
            var next = proxy.beginQuiesce();
            assertThat(proxy.awaitQuiesced(Duration.ofMillis(100))).isFalse();
            assertThat(proxy.resume(next, next.admissionEpoch())).isFalse();
            assertThat(invoke(client, proxy.endpointUrl(), "early", next.admissionEpoch()).statusCode()).isEqualTo(503);
            assertThat(calls.get()).isEqualTo(1);
            released.set(true);
            assertThat(proxy.awaitQuiesced(Duration.ofSeconds(2))).isTrue();
            assertThat(proxy.resume(next, next.admissionEpoch())).isTrue();
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer backend(AtomicInteger calls, AtomicBoolean released, int status) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/runtime/status", e -> reply(e, 200,
                "{\"schemaVersion\":1,\"incarnation\":\"warm-incarnation\",\"physicalReleaseProof\":true,\"maxConcurrentHandlers\":1,\"activeHandlers\":0}"));
        server.createContext("/invoke", e -> {
            calls.incrementAndGet();
            reply(e, status, "{\"result\":1}");
        });
        server.createContext("/runtime/executions/", e -> {
            String id = e.getRequestURI().getPath().substring("/runtime/executions/".length());
            reply(e, 200, "{\"state\":\"" + (released.get() ? "RELEASED" : "ACTIVE")
                    + "\",\"incarnation\":\"warm-incarnation\",\"executionId\":\"" + id
                    + "\",\"dispatchAttempt\":\"1\",\"occupancySeconds\":1,\"handlerStarted\":true}");
        });
        server.start();
        return server;
    }

    private static HttpResponse<String> invoke(HttpClient client, String url, String id, String epoch) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url))
                .header("X-Execution-Id", id).header("X-Dispatch-Attempt", "1");
        if (epoch != null) request.header("X-NanoFaaS-Replica-Epoch", epoch);
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
