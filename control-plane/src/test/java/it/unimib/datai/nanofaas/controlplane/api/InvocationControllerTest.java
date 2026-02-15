package it.unimib.datai.nanofaas.controlplane.api;

import it.unimib.datai.nanofaas.common.model.ExecutionStatus;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.queue.QueueFullException;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionNotFoundException;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionService;
import it.unimib.datai.nanofaas.controlplane.service.InvocationService;
import it.unimib.datai.nanofaas.controlplane.service.RateLimitException;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectReason;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebFluxTest(controllers = {InvocationController.class, FunctionController.class})
@Import(GlobalExceptionHandler.class)
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
        when(invocationService.invokeSyncReactive(eq("echo"), any(), eq(null), eq(null), eq(null)))
                .thenReturn(Mono.just(response));

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
    void invokeSync_syncQueueRejectedFromMono_mapsTo429WithHeaders() {
        InvocationRequest request = new InvocationRequest("payload", Map.of());
        when(invocationService.invokeSyncReactive(eq("echo"), any(), eq(null), eq(null), eq(null)))
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
        when(invocationService.invokeSyncReactive(eq("echo"), any(), eq(null), eq(null), eq(null)))
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
    void invokeSync_rateLimited_returns429() {
        InvocationRequest request = new InvocationRequest("payload", Map.of());
        when(invocationService.invokeSyncReactive(eq("echo"), any(), eq(null), eq(null), eq(null)))
                .thenThrow(new RateLimitException());

        webClient.post()
                .uri("/v1/functions/echo:invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isEqualTo(429);
    }

    @Test
    void invokeSync_queueFull_returns429() {
        InvocationRequest request = new InvocationRequest("payload", Map.of());
        when(invocationService.invokeSyncReactive(eq("echo"), any(), eq(null), eq(null), eq(null)))
                .thenThrow(new QueueFullException());

        webClient.post()
                .uri("/v1/functions/echo:invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isEqualTo(429);
    }

    @Test
    void invokeAsync_success_returns202AndDelegatesHeaders() {
        InvocationRequest request = new InvocationRequest("payload", Map.of("x", "y"));
        InvocationResponse response = new InvocationResponse("exec-2", "queued", null, null);
        when(invocationService.invokeAsync("echo", request, "idem-1", "trace-1")).thenReturn(response);

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
}
