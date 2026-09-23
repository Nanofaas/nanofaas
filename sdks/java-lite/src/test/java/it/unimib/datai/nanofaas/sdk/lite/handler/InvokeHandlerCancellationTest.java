package it.unimib.datai.nanofaas.sdk.lite.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import com.sun.net.httpserver.HttpServer;
import it.unimib.datai.nanofaas.sdk.lite.callback.CallbackClient;
import it.unimib.datai.nanofaas.sdk.lite.metrics.RuntimeMetrics;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvokeHandlerCancellationTest {
    @Test
    void requestCancellationEmitsCanonicalCallbackAndRetainsPhysicalHandlerOwnership() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        CountDownLatch callbackReceived = new CountDownLatch(1);
        AtomicReference<JsonNode> callbackBody = new AtomicReference<>();
        HttpServer callback = HttpServer.create(new InetSocketAddress(0), 0);
        callback.createContext("/", exchange -> {
            callbackBody.set(mapper.readTree(exchange.getRequestBody()));
            callbackReceived.countDown();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        callback.start();
        RuntimeLimits limits = new RuntimeLimits(1, 1, 2_048, 1_024, 1_024, 1_024,
                500, 100, 3, 100);
        ThreadPoolExecutor callbacks = new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
        CallbackClient client = new CallbackClient(mapper,
                "http://127.0.0.1:" + callback.getAddress().getPort());
        InvokeHandler handler = new InvokeHandler(_ -> {
            handlerEntered.countDown();
            boolean released = false;
            while (!released) {
                try { released = releaseHandler.await(20, TimeUnit.MILLISECONDS); }
                catch (InterruptedException _) { }
            }
            return java.util.Map.of("result", "late");
        }, client, new RuntimeMetrics("cancel"), mapper, "cancel", callbacks, 2_000, limits);
        MemoryExchange exchange = new MemoryExchange();
        Thread request = Thread.ofPlatform().start(() -> {
            try { handler.handle(exchange); } catch (Exception _) { }
        });
        try {
            assertTrue(handlerEntered.await(1, TimeUnit.SECONDS));
            request.interrupt();
            assertTrue(callbackReceived.await(1, TimeUnit.SECONDS));

            assertEquals("INVOCATION_CANCELLED",
                    callbackBody.get().path("error").path("code").asText());
            assertEquals(1, limits.activeHandlers(), "physical handler still owns its reservation");
            assertEquals(0, exchange.status, "a disconnected invocation has no success/error response");

            releaseHandler.countDown();
            request.join(1_000);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (limits.activeHandlers() != 0 && System.nanoTime() < deadline) Thread.sleep(5);
            assertEquals(0, limits.activeHandlers());
        } finally {
            releaseHandler.countDown();
            handler.shutdown(java.time.Duration.ofMillis(100));
            client.close(java.time.Duration.ofMillis(100));
            callbacks.shutdownNow();
            callback.stop(0);
        }
    }

    private static final class MemoryExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final InputStream input = new ByteArrayInputStream("{\"input\":{}}".getBytes(StandardCharsets.UTF_8));
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private int status;
        private MemoryExchange() { requestHeaders.set("X-Execution-Id", "execution"); }
        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return URI.create("/invoke"); }
        @Override public String getRequestMethod() { return "POST"; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() { /* no-op: this test double ignores the call */ }
        @Override public InputStream getRequestBody() { return input; }
        @Override public OutputStream getResponseBody() { return output; }
        @Override public void sendResponseHeaders(int code, long length) { status = code; }
        @Override public InetSocketAddress getRemoteAddress() { return null; }
        @Override public int getResponseCode() { return status; }
        @Override public InetSocketAddress getLocalAddress() { return null; }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) { /* no-op: this test double ignores the call */ }
        @Override public void setStreams(InputStream input, OutputStream output) { /* no-op: this test double ignores the call */ }
        @Override public HttpPrincipal getPrincipal() { return null; }
    }
}
