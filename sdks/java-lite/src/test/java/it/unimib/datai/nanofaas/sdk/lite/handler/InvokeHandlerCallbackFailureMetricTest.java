package it.unimib.datai.nanofaas.sdk.lite.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter;
import it.unimib.datai.nanofaas.sdk.lite.callback.CallbackClient;
import it.unimib.datai.nanofaas.sdk.lite.metrics.RuntimeMetrics;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvokeHandlerCallbackFailureMetricTest {
    @Test
    void exhaustedCallbackIncrementsBoundedFailureMetricOnce() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        HttpServer callback = HttpServer.create(new InetSocketAddress(0), 0);
        callback.createContext("/", exchange -> {
            attempts.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        callback.start();
        ObjectMapper mapper = new ObjectMapper();
        RuntimeMetrics metrics = new RuntimeMetrics("failure-metric");
        RuntimeLimits limits = new RuntimeLimits(1, 1, 2_048, 1_024, 1_024, 1_024,
                500, 50, 3, 100);
        ThreadPoolExecutor callbacks = new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
        CallbackClient client = new CallbackClient(mapper,
                "http://127.0.0.1:" + callback.getAddress().getPort());
        InvokeHandler handler = new InvokeHandler(_ -> Map.of("result", "ok"), client, metrics, mapper,
                "failure-metric", callbacks, 1_000, limits);
        HttpServer invoke = HttpServer.create(new InetSocketAddress(0), 0);
        invoke.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        invoke.createContext("/invoke", handler);
        invoke.start();
        try { // NOSONAR (java:S2093): HttpServer is not AutoCloseable; teardown order matters
            HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + invoke.getAddress().getPort() + "/invoke"))
                    .header("X-Execution-Id", "execution").POST(HttpRequest.BodyPublishers.ofString("{\"input\":{}}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            org.awaitility.Awaitility.await().pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(5))
                    .atMost(Duration.ofSeconds(3)).until(() -> limits.pendingCallbacks() == 0);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            new PrometheusTextFormatWriter(true).write(output, metrics.getRegistry().scrape());
            assertEquals(3, attempts.get());
            assertTrue(output.toString().contains(
                    "runtime_callback_failures_total{function=\"failure-metric\"} 1.0"), output.toString());
        } finally {
            invoke.stop(0);
            handler.shutdown(java.time.Duration.ofMillis(100));
            client.close(java.time.Duration.ofMillis(100));
            callbacks.shutdownNow();
            callback.stop(0);
        }
    }
}
