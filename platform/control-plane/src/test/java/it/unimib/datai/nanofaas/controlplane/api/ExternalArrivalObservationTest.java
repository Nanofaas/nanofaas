package it.unimib.datai.nanofaas.controlplane.api;
import it.unimib.datai.nanofaas.common.model.*;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.service.*;
import it.unimib.datai.nanofaas.forecastingapi.ExternalArrival;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import java.util.ArrayList;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class ExternalArrivalObservationTest {
    final InvocationService service = mock(InvocationService.class);
    final FunctionCapacityRegistry capacity = new FunctionCapacityRegistry();
    final ArrayList<ExternalArrival> events = new ArrayList<>();
    final WebTestClient client = WebTestClient.bindToController(new InvocationController(service, events::add, capacity)).build();
    void invoke(String hop) {
        var request = client.post().uri("/v1/functions/f:invoke").contentType(MediaType.APPLICATION_JSON);
        if (hop != null) request.header("X-NanoFaaS-Offload-Hop", hop);
        request.bodyValue(new InvocationRequest("payload", Map.of())).exchange().expectStatus().isOk();
    }
    void setupFunction() {
        capacity.register("f", 1);
        when(service.invokeSyncReactive(eq("f"), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(SyncInvocation.local(new InvocationResponse("id", "success", "out", null))));
    }
    @Test void externalHttpArrivalIsRecordedOnceAndForwardedHopIsExcluded() {
        setupFunction(); invoke(null); invoke("1");
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().generation()).isEqualTo(capacity.activeGeneration("f").id());
    }
    @Test void clientRetriesAreNewArrivalsButServiceResubscriptionIsNot() {
        setupFunction(); invoke(null); invoke(null);
        assertThat(events).hasSize(2);
        assertThat(events.get(0).requestId()).isNotEqualTo(events.get(1).requestId());
    }
    @Test void newGenerationDoesNotReuseOldIdentityAndUnknownFunctionDoesNotCount() {
        setupFunction(); invoke(null);
        long previous = events.getFirst().generation();
        capacity.remove("f"); capacity.register("f", 1); invoke(null);
        assertThat(events.get(1).generation()).isGreaterThan(previous);
        capacity.remove("f"); invoke(null);
        assertThat(events).hasSize(2);
    }
    @Test void invalidHttpBodyDoesNotProduceArrival() {
        setupFunction();
        client.post().uri("/v1/functions/f:invoke").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("invalid json").exchange().expectStatus().isBadRequest();
        assertThat(events).isEmpty();
    }
    @Test void retryingServicePublisherDoesNotCreateAnotherHttpArrival() {
        setupFunction();
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        when(service.invokeSyncReactive(eq("f"), any(), any(), any(), any(), any()))
                .thenReturn(Mono.defer(() -> attempts.incrementAndGet() == 1
                        ? Mono.error(new IllegalStateException("internal failure"))
                        : Mono.just(SyncInvocation.local(new InvocationResponse("id", "success", "out", null)))));
        new InvocationController(service, events::add, capacity).invokeSync("f",
                new InvocationRequest("payload", Map.of()), null, null, null, null, null, null,
                new org.springframework.util.LinkedMultiValueMap<>()).retry(1).block();
        assertThat(attempts.get()).isEqualTo(2);
        assertThat(events).hasSize(1);
    }
    @Test void asyncHttpArrivalsAreObservedAndForwardedArrivalsExcluded() {
        setupFunction();
        when(service.invokeAsync(eq("f"), any(), any(), any())).thenReturn(new InvocationResponse("id", "queued", null, null));
        client.post().uri("/v1/functions/f:enqueue").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new InvocationRequest("payload", Map.of())).exchange().expectStatus().isAccepted();
        client.post().uri("/v1/functions/f:enqueue").contentType(MediaType.APPLICATION_JSON)
                .header("X-NanoFaaS-Offload-Hop", "1").bodyValue(new InvocationRequest("payload", Map.of()))
                .exchange().expectStatus().isAccepted();
        assertThat(events).hasSize(1);
    }
}
