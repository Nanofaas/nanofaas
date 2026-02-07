package it.unimib.datai.nanofaas.controlplane.api;

import it.unimib.datai.nanofaas.common.model.ExecutionStatus;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionNotFoundException;
import it.unimib.datai.nanofaas.controlplane.service.InvocationService;
import it.unimib.datai.nanofaas.controlplane.queue.QueueFullException;
import it.unimib.datai.nanofaas.controlplane.service.RateLimitException;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/v1")
@Validated
public class InvocationController {
    private final InvocationService invocationService;

    public InvocationController(InvocationService invocationService) {
        this.invocationService = invocationService;
    }

    @PostMapping("/functions/{name}:invoke")
    public Mono<ResponseEntity<InvocationResponse>> invokeSync(
            @PathVariable @NotBlank(message = "Function name is required") String name,
            @RequestBody @Valid InvocationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
            @RequestHeader(value = "X-Timeout-Ms", required = false) Integer timeoutMs) {
        try {
            return invocationService.invokeSyncReactive(name, request, idempotencyKey, traceId, timeoutMs)
                    .map(response -> ResponseEntity.ok()
                            .header("X-Execution-Id", response.executionId())
                            .body(response))
                    .onErrorResume(SyncQueueRejectedException.class, ex ->
                            Mono.just(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                                    .header("Retry-After", String.valueOf(ex.retryAfterSeconds()))
                                    .header("X-Queue-Reject-Reason", ex.reason().name().toLowerCase())
                                    .build()));
        } catch (FunctionNotFoundException ex) {
            return Mono.just(ResponseEntity.notFound().build());
        } catch (SyncQueueRejectedException ex) {
            return Mono.just(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(ex.retryAfterSeconds()))
                    .header("X-Queue-Reject-Reason", ex.reason().name().toLowerCase())
                    .build());
        } catch (RateLimitException | QueueFullException ex) {
            return Mono.just(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build());
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
