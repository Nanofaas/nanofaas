package it.unimib.datai.nanofaas.controlplane.api;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import it.unimib.datai.nanofaas.common.model.RawJson;
import it.unimib.datai.nanofaas.controlplane.service.InvocationService;
import it.unimib.datai.nanofaas.controlplane.service.SyncInvocation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The control plane forwards an invocation payload; it has no reason to turn it into objects. */
class InvocationPayloadPassthroughTest {
    private static final String BODY =
            "{\"input\": {\"records\": [{\"id\": 1, \"score\": 2.50}], \"note\": \"città\"}, \"metadata\": {\"k\": \"v\"}}";
    // Exactly the bytes the caller sent for the value, spacing included.
    private static final String PAYLOAD = "{\"records\": [{\"id\": 1, \"score\": 2.50}], \"note\": \"città\"}";

    private final InvocationService service = mock(InvocationService.class);
    private final WebTestClient client = WebTestClient.bindToController(new InvocationController(service)).build();

    @Test
    void aSyncInvocationReachesTheServiceWithItsPayloadAsJsonText() {
        when(service.invokeSyncReactive(eq("fn"), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(SyncInvocation.local(new InvocationResponse("id", "success", "out", null))));

        client.post().uri("/v1/functions/fn:invoke").contentType(MediaType.APPLICATION_JSON).bodyValue(BODY)
                .exchange().expectStatus().isOk();

        ArgumentCaptor<InvocationRequest> request = ArgumentCaptor.forClass(InvocationRequest.class);
        verify(service).invokeSyncReactive(eq("fn"), request.capture(), any(), any(), any(), any());
        assertThat(request.getValue().input()).isInstanceOf(RawJson.class);
        assertThat(((RawJson) request.getValue().input()).json()).isEqualTo(PAYLOAD);
        assertThat(request.getValue().metadata()).containsEntry("k", "v");
    }

    @Test
    void scalarPayloadsPassThroughToo() {
        when(service.invokeSyncReactive(eq("fn"), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(SyncInvocation.local(new InvocationResponse("id", "success", "out", null))));
        for (String scalar : new String[]{"\"a \\\"quoted\\\" città \\\\\"", "\"\"", "-12.50e3", "true"}) {
            org.mockito.Mockito.clearInvocations(service);

            client.post().uri("/v1/functions/fn:invoke").contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"input\":" + scalar + "}").exchange().expectStatus().isOk();

            ArgumentCaptor<InvocationRequest> request = ArgumentCaptor.forClass(InvocationRequest.class);
            verify(service).invokeSyncReactive(eq("fn"), request.capture(), any(), any(), any(), any());
            assertThat(request.getValue().input()).hasToString(scalar);
        }
    }

    @Test
    void whatWasRefusedBeforeIsRefusedTheSameWay() {
        var withHandlers = WebTestClient.bindToController(new InvocationController(service))
                .controllerAdvice(new GlobalExceptionHandler()).build();
        for (String malformed : new String[]{"{\"input\": {\"a\": ", "{\"input\": \"unterminated}", "not json", "{\"input\": [1,,2]}"}) {
            withHandlers.post().uri("/v1/functions/fn:invoke").contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(malformed).exchange().expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.error").isEqualTo("BAD_REQUEST");
        }
        for (String noInput : new String[]{"{\"input\": null}", "{\"metadata\": {}}", "{}"}) {
            withHandlers.post().uri("/v1/functions/fn:invoke").contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(noInput).exchange().expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.error").isEqualTo("VALIDATION_ERROR")
                    .jsonPath("$.details[0]").isEqualTo("input: Input payload is required");
        }
        withHandlers.post().uri("/v1/functions/fn:invoke").contentType(MediaType.TEXT_PLAIN)
                .bodyValue("{\"input\": 1}").exchange().expectStatus().isEqualTo(415);
        withHandlers.post().uri("/v1/functions/fn:invoke").contentType(MediaType.APPLICATION_JSON)
                .exchange().expectStatus().isBadRequest();
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    /**
     * Only a keyed admission can park (waiting on another claimant of the same key); without a
     * key, admission is a few map operations and handing it to another thread costs more than
     * the work - the same rule the synchronous path follows.
     */
    @Test
    void anAsyncInvocationLeavesTheCallingThreadOnlyWhenItCarriesAnIdempotencyKey() {
        var threads = new java.util.concurrent.ConcurrentHashMap<String, String>();
        when(service.invokeAsync(eq("fn"), any(), any(), any())).thenAnswer(call -> {
            threads.put(call.getArgument(2) == null ? "unkeyed" : "keyed", Thread.currentThread().getName());
            return new InvocationResponse("id", "queued", null, null);
        });

        client.post().uri("/v1/functions/fn:enqueue").contentType(MediaType.APPLICATION_JSON).bodyValue(BODY)
                .exchange().expectStatus().isAccepted();
        client.post().uri("/v1/functions/fn:enqueue").header("Idempotency-Key", "k")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(BODY).exchange().expectStatus().isAccepted();

        assertThat(threads.get("keyed")).startsWith("boundedElastic");
        assertThat(threads.get("unkeyed")).doesNotStartWith("boundedElastic");
    }

    @Test
    void anAsyncInvocationDoesToo() {
        when(service.invokeAsync(eq("fn"), any(), any(), any()))
                .thenReturn(new InvocationResponse("id", "queued", null, null));

        client.post().uri("/v1/functions/fn:enqueue").contentType(MediaType.APPLICATION_JSON).bodyValue(BODY)
                .exchange().expectStatus().isAccepted();

        ArgumentCaptor<InvocationRequest> request = ArgumentCaptor.forClass(InvocationRequest.class);
        verify(service).invokeAsync(eq("fn"), request.capture(), any(), any());
        assertThat(((RawJson) request.getValue().input()).json()).isEqualTo(PAYLOAD);
    }
}
