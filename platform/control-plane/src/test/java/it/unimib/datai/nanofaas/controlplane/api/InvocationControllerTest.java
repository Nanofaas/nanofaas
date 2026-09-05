package it.unimib.datai.nanofaas.controlplane.api;

import it.unimib.datai.nanofaas.common.model.ExecutionStatus;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.queue.QueueFullException;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionNotFoundException;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionService;
import it.unimib.datai.nanofaas.controlplane.service.AsyncQueueUnavailableException;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadFailedException;
import it.unimib.datai.nanofaas.controlplane.service.IdempotencyBudgetExhaustedException;
import it.unimib.datai.nanofaas.controlplane.service.InvocationService;
import it.unimib.datai.nanofaas.controlplane.service.OutcomeGoneException;
import it.unimib.datai.nanofaas.controlplane.service.SyncInvocation;
import it.unimib.datai.nanofaas.controlplane.service.RateLimiter;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectReason;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectedException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebFluxTest(controllers = {InvocationController.class, FunctionController.class})
@Import({GlobalExceptionHandler.class, RateLimiter.class})
class InvocationControllerTest {

    @Autowired
    private WebTestClient webClient;

    @MockitoBean
    private InvocationService invocationService;

    @MockitoBean
    private FunctionService functionService;

    @Test
    void invokeSync_success_returnsExecutionHeaderAndBody() {
        InvocationRequest request = new InvocationRequest("payload", Map.of());
        InvocationResponse response = new InvocationResponse("exec-1", "success", "out", null);
        when(invocationService.invokeSyncReactive(eq("echo"), any(), eq(null), eq(null), eq(null), any()))
                .thenReturn(Mono.just(SyncInvocation.local(response)));

        webClient.post()
                .uri("/v1/functions/echo:invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Execution-Id", "exec-1")
                .expectBody()
                .jsonPath("$.executionId").isEqualTo("exec-1")
                .jsonPath("$.status").isEqualTo("success")
                .jsonPath("$.output").isEqualTo("out");
    }

    @Test
    void invokeSync_capturesCallerHeadersIntoRequest() {
        InvocationResponse response = new InvocationResponse("exec-hdr", "success", "out", null);
        when(invocationService.invokeSyncReactive(eq("echo"), any(), eq("k-1"), eq("t-1"), eq(null), any()))
                .thenReturn(Mono.just(SyncInvocation.local(response)));

        webClient.post()
                .uri("/v1/functions/echo:invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer x")
                .header("Connection", "keep-alive")
                .header("X-Trace-Id", "t-1")
                .header("Idempotency-Key", "k-1")
                .bodyValue(new InvocationRequest("payload", Map.of()))
                .exchange()
                .expectStatus().isOk();

        ArgumentCaptor<InvocationRequest> captor = ArgumentCaptor.forClass(InvocationRequest.class);
        verify(invocationService).invokeSyncReactive(eq("echo"), captor.capture(), eq("k-1"), eq("t-1"), eq(null), any());
        Map<String, String> capturedHeaders = captor.getValue().headers();
        assertThat(capturedHeaders)
                .containsEntry("authorization", "Bearer x")
                .doesNotContainKeys("connection", "x-trace-id", "idempotency-key");
    }

    @Test
    void invokeSync_connectionNominatedHeadersAreStrippedFromCallerHeaders() {
        InvocationResponse response = new InvocationResponse("exec-hdr", "success", "out", null);
        when(invocationService.invokeSyncReactive(eq("echo"), any(), eq(null), eq(null), eq(null), any()))
                .thenReturn(Mono.just(SyncInvocation.local(response)));

        webClient.post()
                .uri("/v1/functions/echo:invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer x")
                .header("Connection", "X-Hop-Data")
                .header("X-Hop-Data", "secret")
                .bodyValue(new InvocationRequest("payload", Map.of()))
                .exchange()
                .expectStatus().isOk();

        // RFC 9110: Connection nominates X-Hop-Data as hop-by-hop. It concerns only the
        // caller's connection, so it must not reach the handler (nor, later, the offload
        // gateway, which only ever sees this map).
        ArgumentCaptor<InvocationRequest> captor = ArgumentCaptor.forClass(InvocationRequest.class);
        verify(invocationService).invokeSyncReactive(eq("echo"), captor.capture(), eq(null), eq(null), eq(null), any());
        Map<String, String> capturedHeaders = captor.getValue().headers();
        assertThat(capturedHeaders)
                .containsEntry("authorization", "Bearer x")
                .doesNotContainKeys("connection", "x-hop-data");
    }

    @Test
    void invokeSync_callerCannotForgeHeadersViaBody() {
        InvocationResponse response = new InvocationResponse("exec-forge", "success", "out", null);
        when(invocationService.invokeSyncReactive(eq("echo"), any(), eq(null), eq(null), eq(null), any()))
                .thenReturn(Mono.just(SyncInvocation.local(response)));

        webClient.post()
                .uri("/v1/functions/echo:invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new InvocationRequest(Map.of(), Map.of(), Map.of("authorization", "forged")))
                .exchange()
                .expectStatus().isOk();

        ArgumentCaptor<InvocationRequest> captor = ArgumentCaptor.forClass(InvocationRequest.class);
        verify(invocationService).invokeSyncReactive(eq("echo"), captor.capture(), eq(null), eq(null), eq(null), any());
        assertThat(captor.getValue().headers()).doesNotContainKey("authorization");
    }

    @Test
    void invokeSync_syncQueueRejectedFromMono_mapsTo429WithHeaders() {
        InvocationRequest request = new InvocationRequest("payload", Map.of());
        when(invocationService.invokeSyncReactive(eq("echo"), any(), eq(null), eq(null), eq(null), any()))
                .thenReturn(Mono.error(new SyncQueueRejectedException(SyncQueueRejectReason.EST_WAIT, 7)));

        webClient.post()
                .uri("/v1/functions/echo:invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("Retry-After", "7")
                .expectHeader().valueEquals("X-Queue-Reject-Reason", "est_wait");
    }

    @Test
    void invokeSync_syncQueueRejectedThrownSynchronously_mapsTo429WithHeaders() {
        InvocationRequest request = new InvocationRequest("payload", Map.of());
        when(invocationService.invokeSyncReactive(eq("echo"), any(), eq(null), eq(null), eq(null), any()))
                .thenThrow(new SyncQueueRejectedException(SyncQueueRejectReason.DEPTH, 3));

        webClient.post()
                .uri("/v1/functions/echo:invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("Retry-After", "3")
                .expectHeader().valueEquals("X-Queue-Reject-Reason", "depth");
    }

    @Test
    void invokeSync_queueFull_returns429() {
        InvocationRequest request = new InvocationRequest("payload", Map.of());
        when(invocationService.invokeSyncReactive(eq("echo"), any(), eq(null), eq(null), eq(null), any()))
                .thenThrow(new QueueFullException());

        webClient.post()
                .uri("/v1/functions/echo:invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isEqualTo(429);
    }

    @Test
    void invokeSync_queueFullFromReactiveCoordinator_returns429() {
        InvocationRequest request = new InvocationRequest("payload", Map.of());
        when(invocationService.invokeSyncReactive(eq("echo"), any(), eq(null), eq(null), eq(null), any()))
                .thenReturn(Mono.error(new QueueFullException()));

        webClient.post()
                .uri("/v1/functions/echo:invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isEqualTo(429);
    }

    @Test
    void invokeSync_functionDecidedStatusCode_usedAsRealHttpStatus() {
        InvocationRequest request = new InvocationRequest("payload", Map.of());
        InvocationResponse response = new InvocationResponse("ex-1", "success", "out", null, 404,
                Map.of("Location", "/x"), null);
        when(invocationService.invokeSyncReactive(eq("echo"), any(), eq(null), eq(null), eq(null), any()))
                .thenReturn(Mono.just(SyncInvocation.local(response)));

        webClient.post()
                .uri("/v1/functions/echo:invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isEqualTo(404)
                .expectHeader().valueEquals("Location", "/x")
                .expectHeader().valueEquals("X-NanoFaaS-Function-Status", "true")
                .expectHeader().valueEquals("X-Execution-Id", "ex-1");
    }

    @Test
    void invokeSync_functionDecidedEncoding_isExposedOnEnvelopeResponse() {
        InvocationRequest request = new InvocationRequest("payload", Map.of());
        InvocationResponse response = new InvocationResponse("ex-encoding", "success", "AAEC", null, 200,
                Map.of("Content-Type", "application/octet-stream"), "base64");
        when(invocationService.invokeSyncReactive(eq("echo"), any(), eq(null), eq(null), eq(null), any()))
                .thenReturn(Mono.just(SyncInvocation.local(response)));

        webClient.post()
                .uri("/v1/functions/echo:invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-NanoFaaS-Function-Status", "true")
                .expectHeader().valueEquals("X-NanoFaaS-Encoding", "base64")
                .expectBody()
                .jsonPath("$.encoding").isEqualTo("base64");
    }

    @Test
    void invokeSync_copiesAllowedHeaderButExcludesContentTypeFromRealResponse() {
        // Content-Type and Location arrive together so a regression that short-circuits the
        // whole header-copy loop when Content-Type is present (copying nothing) cannot pass
        // this test the way it could if the two headers were asserted in isolation. Location
        // being a REAL header also fails against a full revert of Task 10 (pre-Task-10 code
        // never copies any handler-supplied header onto the response).
        InvocationRequest request = new InvocationRequest("payload", Map.of());
        InvocationResponse response = new InvocationResponse("ex-2", "success", "out", null, 200,
                Map.of("Content-Type", "application/pdf", "Location", "/x"), null);
        when(invocationService.invokeSyncReactive(eq("echo"), any(), eq(null), eq(null), eq(null), any()))
                .thenReturn(Mono.just(SyncInvocation.local(response)));

        webClient.post()
                .uri("/v1/functions/echo:invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectHeader().valueEquals("Location", "/x")
                .expectBody()
                .jsonPath("$.headers['Content-Type']").isEqualTo("application/pdf");
    }

    @Test
    void invokeSync_noStatusCode_defaultsTo200AsToday() {
        InvocationRequest request = new InvocationRequest("payload", Map.of());
        InvocationResponse response = new InvocationResponse("ex-3", "success", "out", null, null, null, "base64");
        when(invocationService.invokeSyncReactive(eq("echo"), any(), eq(null), eq(null), eq(null), any()))
                .thenReturn(Mono.just(SyncInvocation.local(response)));

        webClient.post()
                .uri("/v1/functions/echo:invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist("X-NanoFaaS-Function-Status")
                .expectHeader().doesNotExist("X-NanoFaaS-Encoding");
    }

    @Test
    void invokeSync_outOfRangeStatusCode_treatedAsPlatformDefaultNot200Lie() {
        // Defense-in-depth: statusCode is already validated upstream (ExternalDispatcher via
        // ResponseHeaderPolicy.isStatusCodeValid), so this should never happen in practice.
        // If it ever did, the out-of-range value must not reach ResponseEntity.status(int) as
        // a real status. A co-present allowed header (Location) is asserted too: the header
        // copy loop is unconditional (runs regardless of functionDecided), so seeing it land
        // as a real header also fails against a full revert of Task 10, where no
        // handler-supplied header is ever copied at all — a bare "falls back to 200" assertion
        // would pass identically pre- and post-Task-10 and prove nothing happened.
        InvocationRequest request = new InvocationRequest("payload", Map.of());
        InvocationResponse response = new InvocationResponse("ex-4", "success", "out", null, 999,
                Map.of("Location", "/x"), null);
        when(invocationService.invokeSyncReactive(eq("echo"), any(), eq(null), eq(null), eq(null), any()))
                .thenReturn(Mono.just(SyncInvocation.local(response)));

        webClient.post()
                .uri("/v1/functions/echo:invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist("X-NanoFaaS-Function-Status")
                .expectHeader().valueEquals("Location", "/x");
    }

    @Test
    void invokeSync_offloaded_addsOffloadedHeader() {
        InvocationRequest request = new InvocationRequest("payload", Map.of());
        InvocationResponse response = new InvocationResponse("exec-off", "success", "out", null);
        when(invocationService.invokeSyncReactive(eq("echo"), any(), eq(null), eq(null), eq(null), any()))
                .thenReturn(Mono.just(new SyncInvocation(response, "http://cloud:8080")));

        webClient.post()
                .uri("/v1/functions/echo:invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-NanoFaaS-Offloaded", "http://cloud:8080")
                .expectBody()
                .jsonPath("$.executionId").isEqualTo("exec-off");
    }

    @Test
    void invokeSync_offloadFailed_returns502WithTargetHeader() {
        InvocationRequest request = new InvocationRequest("payload", Map.of());
        when(invocationService.invokeSyncReactive(eq("echo"), any(), eq(null), eq(null), eq(null), any()))
                .thenReturn(Mono.error(new OffloadFailedException("http://cloud:8080", false, "unreachable")));

        webClient.post()
                .uri("/v1/functions/echo:invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectHeader().valueEquals("X-NanoFaaS-Offloaded", "http://cloud:8080");
    }

    @Test
    void invokeSync_offloadGatewayTimeout_returns504() {
        InvocationRequest request = new InvocationRequest("payload", Map.of());
        when(invocationService.invokeSyncReactive(eq("echo"), any(), eq(null), eq(null), eq(null), any()))
                .thenReturn(Mono.error(new OffloadFailedException("http://cloud:8080", true, "timed out")));

        webClient.post()
                .uri("/v1/functions/echo:invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isEqualTo(504);
    }

    @Test
    void invokeAsync_success_returns202AndDelegatesHeaders() {
        InvocationRequest request = new InvocationRequest("payload", Map.of("x", "y"));
        InvocationResponse response = new InvocationResponse("exec-2", "queued", null, null);
        when(invocationService.invokeAsync(eq("echo"), any(), eq("idem-1"), eq("trace-1"))).thenReturn(response);

        webClient.post()
                .uri("/v1/functions/echo:enqueue")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "idem-1")
                .header("X-Trace-Id", "trace-1")
                .bodyValue(request)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.executionId").isEqualTo("exec-2")
                .jsonPath("$.status").isEqualTo("queued");

        ArgumentCaptor<InvocationRequest> captor = ArgumentCaptor.forClass(InvocationRequest.class);
        verify(invocationService).invokeAsync(eq("echo"), captor.capture(), eq("idem-1"), eq("trace-1"));
        assertThat(captor.getValue().input()).isEqualTo("payload");
        assertThat(captor.getValue().metadata()).isEqualTo(Map.of("x", "y"));
    }

    @Test
    void invokeAsync_capturesCallerHeadersIntoRequest() {
        InvocationResponse response = new InvocationResponse("exec-async-hdr", "queued", null, null);
        when(invocationService.invokeAsync(eq("echo"), any(), eq("k-1"), eq("t-1"))).thenReturn(response);

        webClient.post()
                .uri("/v1/functions/echo:enqueue")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer x")
                .header("Connection", "keep-alive")
                .header("X-Trace-Id", "t-1")
                .header("Idempotency-Key", "k-1")
                .bodyValue(new InvocationRequest("payload", Map.of()))
                .exchange()
                .expectStatus().isAccepted();

        ArgumentCaptor<InvocationRequest> captor = ArgumentCaptor.forClass(InvocationRequest.class);
        verify(invocationService).invokeAsync(eq("echo"), captor.capture(), eq("k-1"), eq("t-1"));
        Map<String, String> capturedHeaders = captor.getValue().headers();
        assertThat(capturedHeaders)
                .containsEntry("authorization", "Bearer x")
                .doesNotContainKeys("connection", "x-trace-id", "idempotency-key");
    }

    @Test
    void invokeAsync_functionNotFound_returns404() {
        InvocationRequest request = new InvocationRequest("payload", Map.of());
        when(invocationService.invokeAsync(eq("missing"), any(), eq(null), eq(null)))
                .thenThrow(new FunctionNotFoundException("missing"));

        webClient.post()
                .uri("/v1/functions/missing:enqueue")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void invokeAsync_whenAsyncQueueUnavailable_returns501() {
        InvocationRequest request = new InvocationRequest("payload", Map.of());
        when(invocationService.invokeAsync(eq("echo"), any(), eq(null), eq(null)))
                .thenThrow(new AsyncQueueUnavailableException());

        webClient.post()
                .uri("/v1/functions/echo:enqueue")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isEqualTo(501);
    }

    @Test
    void getExecution_notFound_returns404() {
        when(invocationService.getStatus("exec-missing")).thenReturn(Optional.empty());

        webClient.get()
                .uri("/v1/executions/exec-missing")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void getExecution_found_returns200() {
        when(invocationService.getStatus("exec-3"))
                .thenReturn(Optional.of(new ExecutionStatus("exec-3", "queued", null, null, null, null, false, null)));

        webClient.get()
                .uri("/v1/executions/exec-3")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.executionId").isEqualTo("exec-3")
                .jsonPath("$.status").isEqualTo("queued");
    }

    @Test
    void completeExecution_returns204AndCallsService() {
        InvocationResult result = InvocationResult.success("ok");

        webClient.post()
                .uri("/v1/internal/executions/exec-4:complete")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(result)
                .exchange()
                .expectStatus().isNoContent();

        verify(invocationService).completeExecution("exec-4", result);
    }

    @Test
    void completeExecution_withDispatchAttemptHeader_passesAttemptToService() {
        InvocationResult result = InvocationResult.success("ok");

        webClient.post()
                .uri("/v1/internal/executions/exec-attempt:complete")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Dispatch-Attempt", "7")
                .bodyValue(result)
                .exchange()
                .expectStatus().isNoContent();

        verify(invocationService).completeExecution("exec-attempt", result, 7);
    }

    @Test
    void completeExecution_withMalformedDispatchAttemptHeader_usesLegacyCompletionPath() {
        InvocationResult result = InvocationResult.success("ok");

        webClient.post()
                .uri("/v1/internal/executions/exec-malformed-attempt:complete")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Dispatch-Attempt", "not-an-int")
                .bodyValue(result)
                .exchange()
                .expectStatus().isNoContent();

        verify(invocationService).completeExecution("exec-malformed-attempt", result);
    }

    @Test
    void completeExecution_withNonPositiveDispatchAttemptHeader_usesLegacyCompletionPath() {
        InvocationResult result = InvocationResult.success("ok");

        webClient.post()
                .uri("/v1/internal/executions/exec-zero-attempt:complete")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Dispatch-Attempt", "0")
                .bodyValue(result)
                .exchange()
                .expectStatus().isNoContent();

        verify(invocationService).completeExecution("exec-zero-attempt", result);
    }

    @Test
    void completeExecution_acceptsStructuredJsonObjectOutput() {
        webClient.post()
                .uri("/v1/internal/executions/exec-structured:complete")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "success": true,
                          "output": {"wordCount": 4, "topWords": [{"word":"the","count":1}]},
                          "error": null
                        }
                        """)
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void invokeSync_outcomeGone_mapsTo410WithExecutionId() {
        InvocationRequest request = new InvocationRequest("payload", Map.of());
        when(invocationService.invokeSyncReactive(eq("echo"), any(), eq("k"), eq(null), eq(null), any()))
                .thenReturn(Mono.error(new OutcomeGoneException("exec-gone")));

        webClient.post()
                .uri("/v1/functions/echo:invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "k")
                .bodyValue(request)
                .exchange()
                .expectStatus().isEqualTo(410)
                .expectHeader().valueEquals("X-Execution-Id", "exec-gone");
    }

    @Test
    void invokeSync_keyBudgetExhausted_mapsTo429() {
        InvocationRequest request = new InvocationRequest("payload", Map.of());
        when(invocationService.invokeSyncReactive(eq("echo"), any(), eq("k"), eq(null), eq(null), any()))
                .thenReturn(Mono.error(new IdempotencyBudgetExhaustedException()));

        webClient.post()
                .uri("/v1/functions/echo:invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "k")
                .bodyValue(request)
                .exchange()
                .expectStatus().isEqualTo(429);
    }

    @Test
    void invokeAsync_outcomeGone_mapsTo410WithExecutionId() {
        InvocationRequest request = new InvocationRequest("payload", Map.of());
        when(invocationService.invokeAsync(eq("echo"), any(), eq("k"), eq(null)))
                .thenThrow(new OutcomeGoneException("exec-gone-async"));

        webClient.post()
                .uri("/v1/functions/echo:enqueue")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "k")
                .bodyValue(request)
                .exchange()
                .expectStatus().isEqualTo(410)
                .expectHeader().valueEquals("X-Execution-Id", "exec-gone-async");
    }

    @Test
    void invokeAsync_keyBudgetExhausted_mapsTo429() {
        InvocationRequest request = new InvocationRequest("payload", Map.of());
        when(invocationService.invokeAsync(eq("echo"), any(), eq("k"), eq(null)))
                .thenThrow(new IdempotencyBudgetExhaustedException());

        webClient.post()
                .uri("/v1/functions/echo:enqueue")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "k")
                .bodyValue(request)
                .exchange()
                .expectStatus().isEqualTo(429);
    }
}
