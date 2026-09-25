package it.unimib.datai.nanofaas.sdk.lite.handler;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import it.unimib.datai.nanofaas.common.runtime.HandlerResponse;
import it.unimib.datai.nanofaas.sdk.lite.callback.CallbackClient;
import it.unimib.datai.nanofaas.sdk.lite.metrics.RuntimeMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A HandlerResponse is the function deciding its own status, the same contract the Java SDK
 * honours. Without it every lite function answered 200, so json-transform-lite and
 * roman-numeral-lite could not return the 400/422 the shared correctness corpus requires.
 */
class InvokeHandlerEnvelopeTest {
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final HttpClient client = HttpClient.newHttpClient();
    private final ArrayBlockingQueue<JsonNode> callbacks = new ArrayBlockingQueue<>(4);
    private HttpServer server;
    private HttpServer callbackServer;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        if (callbackServer != null) callbackServer.stop(0);
    }

    private int start(FunctionHandler handler) throws IOException {
        callbackServer = HttpServer.create(new InetSocketAddress(0), 0);
        callbackServer.createContext("/", exchange -> {
            try (InputStream body = exchange.getRequestBody()) {
                callbacks.offer(objectMapper.readTree(body));
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        callbackServer.start();
        CallbackClient callbackClient = new CallbackClient(objectMapper,
                "http://localhost:" + callbackServer.getAddress().getPort());
        InvokeHandler invokeHandler = new InvokeHandler(handler, callbackClient,
                new RuntimeMetrics("test-fn"), objectMapper, "test-fn");
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/invoke", invokeHandler);
        server.start();
        return server.getAddress().getPort();
    }

    private HttpResponse<String> invoke(int port) throws Exception {
        String body = objectMapper.writeValueAsString(new InvocationRequest(Map.of(), null));
        return client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/invoke"))
                .header("Content-Type", "application/json")
                .header("X-Execution-Id", "exec-envelope")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void envelopeSetsStatusAllowedHeadersAndCallbackEnvelope() throws Exception {
        int port = start(req -> HandlerResponse.of(Map.of("error", "missing required field: number"), 422,
                Map.of("Cache-Control", "no-store", "X-Internal", "dropped")));

        HttpResponse<String> response = invoke(port);

        assertEquals(422, response.statusCode());
        assertEquals(Map.of("error", "missing required field: number"),
                objectMapper.readValue(response.body(), Map.class));
        assertEquals("true", response.headers().firstValue("X-NanoFaaS-Function-Status").orElse(null));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null));
        assertTrue(response.headers().firstValue("X-Internal").isEmpty(), "headers outside the allow-list are dropped");

        JsonNode callback = callbacks.poll(5, TimeUnit.SECONDS);
        assertNotNull(callback, "callback");
        assertTrue(callback.get("success").asBoolean());
        assertEquals(422, callback.get("statusCode").asInt());
        assertEquals("missing required field: number", callback.get("output").get("error").asText());
    }

    @Test
    void plainValueStaysA200WithoutEnvelopeFields() throws Exception {
        int port = start(req -> Map.of("roman", "IV"));

        HttpResponse<String> response = invoke(port);

        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("X-NanoFaaS-Function-Status").isEmpty());
        JsonNode callback = callbacks.poll(5, TimeUnit.SECONDS);
        assertNotNull(callback, "callback");
        assertNull(callback.get("statusCode"), "a plain value carries no envelope status");
    }

    @Test
    void outOfRangeStatusIsAPlatformError() throws Exception {
        int port = start(req -> HandlerResponse.of(Map.of(), 99));

        HttpResponse<String> response = invoke(port);

        assertEquals(500, response.statusCode());
        assertEquals("OUTPUT_SERIALIZATION_ERROR",
                objectMapper.readTree(response.body()).get("error").get("code").asText());
        JsonNode callback = callbacks.poll(5, TimeUnit.SECONDS);
        assertNotNull(callback, "callback");
        assertEquals(false, callback.get("success").asBoolean());
    }

    /**
     * The JVM needs no reflection metadata, so the tests above pass even when a native image
     * cannot serialize the callback. CallbackPayload was missing from this file and every native
     * lite function answered OUTPUT_SERIALIZATION_ERROR.
     */
    @Test
    void nativeReflectionConfigCoversEveryWireType() throws Exception {
        try (InputStream config = getClass().getResourceAsStream(
                "/META-INF/native-image/it.unimib.datai.nanofaas/function-sdk-java-lite/reflect-config.json")) {
            assertNotNull(config, "reflect-config.json");
            String names = objectMapper.readTree(config).findValuesAsText("name").toString();
            for (String type : new String[]{
                    "it.unimib.datai.nanofaas.common.model.InvocationRequest",
                    "it.unimib.datai.nanofaas.common.model.InvocationResult",
                    "it.unimib.datai.nanofaas.common.model.ErrorInfo",
                    "it.unimib.datai.nanofaas.sdk.lite.callback.CallbackPayload"}) {
                assertTrue(names.contains(type), type + " must be registered for native serialization");
            }
        }
    }
}
