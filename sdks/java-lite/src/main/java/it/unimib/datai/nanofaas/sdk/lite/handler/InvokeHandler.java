package it.unimib.datai.nanofaas.sdk.lite.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import it.unimib.datai.nanofaas.sdk.lite.FunctionContext;
import it.unimib.datai.nanofaas.sdk.lite.callback.CallbackClient;
import it.unimib.datai.nanofaas.sdk.lite.metrics.RuntimeMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@SuppressWarnings("EmptyCatch") // Closing a timed-out request body is best-effort.
public final class InvokeHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(InvokeHandler.class);
    private static final String ERROR_KEY = "error";
    private static final AtomicInteger CALLBACK_THREAD_COUNTER = new AtomicInteger();
    private final FunctionHandler functionHandler;
    private final CallbackClient callbackClient;
    private final RuntimeMetrics metrics;
    private final ObjectMapper objectMapper;
    private final String functionName;
    private final String envExecutionId;
    private final ThreadPoolExecutor callbackExecutor;
    private final boolean ownsCallbackExecutor;
    private final long handlerTimeoutMs;
    private final RuntimeLimits limits;
    private final BoundedJson boundedJson;
    private final Object handlerLifecycle = new Object();
    private final Set<HandlerWork> activeHandlers = new HashSet<>();
    private boolean accepting = true;
    private final long containerStartNanos = System.nanoTime();
    private final AtomicBoolean firstInvocation = new AtomicBoolean(true);

    public InvokeHandler(FunctionHandler functionHandler, CallbackClient callbackClient,
                         RuntimeMetrics metrics, ObjectMapper objectMapper, String functionName) {
        this(functionHandler, callbackClient, metrics, objectMapper, functionName,
                newCallbackExecutor(), setting("nanofaas.handler.timeout.ms", "NANOFAAS_HANDLER_TIMEOUT", 30_000),
                true, RuntimeLimits.fromSettings());
    }

    InvokeHandler(FunctionHandler functionHandler, CallbackClient callbackClient,
                  RuntimeMetrics metrics, ObjectMapper objectMapper, String functionName,
                  ThreadPoolExecutor callbackExecutor, long handlerTimeoutMs) {
        this(functionHandler, callbackClient, metrics, objectMapper, functionName,
                callbackExecutor, handlerTimeoutMs, false, RuntimeLimits.fromSettings());
    }

    InvokeHandler(FunctionHandler functionHandler, CallbackClient callbackClient,
                  RuntimeMetrics metrics, ObjectMapper objectMapper, String functionName,
                  ThreadPoolExecutor callbackExecutor, long handlerTimeoutMs, RuntimeLimits limits) {
        this(functionHandler, callbackClient, metrics, objectMapper, functionName,
                callbackExecutor, handlerTimeoutMs, false, limits);
    }

    InvokeHandler(FunctionHandler functionHandler, CallbackClient callbackClient,
                  RuntimeMetrics metrics, ObjectMapper objectMapper, String functionName,
                  ThreadPoolExecutor callbackExecutor, long handlerTimeoutMs, RuntimeLimits limits,
                  boolean ownsCallbackExecutor) {
        this(functionHandler, callbackClient, metrics, objectMapper, functionName,
                callbackExecutor, handlerTimeoutMs, ownsCallbackExecutor, limits);
    }

    private InvokeHandler(FunctionHandler functionHandler, CallbackClient callbackClient,
                          RuntimeMetrics metrics, ObjectMapper objectMapper, String functionName,
                          ThreadPoolExecutor callbackExecutor, long handlerTimeoutMs,
                          boolean ownsCallbackExecutor, RuntimeLimits limits) {
        this.functionHandler = functionHandler;
        this.callbackClient = callbackClient;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.functionName = functionName;
        this.envExecutionId = System.getenv("EXECUTION_ID");
        this.callbackExecutor = callbackExecutor;
        this.ownsCallbackExecutor = ownsCallbackExecutor;
        this.handlerTimeoutMs = handlerTimeoutMs;
        this.limits = limits;
        this.boundedJson = new BoundedJson(objectMapper);
    }

    private static ThreadPoolExecutor newCallbackExecutor() {
        int workers = setting("nanofaas.callback.worker.count", "NANOFAAS_CALLBACK_WORKER_COUNT", 2);
        int capacity = setting("nanofaas.callback.queue.capacity", "NANOFAAS_CALLBACK_QUEUE_CAPACITY", 128);
        return new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), runnable -> {
                    Thread thread = new Thread(runnable,
                            "nanofaas-lite-callback-" + CALLBACK_THREAD_COUNTER.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static int setting(String property, String environment, int fallback) {
        String value = System.getProperty(property, System.getenv(environment));
        if (value == null || value.isBlank()) return fallback;
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException _) {
            return fallback;
        }
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        if (!isAccepting()) {
            sendStopping(exchange);
            return;
        }

        String headerExecutionId = exchange.getRequestHeaders().getFirst("X-Execution-Id");
        String traceId = exchange.getRequestHeaders().getFirst("X-Trace-Id");
        String dispatchAttempt = exchange.getRequestHeaders().getFirst("X-Dispatch-Attempt");

        String effectiveExecutionId = (headerExecutionId != null && !headerExecutionId.isBlank())
                ? headerExecutionId
                : envExecutionId;

        if (effectiveExecutionId == null || effectiveExecutionId.isBlank()) {
            log.error("No execution ID provided (header or ENV)");
            sendJson(exchange, 400, Map.of(ERROR_KEY, "Execution ID not configured"));
            return;
        }

        if (limits.handlerCapacityExhausted()) {
            sendRetryable(exchange, "RUNTIME_HANDLER_SATURATED", "Runtime handler capacity exhausted");
            return;
        }
        RuntimeLimits.Reservation callbackReservation = limits.tryReserveCallback();
        if (callbackReservation == null) {
            sendRetryable(exchange, "RUNTIME_CALLBACK_SATURATED", "Runtime callback capacity exhausted");
            return;
        }

        boolean isColdStart = firstInvocation.compareAndSet(true, false);
        long initDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - containerStartNanos);
        if (isColdStart) {
            metrics.recordColdStart(functionName);
        }

        metrics.incInFlight(functionName);
        long startNanos = System.nanoTime();

        FunctionContext.set(effectiveExecutionId, traceId);
        try {
            ReadRequestResult readResult = readRequest(
                    exchange, callbackReservation, effectiveExecutionId, traceId, dispatchAttempt);
            if (readResult.reservationConsumed()) callbackReservation = null;
            if (readResult.request() == null) {
                return;
            }
            Object output = invokeWithTimeout(readResult.request());

            final byte[] outputBody;
            try {
                outputBody = boundedJson.serialize(output, limits.maxOutputBytes);
            } catch (BoundedJson.PayloadTooLargeException _) {
                metrics.recordInvocation(functionName);
                metrics.recordError(functionName);
                dispatchCallback(callbackReservation, effectiveExecutionId,
                        InvocationResult.error("RUNTIME_OUTPUT_TOO_LARGE",
                                "Runtime output exceeds configured byte limit"), traceId, dispatchAttempt);
                callbackReservation = null;
                sendJson(exchange, 500, Map.of(ERROR_KEY, Map.of(
                        "code", "RUNTIME_OUTPUT_TOO_LARGE",
                        "message", "Runtime output exceeds configured byte limit")));
                return;
            } catch (BoundedJson.SerializationException ex) {
                throw ex;
            }

            metrics.recordInvocation(functionName);

            CallbackHandoff handoff = dispatchCallback(callbackReservation, effectiveExecutionId,
                    InvocationResult.success(objectMapper.readTree(outputBody)), traceId, dispatchAttempt);
            callbackReservation = null;
            if (handoff != CallbackHandoff.ACCEPTED) {
                sendCallbackHandoffFailure(exchange, handoff);
                return;
            }

            if (isColdStart) {
                exchange.getResponseHeaders().set("X-Cold-Start", "true");
                exchange.getResponseHeaders().set("X-Init-Duration-Ms", String.valueOf(initDurationMs));
            }

            sendJsonBytes(exchange, 200, outputBody);
        } catch (TimeoutException _) {
            metrics.recordInvocation(functionName);
            metrics.recordError(functionName);
            dispatchCallback(callbackReservation, effectiveExecutionId,
                    InvocationResult.error("HANDLER_TIMEOUT", "Handler exceeded configured timeout"),
                    traceId, dispatchAttempt);
            callbackReservation = null;
            sendJson(exchange, 504, Map.of(
                    ERROR_KEY, Map.of("code", "HANDLER_TIMEOUT", "message", "Handler exceeded configured timeout")));
        } catch (InterruptedException _) {
            metrics.recordInvocation(functionName);
            metrics.recordError(functionName);
            dispatchCallback(callbackReservation, effectiveExecutionId,
                    InvocationResult.error("INVOCATION_CANCELLED", "Invocation cancelled"),
                    traceId, dispatchAttempt);
            callbackReservation = null;
            exchange.close();
            Thread.currentThread().interrupt();
        } catch (RuntimeStoppingException _) {
            sendStopping(exchange);
        } catch (HandlerSaturatedException _) {
            sendRetryable(exchange, "RUNTIME_HANDLER_SATURATED", "Runtime handler capacity exhausted");
        } catch (Exception ex) {
            handleHandlerFailure(ex, exchange, callbackReservation,
                    effectiveExecutionId, traceId, dispatchAttempt);
            callbackReservation = null;
        } finally {
            if (callbackReservation != null) callbackReservation.close();
            metrics.observeDuration(functionName, (System.nanoTime() - startNanos) / 1_000_000_000.0);
            metrics.decInFlight(functionName);
            FunctionContext.clear();
        }
    }

    private void handleHandlerFailure(Exception ex, HttpExchange exchange,
                                      RuntimeLimits.Reservation callbackReservation, String effectiveExecutionId,
                                      String traceId, String dispatchAttempt) throws IOException {
        log.error("Handler error for execution {}: {}", effectiveExecutionId, ex.getMessage(), ex);
        metrics.recordInvocation(functionName);
        metrics.recordError(functionName);

        dispatchCallback(callbackReservation, effectiveExecutionId,
                InvocationResult.error("HANDLER_ERROR", ex.getMessage()), traceId, dispatchAttempt);

        sendJson(exchange, 500, Map.of(ERROR_KEY, Map.of("code", "HANDLER_ERROR",
                "message", ex.getMessage() != null ? ex.getMessage() : "Internal error")));
    }

    private ReadRequestResult readRequest(HttpExchange exchange, RuntimeLimits.Reservation callbackReservation,
                                          String executionId, String traceId,
                                          String dispatchAttempt) throws IOException {
        InputStream requestBody = exchange.getRequestBody();
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean timedOut = new AtomicBoolean();
        Thread deadline = Thread.ofVirtual().name("nanofaas-lite-body-deadline").start(() -> {
            try {
                if (!finished.await(limits.bodyReadTimeoutMs, TimeUnit.MILLISECONDS)) {
                    timedOut.set(true);
                    try { requestBody.close(); } catch (IOException _) { }
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        });
        try { // NOSONAR (java:S2093): readValue closes the source stream
            return new ReadRequestResult(objectMapper.readValue(
                    new LimitedInputStream(requestBody, limits.maxInputBytes), InvocationRequest.class), false);
        } catch (PayloadTooLargeException _) {
            sendJson(exchange, 413, Map.of(ERROR_KEY, Map.of(
                    "code", "RUNTIME_INPUT_TOO_LARGE",
                    "message", "Runtime input exceeds configured byte limit")));
            return new ReadRequestResult(null, false);
        } catch (JsonProcessingException _) {
            if (timedOut.get()) {
                sendJson(exchange, 408, Map.of(ERROR_KEY, Map.of(
                        "code", "RUNTIME_BODY_READ_TIMEOUT",
                        "message", "Runtime request body read timed out")));
                return new ReadRequestResult(null, false);
            }
            metrics.recordInvocation(functionName);
            metrics.recordError(functionName);
            dispatchCallback(callbackReservation, executionId,
                    InvocationResult.error("INVALID_JSON", "Request body must be valid JSON"),
                    traceId, dispatchAttempt);
            sendJson(exchange, 400, Map.of(
                    ERROR_KEY, Map.of("code", "INVALID_JSON", "message", "Request body must be valid JSON")));
            return new ReadRequestResult(null, true);
        } catch (IOException ex) {
            if (!timedOut.get()) throw ex;
            sendJson(exchange, 408, Map.of(ERROR_KEY, Map.of(
                    "code", "RUNTIME_BODY_READ_TIMEOUT",
                    "message", "Runtime request body read timed out")));
            return new ReadRequestResult(null, false);
        } finally {
            finished.countDown();
            deadline.interrupt();
        }
    }

    private Object invokeWithTimeout(InvocationRequest request) throws InterruptedException, TimeoutException {
        RuntimeLimits.Reservation handlerReservation = limits.tryReserveHandler();
        if (handlerReservation == null) throw new HandlerSaturatedException();
        FutureTask<Object> task = new FutureTask<>(() -> functionHandler.handle(request));
        AtomicReference<HandlerWork> workReference = new AtomicReference<>();
        Thread thread = Thread.ofVirtual().unstarted(() -> {
            try {
                task.run();
            } finally {
                synchronized (handlerLifecycle) {
                    activeHandlers.remove(workReference.get());
                    handlerLifecycle.notifyAll();
                }
                handlerReservation.close();
            }
        });
        HandlerWork work = new HandlerWork(task, thread);
        workReference.set(work);
        synchronized (handlerLifecycle) {
            if (!accepting) {
                handlerReservation.close();
                throw new RuntimeStoppingException();
            }
            activeHandlers.add(work);
        }
        thread.start();
        try {
            return task.get(handlerTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            task.cancel(true);
            throw ex;
        } catch (InterruptedException ex) {
            task.cancel(true);
            throw ex;
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof Exception cause) throw sneakyThrow(cause);
            throw new RuntimeException(ex.getCause());
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }

    private CallbackHandoff dispatchCallback(RuntimeLimits.Reservation reservation, String executionId,
                                              InvocationResult result, String traceId, String dispatchAttempt) {
        final byte[] callbackBody;
        try {
            callbackBody = boundedJson.serialize(
                    it.unimib.datai.nanofaas.sdk.lite.callback.CallbackPayload.from(result), limits.maxCallbackBytes);
        } catch (BoundedJson.PayloadTooLargeException _) {
            reservation.close();
            log.warn("Rejecting oversized callback for execution {}", executionId);
            return CallbackHandoff.PAYLOAD_TOO_LARGE;
        } catch (BoundedJson.SerializationException ex) {
            reservation.close();
            log.warn("Rejecting unserializable callback for execution {}", executionId, ex);
            return CallbackHandoff.SERIALIZATION_FAILED;
        }
        try {
            callbackExecutor.execute(new CallbackTask(
                    reservation, executionId, callbackBody, traceId, dispatchAttempt));
            return CallbackHandoff.ACCEPTED;
        } catch (RejectedExecutionException _) {
            reservation.close();
            log.warn("Rejecting callback for execution {} because dispatcher queue is full", executionId);
            return CallbackHandoff.SATURATED;
        }
    }

    public void shutdownCallbacks() {
        shutdown(Duration.ofSeconds(5));
    }

    public boolean shutdown(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        beginStop();
        synchronized (handlerLifecycle) {
            activeHandlers.forEach(HandlerWork::cancel);
            while (!activeHandlers.isEmpty()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(handlerLifecycle, remaining);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        boolean callbacksDrained = true;
        if (ownsCallbackExecutor) {
            callbackExecutor.shutdown();
            callbacksDrained = awaitCallbacks(deadline);
            if (!callbacksDrained) {
                releaseCancelledCallbacks(callbackExecutor.shutdownNow());
                callbacksDrained = awaitInterruptedCallbacks();
            }
        }
        synchronized (handlerLifecycle) {
            return activeHandlers.isEmpty() && callbacksDrained;
        }
    }

    public void beginStop() {
        limits.stopAdmission();
        synchronized (handlerLifecycle) {
            accepting = false;
        }
    }

    private boolean isAccepting() {
        synchronized (handlerLifecycle) {
            return accepting;
        }
    }

    private void sendStopping(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Retry-After", "1");
        sendJson(exchange, 503, Map.of(
                ERROR_KEY, Map.of("code", "RUNTIME_STOPPING", "message", "Runtime is stopping")));
    }

    private void sendRetryable(HttpExchange exchange, String code, String message) throws IOException {
        exchange.getResponseHeaders().set("Retry-After", "1");
        sendJson(exchange, 429, Map.of(ERROR_KEY, Map.of("code", code, "message", message)));
    }

    private boolean awaitCallbacks(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return callbackExecutor.isTerminated();
        }
        try {
            return callbackExecutor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean awaitInterruptedCallbacks() {
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limits.callbackAttemptTimeoutMs);
        try {
            while (!callbackExecutor.isTerminated()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                try {
                    if (callbackExecutor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) return true;
                } catch (InterruptedException _) {
                    interrupted = true;
                }
            }
            return true;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void releaseCancelledCallbacks(List<Runnable> cancelled) {
        cancelled.forEach(runnable -> {
            if (runnable instanceof CallbackTask task) task.release();
        });
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = objectMapper.writeValueAsBytes(body);
        sendJsonBytes(exchange, status, bytes);
    }

    private void sendJsonBytes(HttpExchange exchange, int status, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void sendCallbackHandoffFailure(HttpExchange exchange, CallbackHandoff handoff) throws IOException {
        if (handoff == CallbackHandoff.SATURATED) {
            exchange.getResponseHeaders().set("Retry-After", "1");
            sendJson(exchange, 503, Map.of(ERROR_KEY, Map.of(
                    "code", "RUNTIME_STOPPING", "message", "Runtime callback handoff failed")));
            return;
        }
        String code = handoff == CallbackHandoff.PAYLOAD_TOO_LARGE
                ? "RUNTIME_OUTPUT_TOO_LARGE"
                : "OUTPUT_SERIALIZATION_ERROR";
        sendJson(exchange, 500, Map.of(ERROR_KEY, Map.of(
                "code", code, "message", "Runtime callback handoff failed")));
    }

    private record HandlerWork(FutureTask<?> task, Thread thread) {
        private void cancel() {
            task.cancel(true);
            thread.interrupt();
        }
    }

    private record ReadRequestResult(InvocationRequest request, boolean reservationConsumed) { }

    private enum CallbackHandoff {
        ACCEPTED,
        PAYLOAD_TOO_LARGE,
        SERIALIZATION_FAILED,
        SATURATED
    }

    private final class CallbackTask implements Runnable {
        private final RuntimeLimits.Reservation reservation;
        private final String executionId;
        private final byte[] body;
        private final String traceId;
        private final String dispatchAttempt;
        private CallbackTask(RuntimeLimits.Reservation reservation, String executionId, byte[] body,
                             String traceId, String dispatchAttempt) {
            this.reservation = reservation;
            this.executionId = executionId;
            this.body = body;
            this.traceId = traceId;
            this.dispatchAttempt = dispatchAttempt;
        }
        @Override public void run() {
            try {
                if (!callbackClient.sendSerializedResult(executionId, body, traceId, dispatchAttempt)) {
                    metrics.recordCallbackFailure(functionName);
                }
            } finally {
                release();
            }
        }
        private void release() { reservation.close(); }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private final long limit;
        private long read;
        private LimitedInputStream(InputStream in, long limit) { super(in); this.limit = limit; }
        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0 && ++read > limit) throw new PayloadTooLargeException();
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = super.read(bytes, offset, (int) Math.min(length, limit - read + 1));
            if (count > 0 && (read += count) > limit) throw new PayloadTooLargeException();
            return count;
        }
    }

    private static final class PayloadTooLargeException extends IOException { }
    private static final class HandlerSaturatedException extends RuntimeException { }

    private static final class RuntimeStoppingException extends RuntimeException {
    }
}
