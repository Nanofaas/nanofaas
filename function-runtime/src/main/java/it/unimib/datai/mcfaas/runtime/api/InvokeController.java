package com.mcfaas.runtime.api;

import com.mcfaas.common.model.InvocationRequest;
import com.mcfaas.common.model.InvocationResult;
import com.mcfaas.common.runtime.FunctionHandler;
import com.mcfaas.runtime.core.CallbackClient;
import com.mcfaas.runtime.core.HandlerRegistry;
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
    public ResponseEntity<Object> invoke(@RequestBody InvocationRequest request) {
        if (executionId == null || executionId.isBlank()) {
            log.error("EXECUTION_ID environment variable not set");
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "EXECUTION_ID not configured"));
        }

        try {
            FunctionHandler handler = handlerRegistry.resolve();
            Object output = handler.handle(request);

            // Callback is best-effort - don't fail the response if callback fails
            boolean callbackSent = callbackClient.sendResult(executionId, InvocationResult.success(output));
            if (!callbackSent) {
                log.warn("Callback failed for execution {} but function succeeded, returning result anyway", executionId);
            }

            return ResponseEntity.ok(output);
        } catch (Exception ex) {
            log.error("Handler error for execution {}: {}", executionId, ex.getMessage(), ex);

            // Try to send error to control-plane (best effort)
            callbackClient.sendResult(executionId, InvocationResult.error("HANDLER_ERROR", ex.getMessage()));

            return ResponseEntity.status(500)
                    .body(Map.of("error", ex.getMessage()));
        }
    }
}
