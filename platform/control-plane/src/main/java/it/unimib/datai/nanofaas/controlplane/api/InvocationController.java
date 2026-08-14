package it.unimib.datai.nanofaas.controlplane.api;

import it.unimib.datai.nanofaas.common.model.ExecutionStatus;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.common.runtime.ResponseHeaderPolicy;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadContext;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadFailedException;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionNotFoundException;
import it.unimib.datai.nanofaas.controlplane.service.AsyncQueueUnavailableException;
import it.unimib.datai.nanofaas.controlplane.service.InvocationService;
import it.unimib.datai.nanofaas.controlplane.service.SyncInvocation;
import it.unimib.datai.nanofaas.controlplane.queue.QueueFullException;
import it.unimib.datai.nanofaas.controlplane.service.RateLimitException;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/v1")
@Validated
public class InvocationController {

    private static final Logger log = LoggerFactory.getLogger(InvocationController.class);

    /**
     * Hop-by-hop headers plus every header the control plane already binds to a dedicated
     * parameter. Anything left is handler-visible.
     */
    private static final Set<String> EXCLUDED_REQUEST_HEADERS = Set.of(
            "connection", "transfer-encoding", "keep-alive", "host", "content-length",
            "x-execution-id", "x-trace-id", "x-dispatch-attempt", "x-timeout-ms",
            "x-nanofaas-offload-hop", "idempotency-key", "traceparent", "tracestate");

    private final InvocationService invocationService;

    public InvocationController(InvocationService invocationService) {
        this.invocationService = invocationService;
    }

    /**
     * Rebuild the request with the caller's real headers, lower-cased. The body's own
     * {@code headers} field is overwritten, never merged: a caller must not be able to
     * forge a header it did not send.
     */
    private static InvocationRequest withCallerHeaders(InvocationRequest request,
                                                         Map<String, String> rawHeaders) {
        Map<String, String> filtered = new LinkedHashMap<>();
        rawHeaders.forEach((name, value) -> {
            String key = name.toLowerCase(Locale.ROOT);
            if (!EXCLUDED_REQUEST_HEADERS.contains(key)) {
                filtered.put(key, value);
            }
        });
        return new InvocationRequest(request.input(), request.metadata(), filtered);
    }

    @PostMapping("/functions/{name}:invoke")
    public Mono<ResponseEntity<InvocationResponse>> invokeSync(
            @PathVariable @NotBlank(message = "Function name is required") String name,
            @RequestBody @Valid InvocationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
            @RequestHeader(value = "X-Timeout-Ms", required = false) Integer timeoutMs,
            @RequestHeader(value = "X-NanoFaaS-Offload-Hop", required = false) String offloadHop,
            @RequestHeader(value = "traceparent", required = false) String traceparent,
            @RequestHeader(value = "tracestate", required = false) String tracestate,
            @RequestHeader Map<String, String> allHeaders) {
        OffloadContext offloadContext = new OffloadContext(offloadHop != null, traceparent, tracestate);
        InvocationRequest requestWithHeaders = withCallerHeaders(request, allHeaders);
        // defer: a synchronously thrown service exception must flow through onErrorResume
        return Mono.defer(() -> invocationService.invokeSyncReactive(name, requestWithHeaders, idempotencyKey, traceId, timeoutMs, offloadContext))
                .map(InvocationController::toResponse)
                .onErrorResume(FunctionNotFoundException.class, ex ->
                        Mono.just(ResponseEntity.notFound().<InvocationResponse>build()))
                .onErrorResume(SyncQueueRejectedException.class, ex ->
                        Mono.just(tooManyRequests(ex)))
                .onErrorResume(RateLimitException.class, ex ->
                        Mono.just(tooManyRequests()))
                .onErrorResume(QueueFullException.class, ex ->
                        Mono.just(tooManyRequests()))
                .onErrorResume(OffloadFailedException.class, ex ->
                        Mono.just(offloadFailed(ex)));
    }

    private static ResponseEntity<InvocationResponse> toResponse(SyncInvocation invocation) {
        InvocationResponse response = invocation.response();
        Integer statusCode = response.statusCode();
        // ponytail: statusCode is already validated upstream (ExternalDispatcher via
        // ResponseHeaderPolicy.isStatusCodeValid) before it ever reaches an
        // InvocationResponse, so this range check is defense-in-depth, not the
        // primary guard. Spring's ResponseEntity.status(int) does not throw for any
        // 100-999 value, so this isn't about preventing a crash — it enforces the
        // project's [200,599] contract and stops a genuinely malformed value
        // (negative, >999) from reaching the HTTP layer as a real status.
        boolean functionDecided = statusCode != null && ResponseHeaderPolicy.isStatusCodeValid(statusCode);
        if (statusCode != null && !functionDecided) {
            log.warn("Execution {} returned out-of-range status code {} (expected [200,599]); "
                    + "falling back to 200", response.executionId(), statusCode);
        }
        int status = functionDecided ? statusCode : 200;
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                .header("X-Execution-Id", response.executionId());
        if (functionDecided) {
            builder.header("X-NanoFaaS-Function-Status", "true");
            if (response.encoding() != null && !response.encoding().isBlank()) {
                builder.header("X-NanoFaaS-Encoding", response.encoding());
            }
        }
        if (response.headers() != null) {
            response.headers().forEach((headerName, headerValue) -> {
                // Content-Type would describe the handler's raw body, but the body
                // here is still the InvocationResponse envelope. Readable in the
                // payload's headers field, not applied to this hop.
                if (!"content-type".equalsIgnoreCase(headerName)) {
                    builder.header(headerName, headerValue);
                }
            });
        }
        if (invocation.offloadedTarget() != null) {
            builder.header("X-NanoFaaS-Offloaded", invocation.offloadedTarget());
        }
        return builder.body(response);
    }

    @PostMapping("/functions/{name}:enqueue")
    public Mono<ResponseEntity<InvocationResponse>> invokeAsync(
            @PathVariable @NotBlank(message = "Function name is required") String name,
            @RequestBody @Valid InvocationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
            @RequestHeader Map<String, String> allHeaders) {
        InvocationRequest requestWithHeaders = withCallerHeaders(request, allHeaders);
        return Mono.fromCallable(() -> invocationService.invokeAsync(name, requestWithHeaders, idempotencyKey, traceId))
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
        } catch (NumberFormatException _) {
            return null;
        }
    }

    private static ResponseEntity<InvocationResponse> tooManyRequests() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
    }

    private static ResponseEntity<InvocationResponse> tooManyRequests(SyncQueueRejectedException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.retryAfterSeconds()))
                // Locale.ROOT, not the default locale: TIMEOUT contains 'I', which folds to
                // dotless 'ı' under a Turkish/Azerbaijani default — this is a wire header value.
                .header("X-Queue-Reject-Reason", ex.reason().name().toLowerCase(Locale.ROOT))
                .build();
    }

    private static ResponseEntity<InvocationResponse> offloadFailed(OffloadFailedException ex) {
        HttpStatus status = ex.gatewayTimeout() ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (ex.targetUrl() != null) {
            builder.header("X-NanoFaaS-Offloaded", ex.targetUrl());
        }
        return builder.build();
    }
}
