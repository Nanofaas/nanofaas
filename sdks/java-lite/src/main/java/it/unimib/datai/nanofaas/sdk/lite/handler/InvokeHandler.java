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
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class InvokeHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(InvokeHandler.class);
    private static final String ERROR_KEY = "error";
    private final FunctionHandler functionHandler;
    private final CallbackClient callbackClient;
    private final RuntimeMetrics metrics;
    private final ObjectMapper objectMapper;
    private final String functionName;
    private final String envExecutionId;
    private final ThreadPoolExecutor callbackExecutor;
    private final long handlerTimeoutMs;
    private final long containerStartNanos = System.nanoTime();
    private final AtomicBoolean firstInvocation = new AtomicBoolean(true);

    public InvokeHandler(FunctionHandler functionHandler, CallbackClient callbackClient,
                         RuntimeMetrics metrics, ObjectMapper objectMapper, String functionName) {
        this(functionHandler, callbackClient, metrics, objectMapper, functionName,
                newCallbackExecutor(), setting("nanofaas.handler.timeout.ms", "NANOFAAS_HANDLER_TIMEOUT", 30_000));
    }

    InvokeHandler(FunctionHandler functionHandler, CallbackClient callbackClient,
                  RuntimeMetrics metrics, ObjectMapper objectMapper, String functionName,
                  ThreadPoolExecutor callbackExecutor, long handlerTimeoutMs) {
        this.functionHandler = functionHandler;
        this.callbackClient = callbackClient;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.functionName = functionName;
        this.envExecutionId = System.getenv("EXECUTION_ID");
        this.callbackExecutor = callbackExecutor;
        this.handlerTimeoutMs = handlerTimeoutMs;
    }

    private static ThreadPoolExecutor newCallbackExecutor() {
        int workers = setting("nanofaas.callback.worker.count", "NANOFAAS_CALLBACK_WORKER_COUNT", 2);
        int capacity = setting("nanofaas.callback.queue.capacity", "NANOFAAS_CALLBACK_QUEUE_CAPACITY", 128);
        return new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), Thread.ofPlatform().daemon().factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static int setting(String property, String environment, int fallback) {
        String value = System.getProperty(property, System.getenv(environment));
        if (value == null || value.isBlank()) return fallback;
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
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
            InvocationRequest request;
            try {
                request = objectMapper.readValue(exchange.getRequestBody(), InvocationRequest.class);
            } catch (JsonProcessingException ex) {
                metrics.recordInvocation(functionName);
                metrics.recordError(functionName);
                dispatchCallback(effectiveExecutionId,
                        InvocationResult.error("INVALID_JSON", "Request body must be valid JSON"),
                        traceId, dispatchAttempt);
                sendJson(exchange, 400, Map.of(
                        ERROR_KEY, Map.of("code", "INVALID_JSON", "message", "Request body must be valid JSON")));
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
        } catch (TimeoutException ex) {
            metrics.recordInvocation(functionName);
            metrics.recordError(functionName);
            dispatchCallback(effectiveExecutionId,
                    InvocationResult.error("HANDLER_TIMEOUT", "Handler exceeded configured timeout"),
                    traceId, dispatchAttempt);
            sendJson(exchange, 504, Map.of(
                    ERROR_KEY, Map.of("code", "HANDLER_TIMEOUT", "message", "Handler exceeded configured timeout")));
        } catch (Exception ex) {
            log.error("Handler error for execution {}: {}", effectiveExecutionId, ex.getMessage(), ex);
            metrics.recordInvocation(functionName);
            metrics.recordError(functionName);

            dispatchCallback(effectiveExecutionId,
                    InvocationResult.error("HANDLER_ERROR", ex.getMessage()), traceId, dispatchAttempt);

            sendJson(exchange, 500, Map.of(ERROR_KEY, ex.getMessage() != null ? ex.getMessage() : "Internal error"));
        } finally {
            metrics.observeDuration(functionName, (System.nanoTime() - startNanos) / 1_000_000_000.0);
            metrics.decInFlight(functionName);
            FunctionContext.clear();
        }
    }

    private Object invokeWithTimeout(InvocationRequest request) throws InterruptedException, TimeoutException {
        FutureTask<Object> task = new FutureTask<>(() -> functionHandler.handle(request));
        Thread.ofVirtual().start(task);
        try {
            return task.get(handlerTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
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
        } catch (RejectedExecutionException ex) {
            log.warn("Dropping callback for execution {} because dispatcher queue is full", executionId);
        }
    }

    public void shutdownCallbacks() {
        callbackExecutor.shutdownNow();
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = objectMapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
