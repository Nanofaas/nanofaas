package com.mcfaas.controlplane.api;

import com.mcfaas.common.model.ExecutionStatus;
import com.mcfaas.common.model.InvocationRequest;
import com.mcfaas.common.model.InvocationResponse;
import com.mcfaas.common.model.InvocationResult;
import com.mcfaas.controlplane.registry.FunctionNotFoundException;
import com.mcfaas.controlplane.service.InvocationService;
import com.mcfaas.controlplane.queue.QueueFullException;
import com.mcfaas.controlplane.service.RateLimitException;
import com.mcfaas.controlplane.sync.SyncQueueRejectedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1")
@Validated
public class InvocationController {
    private final InvocationService invocationService;

    public InvocationController(InvocationService invocationService) {
        this.invocationService = invocationService;
    }

    @PostMapping("/functions/{name}:invoke")
    public ResponseEntity<InvocationResponse> invokeSync(
            @PathVariable @NotBlank(message = "Function name is required") String name,
            @RequestBody @Valid InvocationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
            @RequestHeader(value = "X-Timeout-Ms", required = false) Integer timeoutMs) throws InterruptedException {
        try {
            InvocationResponse response = invocationService.invokeSync(name, request, idempotencyKey, traceId, timeoutMs);
            return ResponseEntity.ok()
                    .header("X-Execution-Id", response.executionId())
                    .body(response);
        } catch (FunctionNotFoundException ex) {
            return ResponseEntity.notFound().build();
        } catch (SyncQueueRejectedException ex) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(ex.retryAfterSeconds()))
                    .header("X-Queue-Reject-Reason", ex.reason().name().toLowerCase())
                    .build();
        } catch (RateLimitException | QueueFullException ex) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
    }

    @PostMapping("/functions/{name}:enqueue")
    public ResponseEntity<InvocationResponse> invokeAsync(
            @PathVariable @NotBlank(message = "Function name is required") String name,
            @RequestBody @Valid InvocationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        try {
            InvocationResponse response = invocationService.invokeAsync(name, request, idempotencyKey, traceId);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        } catch (FunctionNotFoundException ex) {
            return ResponseEntity.notFound().build();
        } catch (RateLimitException | QueueFullException ex) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
    }

    @GetMapping("/executions/{executionId}")
    public ResponseEntity<ExecutionStatus> getExecution(
            @PathVariable @NotBlank(message = "Execution ID is required") String executionId) {
        return invocationService.getStatus(executionId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/internal/executions/{executionId}:complete")
    public ResponseEntity<Void> completeExecution(
            @PathVariable @NotBlank(message = "Execution ID is required") String executionId,
            @RequestBody @Valid InvocationResult result) {
        invocationService.completeExecution(executionId, result);
        return ResponseEntity.noContent().build();
    }
}
