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

    @Test
    void timeoutCleanupDoesNotDrainTheInputOnTheCoordinatorThread() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var limits = new RuntimeLimits(1, 1, 2048, 1024, 1024, 1024, 40, 100, 1, 1000);
        var callbacks = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
        var handler = new InvokeHandler(request -> { throw new AssertionError("handler must not start"); },
                new CallbackClient(mapper, null), new RuntimeMetrics("cleanup-race"), mapper, "cleanup-race", callbacks, 1000, limits);
        var exchange = new BlockingExchange(true);
        var invocation = new java.util.concurrent.FutureTask<Void>(() -> { handler.handle(exchange); return null; });
        Thread coordinator = Thread.ofVirtual().start(invocation);
        try {
            assertTrue(exchange.readerInterrupted.await(1, TimeUnit.SECONDS));
            // Give an erroneous coordinator-side close the chance to claim the
            // stream before the delayed reader can enter socket I/O/cleanup.
            exchange.coordinatorCloseEntered.await(100, TimeUnit.MILLISECONDS);
            exchange.allowReaderCleanup.countDown();
            invocation.get(2, TimeUnit.SECONDS);
            assertEquals(408, exchange.status);
            assertEquals(0, limits.pendingCallbacks());
            assertEquals(0, exchange.release.getCount());
        } finally {
            exchange.coordinatorCloseRelease.countDown(); exchange.allowReaderCleanup.countDown(); exchange.release.countDown();
            coordinator.join(2000); handler.shutdown(java.time.Duration.ofSeconds(1)); callbacks.shutdownNow();
        }
    }

    private static final class BlockingExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final CountDownLatch release = new CountDownLatch(1);
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private int status;
        private final boolean blockExternalClose;
        private final CountDownLatch coordinatorCloseRelease = new CountDownLatch(1);
        private volatile Thread reader;
        private final CountDownLatch readerInterrupted = new CountDownLatch(1);
        private final CountDownLatch allowReaderCleanup = new CountDownLatch(1);
        private final CountDownLatch coordinatorCloseEntered = new CountDownLatch(1);
        private final java.util.concurrent.atomic.AtomicBoolean streamClosed = new java.util.concurrent.atomic.AtomicBoolean();
        private final InputStream requestBody = new InputStream() {
            @Override public int read() throws IOException {
                // Before any socket I/O: interruption here does not close a channel.
                reader = Thread.currentThread();
                try { release.await(); return -1; }
                catch (InterruptedException ex) {
                    if (blockExternalClose) {
                        readerInterrupted.countDown();
                        boolean waiting = true;
                        while (waiting) {
                            try { allowReaderCleanup.await(); waiting = false; }
                            catch (InterruptedException _) { /* preserve interruption after the staging barrier */ }
                        }
                    }
                    Thread.currentThread().interrupt(); throw new IOException(ex);
                }
            }
            @Override public void close() throws IOException {
                if (!streamClosed.compareAndSet(false, true)) return;
                if (blockExternalClose && Thread.currentThread() != reader) {
                    coordinatorCloseEntered.countDown();
                    try { coordinatorCloseRelease.await(); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException(ex); }
                }
                release.countDown();
            }
        };
        private BlockingExchange() { this(false); }
        private BlockingExchange(boolean blockExternalClose) {
            this.blockExternalClose = blockExternalClose;
            requestHeaders.set("X-Execution-Id", "execution");
        }
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
