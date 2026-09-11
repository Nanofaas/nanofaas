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
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
    private final Object handlerLifecycle = new Object();
    private final Set<HandlerWork> activeHandlers = new HashSet<>();
    private boolean accepting = true;
    private final long containerStartNanos = System.nanoTime();
    private final AtomicBoolean firstInvocation = new AtomicBoolean(true);

    public InvokeHandler(FunctionHandler functionHandler, CallbackClient callbackClient,
                         RuntimeMetrics metrics, ObjectMapper objectMapper, String functionName) {
        this(functionHandler, callbackClient, metrics, objectMapper, functionName,
                newCallbackExecutor(), setting("nanofaas.handler.timeout.ms", "NANOFAAS_HANDLER_TIMEOUT", 30_000),
                true);
    }

    InvokeHandler(FunctionHandler functionHandler, CallbackClient callbackClient,
                  RuntimeMetrics metrics, ObjectMapper objectMapper, String functionName,
                  ThreadPoolExecutor callbackExecutor, long handlerTimeoutMs) {
        this(functionHandler, callbackClient, metrics, objectMapper, functionName,
                callbackExecutor, handlerTimeoutMs, false);
    }

    private InvokeHandler(FunctionHandler functionHandler, CallbackClient callbackClient,
                          RuntimeMetrics metrics, ObjectMapper objectMapper, String functionName,
                          ThreadPoolExecutor callbackExecutor, long handlerTimeoutMs,
                          boolean ownsCallbackExecutor) {
        this.functionHandler = functionHandler;
        this.callbackClient = callbackClient;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.functionName = functionName;
        this.envExecutionId = System.getenv("EXECUTION_ID");
        this.callbackExecutor = callbackExecutor;
        this.ownsCallbackExecutor = ownsCallbackExecutor;
        this.handlerTimeoutMs = handlerTimeoutMs;
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

        boolean isColdStart = firstInvocation.compareAndSet(true, false);
        long initDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - containerStartNanos);
        if (isColdStart) {
            metrics.recordColdStart(functionName);
        }

        metrics.incInFlight(functionName);
        long startNanos = System.nanoTime();

        FunctionContext.set(effectiveExecutionId, traceId);
        try {
            InvocationRequest request = readRequest(exchange, effectiveExecutionId, traceId, dispatchAttempt);
            if (request == null) {
                return;
            }
            Object output = invokeWithTimeout(request);

            metrics.recordInvocation(functionName);

            // Fire-and-forget: callback must not block the response to the control plane
            dispatchCallback(effectiveExecutionId, InvocationResult.success(output), traceId, dispatchAttempt);

            if (isColdStart) {
                exchange.getResponseHeaders().set("X-Cold-Start", "true");
                exchange.getResponseHeaders().set("X-Init-Duration-Ms", String.valueOf(initDurationMs));
            }

            sendJson(exchange, 200, output);
        } catch (TimeoutException _) {
            metrics.recordInvocation(functionName);
            metrics.recordError(functionName);
            dispatchCallback(effectiveExecutionId,
                    InvocationResult.error("HANDLER_TIMEOUT", "Handler exceeded configured timeout"),
                    traceId, dispatchAttempt);
            sendJson(exchange, 504, Map.of(
                    ERROR_KEY, Map.of("code", "HANDLER_TIMEOUT", "message", "Handler exceeded configured timeout")));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            handleHandlerFailure(ex, exchange, effectiveExecutionId, traceId, dispatchAttempt);
        } catch (RuntimeStoppingException _) {
            sendStopping(exchange);
        } catch (Exception ex) {
            handleHandlerFailure(ex, exchange, effectiveExecutionId, traceId, dispatchAttempt);
        } finally {
            metrics.observeDuration(functionName, (System.nanoTime() - startNanos) / 1_000_000_000.0);
            metrics.decInFlight(functionName);
            FunctionContext.clear();
        }
    }

    private void handleHandlerFailure(Exception ex, HttpExchange exchange, String effectiveExecutionId,
                                      String traceId, String dispatchAttempt) throws IOException {
        log.error("Handler error for execution {}: {}", effectiveExecutionId, ex.getMessage(), ex);
        metrics.recordInvocation(functionName);
        metrics.recordError(functionName);

        dispatchCallback(effectiveExecutionId,
                InvocationResult.error("HANDLER_ERROR", ex.getMessage()), traceId, dispatchAttempt);

        sendJson(exchange, 500, Map.of(ERROR_KEY, ex.getMessage() != null ? ex.getMessage() : "Internal error"));
    }

    private InvocationRequest readRequest(HttpExchange exchange, String executionId, String traceId,
                                          String dispatchAttempt) throws IOException {
        try {
            return objectMapper.readValue(exchange.getRequestBody(), InvocationRequest.class);
        } catch (JsonProcessingException _) {
            metrics.recordInvocation(functionName);
            metrics.recordError(functionName);
            dispatchCallback(executionId,
                    InvocationResult.error("INVALID_JSON", "Request body must be valid JSON"),
                    traceId, dispatchAttempt);
            sendJson(exchange, 400, Map.of(
                    ERROR_KEY, Map.of("code", "INVALID_JSON", "message", "Request body must be valid JSON")));
            return null;
        }
    }

    private Object invokeWithTimeout(InvocationRequest request) throws InterruptedException, TimeoutException {
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
            }
        });
        HandlerWork work = new HandlerWork(task, thread);
        workReference.set(work);
        synchronized (handlerLifecycle) {
            if (!accepting) {
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

    private void dispatchCallback(String executionId, InvocationResult result,
                                  String traceId, String dispatchAttempt) {
        try {
            callbackExecutor.execute(
                    () -> callbackClient.sendResult(executionId, result, traceId, dispatchAttempt));
        } catch (RejectedExecutionException _) {
            log.warn("Dropping callback for execution {} because dispatcher queue is full", executionId);
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
                callbackExecutor.shutdownNow();
            }
        }
        synchronized (handlerLifecycle) {
            return activeHandlers.isEmpty() && callbacksDrained;
        }
    }

    public void beginStop() {
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

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = objectMapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record HandlerWork(FutureTask<?> task, Thread thread) {
        private void cancel() {
            task.cancel(true);
            thread.interrupt();
        }
    }

    private static final class RuntimeStoppingException extends RuntimeException {
    }
}
