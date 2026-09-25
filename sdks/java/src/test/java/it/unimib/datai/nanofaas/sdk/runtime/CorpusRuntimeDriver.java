package it.unimib.datai.nanofaas.sdk.runtime;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

final class CorpusRuntimeDriver implements AutoCloseable {
    final SaturationRuntimeHarness h;
    final CorpusCallbackExecutor callbacks = new CorpusCallbackExecutor();
    final HandlerExecutor handlers;
    final CallbackDispatcher dispatcher;
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final HttpClient http = HttpClient.newHttpClient();
    final MockMvc mvc;
    final CountDownLatch callbackRelease = new CountDownLatch(1);
    final CountDownLatch callbackBlocked = new CountDownLatch(1);
    final int maxHandlers;
    CorpusRuntimeDriver(SaturationRuntimeHarness h) {
        this.h = h;
        var config = h.config;
        maxHandlers = config.path("maxConcurrentHandlers").asInt();
        handlers = new HandlerExecutor(config.path("handlerTimeoutMs").asLong(), maxHandlers);
        RuntimeSettings settings = new RuntimeSettings(null, null, h.callbackUrl(), "handler");
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(config.path("callbackAttemptTimeoutMs").asLong()));
        CallbackClient client = new CallbackClient(RestClient.builder().requestFactory(factory).build(),
                settings, CorpusTestSupport.MAPPER, config.path("maxPendingCallbackBytes").asInt(),
                config.path("callbackMaxAttempts").asInt()) {
            @Override protected void sleepBeforeRetry(int attemptIndex) { /* deterministic retry scheduler */ }
        };
        assertTrue(client.sendResult("fixture", CallbackPayload.success(
                CorpusTestSupport.MAPPER.valueToTree(Map.of("result", "ok"))), null, "1"), "transport warm-up");
        dispatcher = new CallbackDispatcher(client, callbacks, new RuntimeMetricsFilter(metrics),
                config.path("maxPendingCallbacks").asInt(), config.path("maxPendingCallbackBytes").asLong(),
                config.path("maxPendingCallbackBytes").asInt(),
                Duration.ofMillis(config.path("shutdownTimeoutMs").asLong()));
        var controller = new InvokeController(dispatcher, new HandlerRegistry(Map.of("handler", h::handle), settings),
                new InvocationRuntimeContextResolver(settings), new ColdStartTracker(), handlers,
                new JsonOutputNormalizer(CorpusTestSupport.MAPPER),
                new RuntimePayloadLimits(CorpusTestSupport.MAPPER, config.path("maxOutputBytes").asInt()));
        mvc = MockMvcBuilders.standaloneSetup(controller, new HealthController())
                .addFilters(new RuntimePayloadLimitFilter(config.path("maxInputBytes").asInt(),
                        config.path("bodyReadTimeoutMs").asLong())).build();
    }
    void invoke(SaturationRuntimeHarness.Call call) throws Exception {
        var request = MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(
                call.request.path("method").asText()), call.request.path("path").asText());
        request.contentType("application/json");
        if (call.request.path("method").asText().equals("POST")) request.content(call.body);
        var identity = CorpusTestSupport.MAPPER.createObjectNode();
        identity.putNull("executionId");
        for (var pair : Map.of("executionId", "X-Execution-Id", "traceId", "X-Trace-Id",
                "dispatchAttempt", "X-Dispatch-Attempt").entrySet()) {
            if (call.metadata.hasNonNull(pair.getKey())) request.header(pair.getValue(), call.metadata.path(pair.getKey()).asText());
        }
        // Capture what reaches the real MVC boundary, including rejected invocations.
        var built = request.buildRequest(new org.springframework.mock.web.MockServletContext());
        if (built.getHeader("X-Execution-Id") != null) identity.put("executionId", built.getHeader("X-Execution-Id"));
        if (built.getHeader("X-Dispatch-Attempt") != null) identity.put("dispatchAttempt", Integer.parseInt(built.getHeader("X-Dispatch-Attempt")));
        call.receivedIdentity = identity;
        try {
            var response = mvc.perform(request).andReturn().getResponse();
            var observed = CorpusTestSupport.MAPPER.createObjectNode();
            observed.put("connectionOutcome", "response").put("status", response.getStatus());
            observed.set("body", CorpusTestSupport.MAPPER.readTree(response.getContentAsByteArray()));
            var headers = observed.putObject("requiredHeaders");
            for (String name : List.of("content-type", "retry-after")) {
                String value = response.getHeader(name);
                if (value != null) headers.put(name, value);
            }
            call.response = observed;
        } catch (Exception ex) {
            Throwable cause = ex;
            while (cause != null && !(cause instanceof InvocationCancelledException)) cause = cause.getCause();
            if (cause == null) throw ex;
            var observed = CorpusTestSupport.MAPPER.createObjectNode();
            observed.put("connectionOutcome", "client-disconnected").put("status", 0).putNull("body");
            observed.putObject("requiredHeaders");
            call.response = observed;
        }
    }
    void executeFixtureHandler() throws Exception {
        handlers.execute(h::handle, new InvocationRequest(Map.of("id", "fixture"), null));
    }
    AutoCloseable reserveCallback() {
        var reservation = dispatcher.reserveInvocation();
        assertNotNull(reservation);
        return reservation;
    }
    void fillCallback() throws Exception {
        callbacks.execute(() -> {
            callbackBlocked.countDown();
            try { callbackRelease.await(); } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
        });
        SaturationRuntimeHarness.await(callbackBlocked);
        int bytes = 512;
        var base = CallbackPayload.success(CorpusTestSupport.MAPPER.valueToTree(""));
        int overhead = CorpusTestSupport.MAPPER.writeValueAsBytes(base).length;
        var payload = CallbackPayload.success(CorpusTestSupport.MAPPER.valueToTree("x".repeat(bytes - overhead)));
        var reservation = dispatcher.tryReserve(bytes);
        assertNotNull(reservation);
        assertEquals(CallbackDispatcher.SubmitResult.ACCEPTED,
                dispatcher.submit(reservation, "fixture", payload, null, "1"));
        assertEquals(1, callbacks.getQueue().size(), "fixture must occupy the real callback queue");
    }
    void releaseCallbacks() { callbackRelease.countDown(); }
    int activeHandlers() {
        Semaphore admission = (Semaphore) CorpusCallbackExecutor.field(handlers, "admission");
        return maxHandlers - admission.availablePermits();
    }
    int pendingCallbacks() { return dispatcher.pendingCallbackCount(); }
    long pendingCallbackBytes() { return dispatcher.pendingCallbackBytes(); }
    long serializedCallbackBytes() { return callbacks.retainedBytes(); }
    double callbackFailures() {
        var counter = metrics.find("runtime_callback_failures").counter();
        return counter == null ? 0 : counter.count();
    }
    void awaitDrain() {
        SaturationRuntimeHarness.until(() -> pendingCallbacks() == 0 && activeHandlers() == 0
                && callbacks.getActiveCount() == 0 && callbacks.getQueue().isEmpty());
    }
    void beginStop() { handlers.shutdown(); }
    void stop() { dispatcher.shutdown(); }
    boolean isStopped() { return callbacks.isTerminated() && activeHandlers() == 0; }
    boolean healthy() throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/health")).andReturn().getResponse().getStatus() == 200;
    }
    @Override public void close() throws Exception {
        releaseCallbacks();
        handlers.shutdown();
        dispatcher.shutdown();
        assertTrue(callbacks.awaitTermination(4, TimeUnit.SECONDS));
        http.close();
        metrics.close();
    }
}
