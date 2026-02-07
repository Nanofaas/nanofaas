package it.unimib.datai.nanofaas.sdk.runtime;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class InvokeController {
    private static final Logger log = LoggerFactory.getLogger(InvokeController.class);

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

        try {
            FunctionHandler handler = handlerRegistry.resolve();
            Object output = handler.handle(request);

            // Callback is best-effort - don't fail the response if callback fails
            boolean callbackSent = callbackClient.sendResult(effectiveExecutionId, InvocationResult.success(output), traceId);
            if (!callbackSent) {
                log.warn("Callback failed for execution {} but function succeeded, returning result anyway", effectiveExecutionId);
            }

            return ResponseEntity.ok(output);
        } catch (Exception ex) {
            log.error("Handler error for execution {}: {}", effectiveExecutionId, ex.getMessage(), ex);

            // Try to send error to control-plane (best effort)
            callbackClient.sendResult(effectiveExecutionId, InvocationResult.error("HANDLER_ERROR", ex.getMessage()), traceId);

            return ResponseEntity.status(500)
                    .body(Map.of("error", ex.getMessage()));
        }
    }
}
