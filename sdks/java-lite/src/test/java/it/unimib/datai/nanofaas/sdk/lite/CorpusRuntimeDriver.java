package it.unimib.datai.nanofaas.sdk.lite;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import it.unimib.datai.nanofaas.sdk.lite.handler.InvokeHandler;
import it.unimib.datai.nanofaas.sdk.lite.handler.HealthHandler;
import it.unimib.datai.nanofaas.sdk.lite.callback.CallbackClient;
import it.unimib.datai.nanofaas.sdk.lite.callback.CorpusCallbackClientFactory;
import it.unimib.datai.nanofaas.sdk.lite.metrics.RuntimeMetrics;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

final class CorpusRuntimeDriver implements AutoCloseable {
    final SaturationRuntimeHarness h;
    final CorpusCallbackExecutor callbacks = new CorpusCallbackExecutor();
    final Object limits;
    final InvokeHandler handler;
    final RuntimeMetrics metrics = new RuntimeMetrics("corpus");
    final HttpClient http = HttpClient.newHttpClient();
    final CallbackClient client;
    final CountDownLatch callbackRelease = new CountDownLatch(1);
    final CountDownLatch callbackBlocked = new CountDownLatch(1);
    final Class<?> limitsType;

    CorpusRuntimeDriver(SaturationRuntimeHarness h) throws Exception {
        this.h = h;
        var c = h.config;
        limitsType = Class.forName("it.unimib.datai.nanofaas.sdk.lite.handler.RuntimeLimits");
        var constructor = limitsType.getDeclaredConstructor(int.class, int.class, long.class,
                int.class, int.class, int.class, int.class, int.class, int.class, int.class);
        constructor.setAccessible(true);
        // Single payload cap is not separately specified by the corpus; a 512-byte queued
        // fixture fits under its aggregate cap. Normal invocation rows use the full cap.
        int payloadCap = h.hasAction("fill-callback-capacity") ? 512 : c.path("maxPendingCallbackBytes").asInt();
        limits = constructor.newInstance(c.path("maxConcurrentHandlers").asInt(),
                c.path("maxPendingCallbacks").asInt(), c.path("maxPendingCallbackBytes").asLong(),
                c.path("maxInputBytes").asInt(), c.path("maxOutputBytes").asInt(), payloadCap,
                c.path("bodyReadTimeoutMs").asInt(), c.path("callbackAttemptTimeoutMs").asInt(),
                c.path("callbackMaxAttempts").asInt(), c.path("shutdownTimeoutMs").asInt());
        client = CorpusCallbackClientFactory.create(http, CorpusTestSupport.MAPPER, h.callbackUrl(),
                c.path("callbackAttemptTimeoutMs").asLong(), c.path("callbackMaxAttempts").asInt());
        assertTrue(client.sendResult("fixture", InvocationResult.success(Map.of("result", "ok")), null, "1"),
                "transport warm-up");
        var handlerConstructor = InvokeHandler.class.getDeclaredConstructor(FunctionHandler.class,
                CallbackClient.class, RuntimeMetrics.class, com.fasterxml.jackson.databind.ObjectMapper.class,
                String.class, ThreadPoolExecutor.class, long.class, limitsType, boolean.class);
        handlerConstructor.setAccessible(true);
        handler = handlerConstructor.newInstance((FunctionHandler) h::handle, client, metrics,
                CorpusTestSupport.MAPPER, "corpus", callbacks, c.path("handlerTimeoutMs").asLong(), limits, true);
    }
    void invoke(SaturationRuntimeHarness.Call call) throws Exception {
        MemoryExchange exchange = new MemoryExchange(call);
        var identity = CorpusTestSupport.MAPPER.createObjectNode();
        identity.putNull("executionId");
        String execution = exchange.getRequestHeaders().getFirst("X-Execution-Id");
        if (execution != null) identity.put("executionId", execution);
        String dispatch = exchange.getRequestHeaders().getFirst("X-Dispatch-Attempt");
        if (dispatch != null) identity.put("dispatchAttempt", Integer.parseInt(dispatch));
        call.receivedIdentity = identity;
        if (exchange.getRequestURI().getPath().equals("/health")) new HealthHandler().handle(exchange);
        else handler.handle(exchange);
        var response = CorpusTestSupport.MAPPER.createObjectNode();
        response.put("connectionOutcome", exchange.status == 0 ? "client-disconnected" : "response");
        response.put("status", exchange.status);
        response.set("body", exchange.output.size() == 0 ? CorpusTestSupport.MAPPER.nullNode()
                : CorpusTestSupport.MAPPER.readTree(exchange.output.toByteArray()));
        var headers = response.putObject("requiredHeaders");
        for (String name : List.of("content-type", "retry-after")) {
            String value = exchange.getResponseHeaders().getFirst(name);
            if (value != null) headers.put(name, value);
        }
        assertTrue(exchange.closed, "runtime must finish the exchange");
        call.response = response;
    }
    void executeFixtureHandler() throws Exception {
        CorpusCallbackExecutor.call(handler, "invokeWithTimeout", new Class<?>[]{InvocationRequest.class},
                new InvocationRequest(Map.of("id", "fixture"), null));
    }
    AutoCloseable reserveCallback() throws Exception {
        var reservation = (AutoCloseable) CorpusCallbackExecutor.call(limits, "tryReserveCallback", new Class<?>[]{});
        assertNotNull(reservation);
        return reservation;
    }
    void fillCallback() throws Exception {
        callbacks.execute(() -> {
            callbackBlocked.countDown();
            try { callbackRelease.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        });
        SaturationRuntimeHarness.await(callbackBlocked);
        int bytes = 512;
        int overhead = CorpusTestSupport.MAPPER.writeValueAsBytes(
                it.unimib.datai.nanofaas.sdk.lite.callback.CallbackPayload.from(InvocationResult.success(""))).length;
        var result = InvocationResult.success("x".repeat(bytes - overhead));
        var reservation = reserveCallback();
        Object handoff = CorpusCallbackExecutor.call(handler, "dispatchCallback",
                new Class<?>[]{reservation.getClass(), String.class, InvocationResult.class, String.class, String.class},
                reservation, "fixture", result, null, "1");
        assertEquals("ACCEPTED", handoff.toString());
        assertEquals(1, callbacks.getQueue().size(), "fixture must occupy the real callback queue");
    }
    void releaseCallbacks() { callbackRelease.countDown(); }
    long counter(String name) {
        try { return ((Number) CorpusCallbackExecutor.call(limits, name, new Class<?>[]{})).longValue(); }
        catch (Exception ex) { throw new AssertionError(ex); }
    }
    int activeHandlers() { return (int) counter("activeHandlers"); }
    int pendingCallbacks() { return (int) counter("pendingCallbacks"); }
    long pendingCallbackBytes() { return counter("pendingCallbackBytes"); }
    long serializedCallbackBytes() { return callbacks.retainedBytes(); }
    double callbackFailures() {
        var counter = (io.prometheus.metrics.core.metrics.Counter) CorpusCallbackExecutor.field(metrics, "callbackFailures");
        return counter.labelValues("corpus").get();
    }
    void awaitDrain() {
        SaturationRuntimeHarness.until(() -> pendingCallbacks() == 0 && activeHandlers() == 0
                && callbacks.getActiveCount() == 0 && callbacks.getQueue().isEmpty());
    }
    void beginStop() { handler.beginStop(); }
    void stop() { handler.shutdown(Duration.ofMillis(h.config.path("shutdownTimeoutMs").asLong())); }
    boolean isStopped() { return callbacks.isTerminated() && activeHandlers() == 0; }
    boolean healthy() throws Exception {
        MemoryExchange exchange = new MemoryExchange(null);
        new HealthHandler().handle(exchange);
        return exchange.status == 200;
    }
    @Override public void close() throws Exception {
        releaseCallbacks();
        handler.shutdown(Duration.ofSeconds(1));
        assertTrue(callbacks.awaitTermination(4, TimeUnit.SECONDS));
        http.close();
    }
    /** Captures real HttpHandler output; cancellation interrupts the owned request thread. */
    private static final class MemoryExchange extends HttpExchange {
        final Headers requestHeaders = new Headers(), responseHeaders = new Headers();
        final ByteArrayInputStream input;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final String method, path;
        int status;
        boolean closed;
        MemoryExchange(SaturationRuntimeHarness.Call call) {
            method = call == null ? "GET" : call.request.path("method").asText();
            path = call == null ? "/health" : call.request.path("path").asText();
            input = new ByteArrayInputStream(call == null ? new byte[0] : call.body);
            requestHeaders.set("Content-Type", "application/json");
            if (call != null) {
                for (var pair : Map.of("executionId", "X-Execution-Id", "traceId", "X-Trace-Id",
                        "dispatchAttempt", "X-Dispatch-Attempt").entrySet())
                    if (call.metadata.hasNonNull(pair.getKey()))
                        requestHeaders.set(pair.getValue(), call.metadata.path(pair.getKey()).asText());
            }
        }
        @Override public Headers getRequestHeaders() { return requestHeaders; }
        @Override public Headers getResponseHeaders() { return responseHeaders; }
        @Override public URI getRequestURI() { return URI.create(path); }
        @Override public String getRequestMethod() { return method; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() { closed = true; }
        @Override public InputStream getRequestBody() { return input; }
        @Override public OutputStream getResponseBody() { return output; }
        @Override public void sendResponseHeaders(int code, long length) { status = code; }
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress(0); }
        @Override public int getResponseCode() { return status; }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress(0); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) { }
        @Override public void setStreams(InputStream input, OutputStream output) { throw new UnsupportedOperationException(); }
        @Override public HttpPrincipal getPrincipal() { return null; }
    }
}
