package it.unimib.datai.nanofaas.sdk.lite;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

class NanofaasRuntimeTest {

    @Test
    void startAndStop() throws Exception {
        NanofaasRuntime runtime = NanofaasRuntime.builder()
                .handler(req -> Map.of("echo", req.input()))
                .port(0) // random port - but HttpServer doesn't support port 0
                .functionName("test")
                .port(18080)
                .build();

        // Start in background thread (start() blocks)
        Thread t = new Thread(runtime::start);
        t.setDaemon(true);
        t.start();

        HttpClient client = HttpClient.newHttpClient();

        // Wait for server to be ready by polling the health endpoint
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            HttpResponse<String> healthResp = client.send(
                    HttpRequest.newBuilder().uri(URI.create("http://localhost:18080/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, healthResp.statusCode());
            assertTrue(healthResp.body().contains("ok"));
        });

        // Test metrics
        HttpResponse<String> metricsResp = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:18080/metrics")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, metricsResp.statusCode());
        assertTrue(metricsResp.body().contains("nanofaas_invocations_total"));

        runtime.stop();
    }

    @Test
    void builderRequiresHandler() {
        NanofaasRuntime.Builder builder = NanofaasRuntime.builder();
        assertThrows(IllegalStateException.class, () -> builder.build());
    }
}
