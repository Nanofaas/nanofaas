package it.unimib.datai.nanofaas.controlplane.api;

import it.unimib.datai.nanofaas.common.model.ExecutionStatus;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionNotFoundException;
import it.unimib.datai.nanofaas.controlplane.service.AsyncQueueUnavailableException;
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
import reactor.core.scheduler.Schedulers;

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
        // defer: a synchronously thrown service exception must flow through onErrorResume
        return Mono.defer(() -> invocationService.invokeSyncReactive(name, request, idempotencyKey, traceId, timeoutMs))
                .map(response -> ResponseEntity.ok()
                        .header("X-Execution-Id", response.executionId())
                        .body(response))
                .onErrorResume(FunctionNotFoundException.class, ex ->
                        Mono.just(ResponseEntity.notFound().<InvocationResponse>build()))
                .onErrorResume(SyncQueueRejectedException.class, ex ->
                        Mono.just(tooManyRequests(ex)))
                .onErrorResume(RateLimitException.class, ex ->
                        Mono.just(tooManyRequests()))
                .onErrorResume(QueueFullException.class, ex ->
                        Mono.just(tooManyRequests()));
    }

    @PostMapping("/functions/{name}:enqueue")
    public Mono<ResponseEntity<InvocationResponse>> invokeAsync(
            @PathVariable @NotBlank(message = "Function name is required") String name,
            @RequestBody @Valid InvocationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        return Mono.fromCallable(() -> invocationService.invokeAsync(name, request, idempotencyKey, traceId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(response -> ResponseEntity.status(HttpStatus.ACCEPTED).body(response))
                .onErrorResume(FunctionNotFoundException.class, ex ->
                        Mono.just(ResponseEntity.notFound().<InvocationResponse>build()))
                .onErrorResume(AsyncQueueUnavailableException.class, ex ->
                        Mono.just(ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).<InvocationResponse>build()))
                .onErrorResume(RateLimitException.class, ex ->
                        Mono.just(tooManyRequests()))
                .onErrorResume(QueueFullException.class, ex ->
                        Mono.just(tooManyRequests()));
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
            @RequestHeader(value = "X-Dispatch-Attempt", required = false) String dispatchAttemptHeader,
            @RequestBody @Valid InvocationResult result) {
        Integer dispatchAttempt = parseDispatchAttempt(dispatchAttemptHeader);
        if (dispatchAttempt != null) {
            invocationService.completeExecution(executionId, result, dispatchAttempt);
        } else {
            invocationService.completeExecution(executionId, result);
        }
        return ResponseEntity.noContent().build();
    }

    private static Integer parseDispatchAttempt(String dispatchAttemptHeader) {
        if (dispatchAttemptHeader == null || dispatchAttemptHeader.isBlank()) {
            return null;
        }
        try {
            int dispatchAttempt = Integer.parseInt(dispatchAttemptHeader);
            return dispatchAttempt > 0 ? dispatchAttempt : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static ResponseEntity<InvocationResponse> tooManyRequests() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
    }

    private static ResponseEntity<InvocationResponse> tooManyRequests(SyncQueueRejectedException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.retryAfterSeconds()))
                .header("X-Queue-Reject-Reason", ex.reason().name().toLowerCase())
                .build();
    }
}
