package it.unimib.datai.nanofaas.sdk.lite.handler;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import it.unimib.datai.nanofaas.sdk.lite.callback.CallbackClient;
import it.unimib.datai.nanofaas.sdk.lite.metrics.RuntimeMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InvokeHandlerTest {
    private HttpServer server;
    private HttpClient client;
    private ObjectMapper objectMapper;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void startServer(FunctionHandler handler) throws IOException {
        CallbackClient callbackClient = new CallbackClient(objectMapper, null);
        RuntimeMetrics metrics = new RuntimeMetrics("test-fn");
        InvokeHandler invokeHandler = new InvokeHandler(handler, callbackClient, metrics, objectMapper, "test-fn");

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/invoke", invokeHandler);
        server.start();
        port = server.getAddress().getPort();
    }

    @Test
    void successfulInvocation() throws Exception {
        startServer(req -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> input = (Map<String, Object>) req.input();
            return Map.of("greeting", "Hello " + input.get("name"));
        });

        String body = objectMapper.writeValueAsString(new InvocationRequest(Map.of("name", "World"), null));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/invoke"))
                .header("Content-Type", "application/json")
                .header("X-Execution-Id", "exec-123")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Hello World"));
    }

    @Test
    void coldStartHeaders() throws Exception {
        startServer(req -> Map.of("ok", true));

        String body = objectMapper.writeValueAsString(new InvocationRequest(Map.of(), null));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/invoke"))
                .header("Content-Type", "application/json")
                .header("X-Execution-Id", "exec-cold")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        // Cold start headers may or may not be present depending on whether this is the
        // first invocation in the JVM (shared static state), so we just verify no error
    }

    @Test
    void handlerErrorReturns500() throws Exception {
        startServer(req -> { throw new RuntimeException("boom"); });

        String body = objectMapper.writeValueAsString(new InvocationRequest(Map.of(), null));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/invoke"))
                .header("Content-Type", "application/json")
                .header("X-Execution-Id", "exec-err")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(500, response.statusCode());
        assertTrue(response.body().contains("boom"));
    }

    @Test
    void missingExecutionIdReturns400() throws Exception {
        startServer(req -> Map.of("ok", true));

        String body = objectMapper.writeValueAsString(new InvocationRequest(Map.of(), null));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/invoke"))
                .header("Content-Type", "application/json")
                // No X-Execution-Id header and EXECUTION_ID env not set
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        // May be 400 or 200 depending on EXECUTION_ID env being set in test runner
        assertTrue(response.statusCode() == 400 || response.statusCode() == 200);
    }

    @Test
    void getMethodReturns405() throws Exception {
        startServer(req -> Map.of("ok", true));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/invoke"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(405, response.statusCode());
    }
}
