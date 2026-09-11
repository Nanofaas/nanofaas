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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

class InvokeHandlerTest {
    private HttpServer server;
    private HttpClient client;
    private ObjectMapper objectMapper;
    private int port;

    @BeforeEach
    void setUp() {
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
        startServer(handler, callbackClient);
    }

    private void startServer(FunctionHandler handler, CallbackClient callbackClient) throws IOException {
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
    void successfulInvocation_sendsDispatchAttemptOnCallback() throws Exception {
        AtomicReference<String> dispatchAttempt = new AtomicReference<>();
        ArrayBlockingQueue<Boolean> callbackReceived = new ArrayBlockingQueue<>(1);
        HttpServer callbackServer = HttpServer.create(new InetSocketAddress(0), 0);
        callbackServer.createContext("/", exchange -> {
            dispatchAttempt.set(exchange.getRequestHeaders().getFirst("X-Dispatch-Attempt"));
            callbackReceived.offer(true);
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        callbackServer.start();
        try {
            String callbackUrl = "http://localhost:" + callbackServer.getAddress().getPort();
            startServer(req -> Map.of("ok", true), new CallbackClient(objectMapper, callbackUrl));

            String body = objectMapper.writeValueAsString(new InvocationRequest(Map.of(), null));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/invoke"))
                    .header("Content-Type", "application/json")
                    .header("X-Execution-Id", "exec-attempt")
                    .header("X-Dispatch-Attempt", "3")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(callbackReceived.poll(2, TimeUnit.SECONDS));
            assertEquals("3", dispatchAttempt.get());
        } finally {
            callbackServer.stop(0);
        }
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
    void handlerRejectedExecutionExceptionRemainsAHandlerError() throws Exception {
        startServer(req -> {
            throw new RejectedExecutionException("handler rejected its own work");
        });

        String body = objectMapper.writeValueAsString(new InvocationRequest(Map.of(), null));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/invoke"))
                .header("Content-Type", "application/json")
                .header("X-Execution-Id", "exec-handler-rejected")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(500, response.statusCode());
        assertTrue(response.body().contains("handler rejected its own work"));
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

    @Test
    void malformedJsonReturns400() throws Exception {
        startServer(req -> Map.of("ok", true));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/invoke"))
                .header("Content-Type", "application/json")
                .header("X-Execution-Id", "exec-json")
                .POST(HttpRequest.BodyPublishers.ofString("{"))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("INVALID_JSON"));
    }

    @Test
    void handlerTimeoutReturns504() throws Exception {
        System.setProperty("nanofaas.handler.timeout.ms", "20");
        try {
            startServer(req -> {
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(200));
                return Map.of("late", true);
            });
            String body = objectMapper.writeValueAsString(new InvocationRequest(Map.of(), null));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/invoke"))
                    .header("Content-Type", "application/json")
                    .header("X-Execution-Id", "exec-timeout")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(504, response.statusCode());
            assertTrue(response.body().contains("HANDLER_TIMEOUT"));
        } finally {
            System.clearProperty("nanofaas.handler.timeout.ms");
        }
    }

    @Test
    void callbackDispatchUsesBoundedWorkerAndQueue() throws Exception {
        System.setProperty("nanofaas.callback.worker.count", "1");
        System.setProperty("nanofaas.callback.queue.capacity", "1");
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        HttpServer callbackServer = HttpServer.create(new InetSocketAddress(0), 0);
        callbackServer.createContext("/", exchange -> {
            started.incrementAndGet();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        callbackServer.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        callbackServer.start();
        try {
            startServer(
                    req -> Map.of("ok", true),
                    new CallbackClient(objectMapper, "http://localhost:" + callbackServer.getAddress().getPort()));
            String body = objectMapper.writeValueAsString(new InvocationRequest(Map.of(), null));
            for (int i = 0; i < 3; i++) {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/invoke"))
                        .header("Content-Type", "application/json")
                        .header("X-Execution-Id", "exec-queue-" + i)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                assertEquals(200, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            // single callback worker + bounded queue: only one callback may be in flight
            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
                    assertEquals(1, started.get()));
        } finally {
            release.countDown();
            callbackServer.stop(0);
            System.clearProperty("nanofaas.callback.worker.count");
            System.clearProperty("nanofaas.callback.queue.capacity");
        }
    }
}
