package it.unimib.datai.nanofaas.sdk.lite.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import it.unimib.datai.nanofaas.sdk.lite.callback.CallbackClient;
import it.unimib.datai.nanofaas.sdk.lite.metrics.RuntimeMetrics;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvokeHandlerBodyDeadlineTest {
    @Test
    void stalledRequestBodyGetsFiniteCanonicalTimeout() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var corpus = mapper.readTree(java.nio.file.Files.readAllBytes(
                SharedFailureWireCorpusTest.find("sdks/runtime-contract/failure-wire-corpus.json")));
        var config = corpus.path("config");
        var expected = corpus.path("contractDefinitions").path("ingress-io-timeout");
        RuntimeLimits limits = new RuntimeLimits(1, 1, 2_048, 1_024, 1_024, 1_024,
                config.path("bodyReadTimeoutMs").asInt(), config.path("callbackAttemptTimeoutMs").asInt(),
                config.path("callbackMaxAttempts").asInt(), config.path("deadlineMs").asInt());
        ThreadPoolExecutor callbacks = new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
        java.util.concurrent.atomic.AtomicBoolean called = new java.util.concurrent.atomic.AtomicBoolean();
        InvokeHandler handler = new InvokeHandler(request -> { called.set(true); return request.input(); }, new CallbackClient(mapper, null),
                new RuntimeMetrics("body-deadline"), mapper, "body-deadline", callbacks, 1_000, limits);
        BlockingExchange exchange = new BlockingExchange();
        long started = System.nanoTime();
        try {
            handler.handle(exchange);

            assertEquals(expected.path("httpStatus").asInt(), exchange.status);
            assertEquals(expected.path("handlerStarted").asBoolean(), called.get());
            assertEquals(expected.path("errorCode").asText(), mapper.readTree(exchange.body.toByteArray()).path("error").path("code").asText());
            assertEquals(0, exchange.release.getCount(), "physical input stream released");
            assertEquals(corpus.path("finalCounters").path("pendingCallbacks").asInt(), limits.pendingCallbacks());
            assertEquals(corpus.path("finalCounters").path("pendingCallbackBytes").asLong(), limits.pendingCallbackBytes());
            assertEquals(corpus.path("finalCounters").path("activeHandlers").asInt(), limits.activeHandlers());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < config.path("deadlineMs").asLong());
        } finally {
            exchange.release.countDown();
            handler.shutdown(java.time.Duration.ofMillis(100));
            callbacks.shutdownNow();
        }
    }

    private static final class BlockingExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final CountDownLatch release = new CountDownLatch(1);
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private int status;
        private final InputStream requestBody = new InputStream() {
            @Override public int read() throws IOException {
                try { release.await(); return -1; }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException(ex); }
            }
            @Override public void close() { release.countDown(); }
        };
        private BlockingExchange() { requestHeaders.set("X-Execution-Id", "execution"); }
        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return URI.create("/invoke"); }
        @Override public String getRequestMethod() { return "POST"; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() { /* no-op: this test double ignores the call */ }
        @Override public InputStream getRequestBody() { return requestBody; }
        @Override public OutputStream getResponseBody() { return body; }
        @Override public void sendResponseHeaders(int responseCode, long responseLength) { status = responseCode; }
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
