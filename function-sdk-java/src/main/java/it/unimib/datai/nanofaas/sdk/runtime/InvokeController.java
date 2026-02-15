package it.unimib.datai.nanofaas.sdk.runtime;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
public class InvokeController {
    private static final Logger log = LoggerFactory.getLogger(InvokeController.class);
    private static final AtomicBoolean FIRST_INVOCATION = new AtomicBoolean(true);
    private static final Instant CONTAINER_START = Instant.now();
    private static final ExecutorService CALLBACK_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final CallbackClient callbackClient;
    private final HandlerRegistry handlerRegistry;
    private final String executionId;

    public InvokeController(CallbackClient callbackClient, HandlerRegistry handlerRegistry,
                           @Value("${EXECUTION_ID:#{systemEnvironment['EXECUTION_ID'] ?: 'test-execution'}}") String executionId) {
        this.callbackClient = callbackClient;
        this.handlerRegistry = handlerRegistry;
        this.executionId = executionId;
    }

    @PostMapping("/invoke")
    public ResponseEntity<Object> invoke(
            @RequestBody InvocationRequest request,
            @RequestHeader(value = "X-Execution-Id", required = false) String headerExecutionId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {

        // Prefer header over ENV (for warm mode)
        String effectiveExecutionId = (headerExecutionId != null && !headerExecutionId.isBlank())
                ? headerExecutionId
                : this.executionId;

        if (effectiveExecutionId == null || effectiveExecutionId.isBlank()) {
            log.error("No execution ID provided (header or ENV)");
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Execution ID not configured"));
        }

        boolean isColdStart = FIRST_INVOCATION.compareAndSet(true, false);

        try {
            FunctionHandler handler = handlerRegistry.resolve();
            Object output = handler.handle(request);

            // Fire-and-forget: callback must not block the response
            final String cbExecId = effectiveExecutionId;
            final String cbTraceId = traceId;
            final InvocationResult cbResult = InvocationResult.success(output);
            CALLBACK_EXECUTOR.submit(() -> callbackClient.sendResult(cbExecId, cbResult, cbTraceId));

            ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.ok();
            if (isColdStart) {
                long initDurationMs = Instant.now().toEpochMilli() - CONTAINER_START.toEpochMilli();
                responseBuilder.header("X-Cold-Start", "true");
                responseBuilder.header("X-Init-Duration-Ms", String.valueOf(initDurationMs));
            }
            return responseBuilder.body(output);
        } catch (Exception ex) {
            log.error("Handler error for execution {}: {}", effectiveExecutionId, ex.getMessage(), ex);

            // Fire-and-forget: error callback must not block the error response
            final String cbExecId = effectiveExecutionId;
            final String cbTraceId = traceId;
            final InvocationResult cbResult = InvocationResult.error("HANDLER_ERROR", ex.getMessage());
            CALLBACK_EXECUTOR.submit(() -> callbackClient.sendResult(cbExecId, cbResult, cbTraceId));

            return ResponseEntity.status(500)
                    .body(Map.of("error", ex.getMessage()));
        }
    }
}
