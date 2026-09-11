package it.unimib.datai.nanofaas.sdk.lite.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import it.unimib.datai.nanofaas.sdk.lite.callback.CallbackClient;
import it.unimib.datai.nanofaas.sdk.lite.metrics.RuntimeMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** A focused RED reproducer: compare the unchanged corpus with real wire results. */
class SaturationCorpusRuntimeMismatchTest {
    @Test
    void dispatchRetryIdentityMatchesRealResponsesAndCallbacks() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(
                "sdks/runtime-contract/saturation-wire-corpus.json"))) root = root.getParent();
        assertNotNull(root, "repository root");
        Path corpusPath = root.resolve("sdks/runtime-contract/saturation-wire-corpus.json");
        Process validator = new ProcessBuilder("python3", root.resolve(
                "sdks/runtime-contract/validate_saturation_wire_corpus.py").toString(),
                corpusPath.toString()).redirectErrorStream(true).start();
        assertTrue(validator.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, validator.exitValue(), new String(validator.getInputStream().readAllBytes()));
        JsonNode corpus = new ObjectMapper().readTree(Files.readAllBytes(corpusPath));
        JsonNode scenario = null;
        for (JsonNode row : corpus.path("scenarios")) {
            if (row.path("id").asText().equals("dispatch-retry-identity")) scenario = row;
        }
        assertNotNull(scenario);
        executeScenario(scenario, corpus.path("runtimeConfigurations")
                .path(scenario.path("runtimeConfigRef").asText()));
    }

    private static void executeScenario(JsonNode scenario, JsonNode config) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var callbackRequests = new LinkedBlockingQueue<JsonNode>();
        var starts = new AtomicInteger();
        var requests = Executors.newVirtualThreadPerTaskExecutor();
        var callbacks = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1));
        HttpServer backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", exchange -> {
            var observed = mapper.createObjectNode();
            observed.put("method", exchange.getRequestMethod());
            // Only the loopback authority is mapped back to the corpus's logical backend.
            observed.put("url", "http://callback.invalid" + exchange.getRequestURI());
            var headers = observed.putObject("headers");
            for (String name : List.of("content-type", "x-trace-id", "x-dispatch-attempt")) {
                headers.put(name, exchange.getRequestHeaders().getFirst(name));
            }
            observed.set("payload", mapper.readTree(exchange.getRequestBody()));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            callbackRequests.add(observed);
        });
        backend.start();
        CallbackClient callbackClient = new CallbackClient(mapper,
                "http://127.0.0.1:" + backend.getAddress().getPort());
        RuntimeLimits limits = new RuntimeLimits(config.path("maxConcurrentHandlers").asInt(),
                config.path("maxPendingCallbacks").asInt(), config.path("maxPendingCallbackBytes").asLong(),
                config.path("maxInputBytes").asInt(), config.path("maxOutputBytes").asInt(),
                config.path("maxPendingCallbackBytes").asInt());
        InvokeHandler handler = new InvokeHandler(_ -> {
            JsonNode behavior = scenario.path("backend").path("handlers").path(starts.getAndIncrement());
            return switch (behavior.path("behavior").asText()) {
                case "fail" -> throw new IllegalStateException("Handler failed");
                case "succeed" -> Map.of("result", "ok");
                default -> throw new AssertionError("unsupported backend behavior: " + behavior);
            };
        }, callbackClient, new RuntimeMetrics("corpus-mismatch"), mapper, "corpus-mismatch",
                callbacks, config.path("handlerTimeoutMs").asLong(), limits);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(requests);
        server.createContext("/invoke", handler);
        server.start();
        List<Executable> comparisons = new ArrayList<>();
        try (HttpClient client = HttpClient.newHttpClient()) {
            int index = 0;
            for (JsonNode request : scenario.path("requests")) {
                JsonNode metadata = request.path("metadata");
                var wire = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                                + server.getAddress().getPort() + request.path("path").asText()))
                        .header("Content-Type", "application/json")
                        .header("X-Execution-Id", metadata.path("executionId").asText())
                        .header("X-Trace-Id", metadata.path("traceId").asText())
                        .header("X-Dispatch-Attempt", metadata.path("dispatchAttempt").asText())
                        .POST(HttpRequest.BodyPublishers.ofString("{\"input\":{}}"))
                        .timeout(Duration.ofSeconds(3)).build();
                HttpResponse<String> response = client.send(wire, HttpResponse.BodyHandlers.ofString());
                JsonNode callback = callbackRequests.poll(3, TimeUnit.SECONDS);
                assertNotNull(callback, "actual callback for " + request.path("id").asText());
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3))
                        .until(() -> limits.pendingCallbacks() == 0);
                JsonNode expectedResponse = scenario.path("expected").path("responses").path(index);
                JsonNode expectedCallback = scenario.path("expected").path("callbacks").path(index++);
                JsonNode body = mapper.readTree(response.body());
                comparisons.add(() -> assertEquals(expectedResponse.path("status").asInt(), response.statusCode()));
                comparisons.add(() -> assertEquals(expectedResponse.path("body"), body,
                        request.path("id").asText() + " actual HTTP response body"));
                comparisons.add(() -> assertEquals(expectedCallback.path("requestProjection"), callback,
                        request.path("id").asText() + " actual callback projection"));
            }
            assertAll(comparisons);
        } finally {
            handler.beginStop();
            handler.shutdown(Duration.ofSeconds(1));
            server.stop(0);
            callbacks.shutdownNow();
            callbacks.awaitTermination(3, TimeUnit.SECONDS);
            callbackClient.close(Duration.ofSeconds(1));
            backend.stop(0);
            requests.shutdownNow();
            requests.awaitTermination(3, TimeUnit.SECONDS);
        }
    }
}
