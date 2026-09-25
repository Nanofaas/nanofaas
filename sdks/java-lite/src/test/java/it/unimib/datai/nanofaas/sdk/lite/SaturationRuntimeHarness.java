package it.unimib.datai.nanofaas.sdk.lite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test-only action interpreter. Inputs come exclusively from requests/backend/actions/config.
 * Expectations are read only after execution. Runtime owners and wire captures supply observations.
 */
final class SaturationRuntimeHarness implements AutoCloseable {
    static final Duration WAIT = Duration.ofSeconds(4);
    final JsonNode scenario;
    final JsonNode config;
    final Map<String, Call> calls = new LinkedHashMap<>();
    final Map<String, CountDownLatch> barriers = new HashMap<>();
    final Set<String> observations = ConcurrentHashMap.newKeySet();
    final List<Throwable> failures = new CopyOnWriteArrayList<>();
    final List<ILoggingEvent> logs = new CopyOnWriteArrayList<>();
    final HttpServer backend;
    final ExecutorService backendThreads = Executors.newVirtualThreadPerTaskExecutor();
    final CountDownLatch fixtureStarted = new CountDownLatch(1);
    final CountDownLatch fixtureRelease = new CountDownLatch(1);
    final AtomicLong retainedInput = new AtomicLong();
    final AtomicLong retainedOutput = new AtomicLong();
    final AtomicLong peakOutput = new AtomicLong();
    final AtomicInteger unexpectedCallbacks = new AtomicInteger();
    final AtomicInteger starts = new AtomicInteger();
    final AtomicInteger sent = new AtomicInteger();
    final AppenderBase<ILoggingEvent> appender;
    CorpusRuntimeDriver runtime;
    JsonNode initial;
    JsonNode last;
    Thread stopThread;
    Thread fixtureThread;
    AutoCloseable fixtureReservation;
    boolean stopped;
    boolean restarted;

    static void run(JsonNode scenario, JsonNode config) throws Exception {
        try (var harness = new SaturationRuntimeHarness(scenario, config)) {
            harness.execute();
            harness.assertMatches();
        }
    }
    SaturationRuntimeHarness(JsonNode scenario, JsonNode config) throws Exception {
        this.scenario = scenario;
        this.config = config;
        for (JsonNode request : scenario.path("requests")) {
            String id = request.path("id").asText();
            calls.put(id, new Call(request, backendPlan("handlers", id), backendPlan("callbacks", id)));
        }
        for (JsonNode barrier : scenario.path("harness").path("barriers"))
            barriers.put(barrier.path("id").asText(), new CountDownLatch(
                    barrier.path("initialState").asText().equals("closed") ? 1 : 0));
        appender = new AppenderBase<>() {
            @Override protected void append(ILoggingEvent event) { logs.add(event); }
        };
        appender.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(appender);
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.setExecutor(backendThreads);
        backend.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/fixture:complete")) {
                    CorpusTestSupport.MAPPER.readTree(exchange.getRequestBody());
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                String attempt = exchange.getRequestHeaders().getFirst("X-Dispatch-Attempt");
                Call call = calls.values().stream().filter(c ->
                        path.equals("/" + c.metadata.path("executionId").asText() + ":complete")
                        && Objects.equals(attempt, c.metadata.path("dispatchAttempt").asText()))
                        .findFirst().orElse(null);
                if (call == null) {
                    unexpectedCallbacks.incrementAndGet();
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
                ObjectNode projection = CorpusTestSupport.MAPPER.createObjectNode();
                projection.put("method", exchange.getRequestMethod());
                projection.put("url", "http://callback.invalid" + exchange.getRequestURI());
                var headers = projection.putObject("headers");
                for (String name : List.of("content-type", "x-trace-id", "x-dispatch-attempt"))
                    headers.put(name, exchange.getRequestHeaders().getFirst(name));
                projection.set("payload", CorpusTestSupport.MAPPER.readTree(exchange.getRequestBody()));
                call.callbacks.add(projection);
                observations.add("callback-attempt");
                int status = switch (call.callbackPlan.path("behavior").asText()) {
                    case "succeed" -> 204;
                    case "retryable-failure" -> 500;
                    default -> throw new AssertionError("unexpected callback " + call.id);
                };
                exchange.sendResponseHeaders(status, -1);
                call.backendStatuses.add(status);
            } catch (Throwable ex) { failures.add(ex); }
            finally { exchange.close(); }
        });
        backend.start();
    }
    String callbackUrl() { return "http://127.0.0.1:" + backend.getAddress().getPort(); }
    JsonNode backendPlan(String family, String id) {
        for (JsonNode plan : scenario.path("backend").path(family))
            if (plan.path("requestId").asText().equals(id)) return plan;
        throw new AssertionError("missing backend plan " + id);
    }
    boolean hasAction(String name) {
        for (JsonNode action : scenario.path("harness").path("actions"))
            if (action.path("action").asText().equals(name)) return true;
        return false;
    }
    void execute() throws Exception {
        for (JsonNode action : scenario.path("harness").path("actions")) {
            Call call = calls.get(action.path("requestId").asText());
            switch (action.path("action").asText()) {
                case "start-runtime" -> runtime = new CorpusRuntimeDriver(this);
                case "start-runtime-again" -> {
                    assertTrue(stopped, "restart must follow completed stop");
                    runtime.close();
                    runtime = new CorpusRuntimeDriver(this);
                    restarted = true;
                }
                case "fill-callback-capacity" -> {
                    runtime.fillCallback();
                    // The health fixture saturates both independent owners.
                    if (calls.values().stream().allMatch(c -> c.request.path("role").asText().equals("health")))
                        fillHandler(false);
                }
                case "fill-handler-capacity" -> fillHandler(true);
                case "send-request", "probe-health" -> {
                    snapshotInitial();
                    assertNotNull(call);
                    assertNull(call.thread, "one client send per request");
                    sent.incrementAndGet();
                    call.thread = Thread.ofPlatform().unstarted(() -> {
                        try { runtime.invoke(call); }
                        catch (Throwable ex) { failures.add(ex); }
                        finally { responseFinished(call); }
                    });
                    call.thread.start();
                }
                case "await-response" -> join(call.thread);
                case "await-callback" -> {
                    join(call.thread);
                    runtime.awaitDrain();
                }
                case "await-barrier" -> await(barriers.get(action.path("barrier").asText()));
                case "cancel-request" -> { call.thread.interrupt(); join(call.thread); }
                case "drain-callbacks" -> { runtime.releaseCallbacks(); drainHandler(); runtime.awaitDrain(); }
                case "drain-handlers" -> { drainHandler(); runtime.awaitDrain(); }
                case "begin-stop" -> {
                    snapshotInitial();
                    runtime.beginStop();
                    String barrier = action.path("barrier").asText();
                    if (barriers.containsKey(barrier)) barriers.get(barrier).countDown();
                    stopThread = Thread.ofPlatform().start(() -> {
                        try { runtime.stop(); } catch (Throwable ex) { failures.add(ex); }
                    });
                }
                case "await-stop" -> {
                    join(stopThread);
                    stopped = runtime.isStopped();
                    assertTrue(stopped, "runtime owners failed to stop");
                    observations.add("stop-complete");
                }
                case "control-plane-redispatch" -> {
                    assertEquals("control-plane", action.path("actor").asText());
                    assertNotNull(call);
                }
                default -> throw new AssertionError("unsupported action " + action);
            }
        }
        for (Call call : calls.values()) {
            join(call.thread);
            if (call.started.get()) await(call.exited);
        }
        runtime.awaitDrain();
        last = snapshot();
        if (allZero(last)) observations.add("counters-zero");
        if (restarted && runtime.healthy()) observations.add("restart-complete");
        assertTrue(failures.isEmpty(), () -> "harness/runtime errors: " + failures);
        assertEquals(0, unexpectedCallbacks.get(), "unexpected callback identities");
        if (starts.get() <= sent.get() && unexpectedCallbacks.get() == 0)
            observations.add("runtime-redispatch-zero");
        for (Call call : calls.values()) {
            if (call.response != null && call.response.path("status").asInt() != 0) {
                observations.add("wire-response");
                if (call.request.path("role").asText().equals("health")) observations.add("health-response");
            } else observations.add("no-wire-response");
            if (!call.callbacks.isEmpty() && call.backendStatuses.contains(204) && runtime.callbackFailures() == 0)
                observations.add("callback-delivery");
        }
        if (runtime.callbackFailures() > 0) observations.add("callback-failure-metric");
        if (logs.stream().anyMatch(e -> e.getFormattedMessage().contains("callback attempts failed for execution")
                && calls.values().stream().anyMatch(c -> e.getFormattedMessage().contains(
                        c.metadata.path("executionId").asText())))) observations.add("structured-log");
    }
    Object handle(InvocationRequest request) {
        Map<?, ?> input = (Map<?, ?>) request.input();
        String id = (String) input.get("id");
        if ("fixture".equals(id)) {
            byte[] bytes = body("fixture", 128);
            retainedInput.addAndGet(bytes.length);
            fixtureStarted.countDown();
            try {
                boolean released = false;
                while (!released) {
                    try { fixtureRelease.await(); released = true; }
                    catch (InterruptedException _) { /* physical fixture deliberately outlives its waiter */ }
                }
                return Map.of("result", "ok");
            } finally { retainedInput.addAndGet(-bytes.length); }
        }
        Call call = Objects.requireNonNull(calls.get(id), "unrecognized runtime handler input");
        starts.incrementAndGet();
        call.started.set(true);
        observations.add("handler-start");
        retainedInput.addAndGet(call.body.length);
        JsonNode barrier = call.handlerPlan.path("barrier");
        if (!barrier.isNull()) barriers.get(barrier.asText()).countDown();
        try {
            switch (call.handlerPlan.path("behavior").asText()) {
                case "not-invoked" -> throw new AssertionError("rejected handler started: " + id);
                case "fail" -> {
                    call.failed.set(true);
                    throw new IllegalStateException("Handler failed");
                }
                case "block-until-cancelled" -> {
                    try { new CountDownLatch(1).await(); }
                    catch (InterruptedException _) {
                        call.cancelled.set(true);
                        observations.add("handler-cancel");
                        Thread.currentThread().interrupt();
                    }
                    return returnedOutput(call, Map.of("result", "late"));
                }
                case "succeed" -> {
                    if (call.handlerPlan.path("outputRelationToLimit").asText().equals("above-limit"))
                        return returnedOutput(call, "x".repeat(call.handlerPlan.path("outputBytes").asInt()));
                    return returnedOutput(call, Map.of("result", "ok"));
                }
                default -> throw new AssertionError("unknown handler behavior");
            }
        } finally {
            retainedInput.addAndGet(-call.body.length);
            call.exited.countDown();
        }
    }
    private Object returnedOutput(Call call, Object output) {
        try {
            long bytes = CorpusTestSupport.MAPPER.writeValueAsBytes(output).length;
            synchronized (call) {
                // A cancelled waiter has no output owner; the real Future discards its late result.
                if (!call.responseFinished) {
                    call.outputBytes = bytes;
                    long retained = retainedOutput.addAndGet(bytes);
                    peakOutput.accumulateAndGet(retained, Math::max);
                }
            }
            return output;
        } catch (Exception ex) { throw new AssertionError(ex); }
    }
    private void responseFinished(Call call) {
        synchronized (call) {
            retainedOutput.addAndGet(-call.outputBytes);
            call.outputBytes = 0;
            call.responseFinished = true;
        }
    }
    void fillHandler(boolean reserveCallback) throws Exception {
        if (reserveCallback) fixtureReservation = runtime.reserveCallback();
        fixtureThread = Thread.ofPlatform().start(() -> {
            try { runtime.executeFixtureHandler(); }
            catch (TimeoutException _) { /* waiter ends; fixture retains the real permit */ }
            catch (Throwable ex) { failures.add(ex); }
        });
        await(fixtureStarted);
        assertEquals(config.path("maxConcurrentHandlers").asInt(), runtime.activeHandlers());
    }
    void drainHandler() throws Exception {
        fixtureRelease.countDown();
        if (fixtureThread != null) join(fixtureThread);
        if (fixtureReservation != null) { fixtureReservation.close(); fixtureReservation = null; }
        until(() -> runtime.activeHandlers() == 0);
    }
    void snapshotInitial() {
        if (initial == null) initial = snapshot();
    }
    JsonNode snapshot() {
        return CorpusTestSupport.MAPPER.valueToTree(Map.of(
                "activeHandlers", (long) runtime.activeHandlers(),
                "inputBytes", retainedInput.get(),
                "outputBytes", retainedOutput.get(),
                "pendingCallbacks", (long) runtime.pendingCallbacks(),
                "pendingCallbackBytes", runtime.pendingCallbackBytes(),
                "serializedCallbackBytes", runtime.serializedCallbackBytes()));
    }
    static boolean allZero(JsonNode counters) {
        for (JsonNode value : counters) if (value.asLong() != 0) return false;
        return true;
    }
    void assertMatches() {
        SaturationCorpusContractAssertions.assertScenario(scenario, this);
    }
    JsonNode observedHandlers(Call call) {
        String code = call.response.path("body").path("error").path("code").asText();
        if (call.response.path("status").asInt() == 0 && !call.callbacks.isEmpty())
            code = call.callbacks.getFirst().path("payload").path("error").path("code").asText();
        String terminal = !call.started.get() ? "not-started" : switch (code) {
            case "RUNTIME_OUTPUT_TOO_LARGE" -> "output-rejected";
            case "HANDLER_TIMEOUT" -> "timed-out";
            case "INVOCATION_CANCELLED" -> "cancelled";
            default -> call.failed.get() ? "failed" : "succeeded";
        };
        return CorpusTestSupport.MAPPER.valueToTree(Map.of("started", call.started.get(),
                "cancelRequested", call.cancelled.get(), "terminal", terminal));
    }
    JsonNode observedCallbacks(Call call) {
        ObjectNode result = CorpusTestSupport.MAPPER.createObjectNode();
        boolean required = call.metadata.hasNonNull("executionId") && !call.request.path("role").asText().equals("health");
        boolean attempted = !call.callbacks.isEmpty();
        boolean delivered = attempted && call.backendStatuses.contains(204) && runtime.callbackFailures() == 0;
        result.put("required", required).put("attempted", attempted).put("delivered", delivered);
        result.put("attempts", call.callbacks.size());
        result.put("terminal", !required ? "not-required" : !attempted ? "rejected-before-handler"
                : delivered ? "delivered" : "exhausted");
        var attempts = result.putArray("dispatchAttempts");
        for (JsonNode callback : call.callbacks)
            attempts.add(Integer.parseInt(callback.path("headers").path("x-dispatch-attempt").asText()));
        result.set("requestProjection", attempted ? call.callbacks.getFirst() : CorpusTestSupport.MAPPER.nullNode());
        // Every retry must carry the same observed body and metadata.
        for (JsonNode callback : call.callbacks) assertEquals(call.callbacks.getFirst(), callback);
        return result;
    }
    JsonNode observedIdentity() {
        ObjectNode result = CorpusTestSupport.MAPPER.createObjectNode();
        result.set("executionId", calls.values().iterator().next().receivedIdentity.path("executionId"));
        var attempts = result.putArray("requestDispatchAttempts");
        for (Call call : calls.values())
            if (call.receivedIdentity.hasNonNull("dispatchAttempt"))
                attempts.add(call.receivedIdentity.path("dispatchAttempt").asInt());
        result.put("runtimeRedispatchCount", Math.max(0, starts.get() - sent.get()) + unexpectedCallbacks.get());
        return result;
    }
    static byte[] body(String id, int size) {
        String prefix = "{\"input\":{\"id\":\"" + id + "\",\"padding\":\"";
        String suffix = "\"}}";
        return (prefix + "x".repeat(Math.max(0, size - prefix.length() - suffix.length())) + suffix)
                .getBytes(StandardCharsets.UTF_8);
    }
    static void until(BooleanSupplier condition) {
        org.awaitility.Awaitility.await().pollInterval(Duration.ofMillis(1)).atMost(WAIT).until(condition::getAsBoolean);
    }
    static void await(CountDownLatch latch) throws InterruptedException {
        assertNotNull(latch, "undeclared barrier");
        assertTrue(latch.await(WAIT.toMillis(), TimeUnit.MILLISECONDS), "barrier timeout");
    }
    static void join(Thread thread) throws InterruptedException {
        assertNotNull(thread, "missing owned thread");
        thread.join(WAIT.toMillis());
        assertFalse(thread.isAlive(), "owned thread failed to terminate");
    }
    @Override public void close() throws Exception {
        fixtureRelease.countDown();
        if (runtime != null) runtime.releaseCallbacks();
        for (Call call : calls.values()) if (call.thread != null && call.thread.isAlive()) call.thread.interrupt();
        if (fixtureReservation != null) fixtureReservation.close();
        if (runtime != null) runtime.close();
        if (fixtureThread != null) join(fixtureThread);
        if (stopThread != null) join(stopThread);
        backend.stop(0);
        backendThreads.shutdownNow();
        assertTrue(backendThreads.awaitTermination(WAIT.toMillis(), TimeUnit.MILLISECONDS));
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(appender);
        appender.stop();
    }
    static final class Call {
        final String id;
        final JsonNode request, metadata, handlerPlan, callbackPlan;
        final byte[] body;
        final AtomicBoolean started = new AtomicBoolean(), cancelled = new AtomicBoolean(), failed = new AtomicBoolean();
        final CountDownLatch exited = new CountDownLatch(1);
        final List<JsonNode> callbacks = new CopyOnWriteArrayList<>();
        final List<Integer> backendStatuses = new CopyOnWriteArrayList<>();
        volatile JsonNode response;
        volatile JsonNode receivedIdentity;
        Thread thread;
        long outputBytes;
        boolean responseFinished;
        Call(JsonNode request, JsonNode handlerPlan, JsonNode callbackPlan) {
            this.id = request.path("id").asText();
            this.request = request;
            this.metadata = request.path("metadata");
            this.handlerPlan = handlerPlan;
            this.callbackPlan = callbackPlan;
            this.body = SaturationRuntimeHarness.body(id, request.path("payload").path("inputBytes").asInt());
        }
    }
}
