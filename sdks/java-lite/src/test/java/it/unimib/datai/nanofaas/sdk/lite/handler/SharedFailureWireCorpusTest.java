package it.unimib.datai.nanofaas.sdk.lite.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import it.unimib.datai.nanofaas.common.runtime.HandlerResponse;
import it.unimib.datai.nanofaas.sdk.lite.callback.CorpusCallbackClientFactory;
import it.unimib.datai.nanofaas.sdk.lite.metrics.RuntimeMetrics;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class SharedFailureWireCorpusTest {
    public static final class Unserializable {
        public String getValue() { throw new IllegalStateException("encoding fault"); }
    }
    static Path find(String relative) {
        for (Path root = Path.of("").toAbsolutePath(); root != null; root = root.getParent())
            if (Files.isRegularFile(root.resolve(relative))) return root.resolve(relative);
        throw new AssertionError("missing " + relative);
    }
    record Callback(JsonNode body, String attempt, String trace, String path) { }

    @ParameterizedTest
    @ValueSource(strings = {"envelope-serialization-failure", "callback-http-rejected", "ingress-io-timeout", "callback-io-timeout"})
    void executesSharedFailureLifecycle(String name) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode corpus = mapper.readTree(Files.readAllBytes(find("sdks/runtime-contract/failure-wire-corpus.json")));
        Process validator = new ProcessBuilder("python3", find("sdks/runtime-contract/validate_saturation_wire_corpus.py").toString(),
                find("sdks/runtime-contract/failure-wire-corpus.json").toString()).redirectErrorStream(true).start();
        assertTrue(validator.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, validator.exitValue(), new String(validator.getInputStream().readAllBytes()));
        JsonNode config = corpus.path("config"), expected = corpus.path("contractDefinitions").path(name);
        List<Callback> calls = new CopyOnWriteArrayList<>();
        CountDownLatch release = new CountDownLatch(1);
        HttpServer callbacks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService connections = Executors.newVirtualThreadPerTaskExecutor();
        callbacks.setExecutor(connections);
        callbacks.createContext("/", exchange -> {
            if (exchange.getRequestURI().getPath().equals("/warmup")) {
                exchange.sendResponseHeaders(204, -1); exchange.close(); return;
            }
            calls.add(new Callback(mapper.readTree(exchange.getRequestBody()), exchange.getRequestHeaders().getFirst("X-Dispatch-Attempt"),
                    exchange.getRequestHeaders().getFirst("X-Trace-Id"), exchange.getRequestURI().getPath()));
            if (name.equals("callback-io-timeout")) {
                try { release.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            }
            try { exchange.sendResponseHeaders(expected.path("callbackStatus").isNull() ? 204 : expected.path("callbackStatus").asInt(), -1); }
            finally { exchange.close(); }
        });
        callbacks.start();
        String callbackUrl = "http://127.0.0.1:" + callbacks.getAddress().getPort();
        ThreadPoolExecutor delivery = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2));
        AtomicBoolean started = new AtomicBoolean();
        RuntimeLimits limits = new RuntimeLimits(1, 2, 8192, 4096, 4096, 4096,
                config.path("bodyReadTimeoutMs").asInt(), config.path("callbackAttemptTimeoutMs").asInt(), config.path("callbackMaxAttempts").asInt(), config.path("deadlineMs").asInt());
        HttpServer ingress = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ingress.setExecutor(connections);
        try (HttpClient http = HttpClient.newHttpClient()) {
            http.send(HttpRequest.newBuilder(URI.create(callbackUrl + "/warmup")).build(), HttpResponse.BodyHandlers.discarding());
            var client = CorpusCallbackClientFactory.create(http, mapper, callbackUrl, config.path("callbackAttemptTimeoutMs").asLong(), config.path("callbackMaxAttempts").asInt());
            RuntimeMetrics metrics = new RuntimeMetrics("failure");
            InvokeHandler handler = new InvokeHandler(_ -> {
                started.set(true);
                return name.equals("envelope-serialization-failure") ? new HandlerResponse(new Unserializable(), 201, Map.of(), null) : corpus.path("successOutput");
            }, client, metrics, mapper, "failure", delivery, 1000, limits);
            ingress.createContext("/invoke", handler); ingress.start();
            try {
                int status; JsonNode body;
                if (name.equals("ingress-io-timeout")) {
                    new InvokeHandlerTcpBodyDeadlineTest().assertPartialTcpUpload(false, "partial", corpus);
                    return;
                } else {
                    var response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ingress.getAddress().getPort() + "/invoke"))
                            .timeout(Duration.ofMillis(config.path("deadlineMs").asLong())).header("Content-Type", "application/json")
                            .header("X-Execution-Id", config.path("executionId").asText()).header("X-Dispatch-Attempt", config.path("dispatchAttempt").asText())
                            .header("X-Trace-Id", config.path("traceId").asText()).POST(HttpRequest.BodyPublishers.ofString("{\"input\":null}")).build(), HttpResponse.BodyHandlers.ofString());
                    status = response.statusCode(); body = mapper.readTree(response.body());
                }
                assertEquals(expected.path("httpStatus").asInt(), status);
                assertEquals(expected.path("handlerStarted").asBoolean(), started.get());
                if (!expected.path("errorCode").isNull()) assertEquals((corpus.path("knownDifferences").path("java-lite").path(name).has("errorCode") ? corpus.path("knownDifferences").path("java-lite").path(name).path("errorCode") : expected.path("errorCode")).asText(), body.path("error").path("code").asText());
                delivery.shutdown(); assertTrue(delivery.awaitTermination(config.path("deadlineMs").asLong(), TimeUnit.MILLISECONDS));
                assertEquals(corpus.path("finalCounters").path("activeHandlers").asInt(), limits.activeHandlers());
                assertEquals(corpus.path("finalCounters").path("pendingCallbacks").asInt(), limits.pendingCallbacks());
                assertEquals(corpus.path("finalCounters").path("pendingCallbackBytes").asLong(), limits.pendingCallbackBytes());
                var scrape = new java.io.ByteArrayOutputStream();
                new io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter(true).write(scrape, metrics.getRegistry().scrape());
                double failures = expected.path("callbackAttempts").asInt() > 0 && !expected.path("callbackDelivered").asBoolean() ? 1.0 : 0.0;
                assertTrue(scrape.toString().contains("runtime_callback_failures_total{function=\"failure\"} " + failures), scrape.toString());
                assertEquals(expected.path("callbackAttempts").asInt(), calls.size());
                for (Callback call : calls) {
                    assertTrue(call.path().contains(config.path("executionId").asText()));
                    assertEquals(config.path("dispatchAttempt").asText(), call.attempt()); assertEquals(config.path("traceId").asText(), call.trace());
                    assertEquals(expected.path("errorCode").isNull(), call.body().path("success").asBoolean());
                    if (expected.path("errorCode").isNull()) assertEquals(mapper.valueToTree(corpus.path("successOutput")), call.body().path("output"));
                    else assertEquals(body.path("error"), call.body().path("error"));
                }
            } finally { handler.shutdown(Duration.ofMillis(config.path("deadlineMs").asLong())); }
        } finally { release.countDown(); ingress.stop(0); callbacks.stop(0); delivery.shutdownNow(); connections.close(); }
    }
}
