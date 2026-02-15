package it.unimib.datai.nanofaas.sdk.lite.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class InvokeHandler implements HttpHandler {
    private static final Logger log = LoggerFactory.getLogger(InvokeHandler.class);
    private static final Instant CONTAINER_START = Instant.now();
    private static final AtomicBoolean FIRST_INVOCATION = new AtomicBoolean(true);

    private static final ExecutorService CALLBACK_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final FunctionHandler functionHandler;
    private final CallbackClient callbackClient;
    private final RuntimeMetrics metrics;
    private final ObjectMapper objectMapper;
    private final String functionName;
    private final String envExecutionId;

    public InvokeHandler(FunctionHandler functionHandler, CallbackClient callbackClient,
                         RuntimeMetrics metrics, ObjectMapper objectMapper, String functionName) {
        this.functionHandler = functionHandler;
        this.callbackClient = callbackClient;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.functionName = functionName;
        this.envExecutionId = System.getenv("EXECUTION_ID");
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

        String effectiveExecutionId = (headerExecutionId != null && !headerExecutionId.isBlank())
                ? headerExecutionId
                : envExecutionId;

        if (effectiveExecutionId == null || effectiveExecutionId.isBlank()) {
            log.error("No execution ID provided (header or ENV)");
            sendJson(exchange, 400, Map.of("error", "Execution ID not configured"));
            return;
        }

        boolean isColdStart = FIRST_INVOCATION.compareAndSet(true, false);
        if (isColdStart) {
            metrics.recordColdStart(functionName);
        }

        metrics.incInFlight(functionName);
        long startNanos = System.nanoTime();

        FunctionContext.set(effectiveExecutionId, traceId);
        try {
            InvocationRequest request = objectMapper.readValue(exchange.getRequestBody(), InvocationRequest.class);
            Object output = functionHandler.handle(request);

            metrics.recordInvocation(functionName);
            double durationSec = (System.nanoTime() - startNanos) / 1_000_000_000.0;
            metrics.observeDuration(functionName, durationSec);

            // Fire-and-forget: callback must not block the response to the control plane
            final String cbExecId = effectiveExecutionId;
            final String cbTraceId = traceId;
            final InvocationResult cbResult = InvocationResult.success(output);
            CALLBACK_EXECUTOR.submit(() -> callbackClient.sendResult(cbExecId, cbResult, cbTraceId));

            if (isColdStart) {
                long initDurationMs = Instant.now().toEpochMilli() - CONTAINER_START.toEpochMilli();
                exchange.getResponseHeaders().set("X-Cold-Start", "true");
                exchange.getResponseHeaders().set("X-Init-Duration-Ms", String.valueOf(initDurationMs));
            }

            sendJson(exchange, 200, output);
        } catch (Exception ex) {
            log.error("Handler error for execution {}: {}", effectiveExecutionId, ex.getMessage(), ex);
            metrics.recordInvocation(functionName);
            metrics.recordError(functionName);
            double durationSec = (System.nanoTime() - startNanos) / 1_000_000_000.0;
            metrics.observeDuration(functionName, durationSec);

            final String cbExecId = effectiveExecutionId;
            final String cbTraceId = traceId;
            final InvocationResult cbResult = InvocationResult.error("HANDLER_ERROR", ex.getMessage());
            CALLBACK_EXECUTOR.submit(() -> callbackClient.sendResult(cbExecId, cbResult, cbTraceId));

            sendJson(exchange, 500, Map.of("error", ex.getMessage() != null ? ex.getMessage() : "Internal error"));
        } finally {
            metrics.decInFlight(functionName);
            FunctionContext.clear();
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = objectMapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
