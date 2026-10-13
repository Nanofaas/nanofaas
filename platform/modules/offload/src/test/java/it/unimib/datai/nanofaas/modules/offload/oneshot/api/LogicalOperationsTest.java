package it.unimib.datai.nanofaas.modules.offload.oneshot.api;

import it.unimib.datai.nanofaas.modules.offload.oneshot.actuation.*;
import it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.*;
import it.unimib.datai.nanofaas.p2papi.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LogicalOperationsTest {
    static OneShotSettings logical(OneShotSettings s, boolean scheduled) {
        return new OneShotSettings(2,s.profileId(),s.environmentFingerprint(),s.purpose(),s.allowSynthetic(),
                s.cloudUri(),s.memoryCapacityMiB(),s.flowQuantum(),s.period(),s.leadTime(),scheduled,
                s.anchor(),s.preparationBudget(),s.maxOperationalFraction(),s.burst(),s.functions(),
                s.negotiation(),s.maxSolverStates(),s.maxSolverBytes(),"session");
    }
    static final class Fixture implements AutoCloseable {
        final AtomicLong nano = new AtomicLong(100);
        final OneShotConfigurationStore configs = new OneShotConfigurationStore(()->true,()->true);
        final ServiceProfileStore profiles = mock(ServiceProfileStore.class);
        final ProfileEpochInputFactory inputs = mock(ProfileEpochInputFactory.class);
        final EpochCoordinator coordinator = mock(EpochCoordinator.class);
        final ReplicaPlanActuator actuator = mock(ReplicaPlanActuator.class);
        final PeerTransport peers = mock(PeerTransport.class);
        final EpochEventStore events = new EpochEventStore(100);
        final OneShotSettings settings = logical(OneShotConfigurationTest.config(),false);
        final OneShotOperations operations;
        final WebTestClient client;
        Fixture() {
            when(profiles.compatible(any())).thenReturn(new ServiceProfileStore.Stored(1,"hash",null,1));
            when(inputs.pin(anyLong(),any(),any(),any())).thenAnswer(a->{
                nano.addAndGet(Duration.ofMillis(700).toNanos());
                return new EpochInput(null,Map.of(),Map.of(),1,1,null,100,100,"hash");
            });
            when(peers.localEndpoint()).thenReturn(Optional.of(new PeerEndpoint("edge","inc",URI.create("http://edge:8080"))));
            when(peers.activeNeighbors()).thenReturn(List.of());
            when(peers.subscribe(anyString(),any())).thenReturn(()->{});
            when(actuator.ownedFunctions()).thenReturn(Set.of());
            when(coordinator.lastPreparedEpoch()).thenReturn(-1L);
            when(coordinator.prepareLogical(anyLong(),any(EpochTiming.class))).thenAnswer(a-> {
                EpochTiming timing=a.getArgument(1);
                return Mono.just(new EpochOutcome(a.getArgument(0),EpochOutcome.Status.FIXED_ROUNDS_COMPLETED,
                        null,10,5,10,"bounded",null,timing));
            });
            when(actuator.prepare(any(),any(),any())).thenReturn(Mono.just(result(PlanActivation.Status.NEGOTIATED)));
            when(actuator.activate(anyString(),anyLong())).thenAnswer(a->Mono.fromSupplier(()-> {
                nano.addAndGet(Duration.ofMillis(300).toNanos());
                return result(PlanActivation.Status.PREPARED);
            }));
            var clock=Clock.fixed(Instant.parse("2040-01-01T00:00:00Z"),ZoneOffset.UTC);
            operations=new OneShotOperations(configs,profiles,inputs,coordinator,actuator,events,peers,
                    new SimpleMeterRegistry(),true,clock,nano::get);
            operations.start();
            client=WebTestClient.bindToController(new OneShotController(operations,profiles,events,
                    new ClockHealth(Duration.ofMillis(10),Duration.ofSeconds(10),clock::instant))).build();
        }
        void configure() {
            client.put().uri("/v1/admin/offload/one-shot/config").header("If-Match","0")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .bodyValue(JsonMapper.builder().build().writeValueAsBytes(settings)).exchange().expectStatus().isOk();
        }
        OneShotOperations.LogicalWindow window() {
            return new OneShotOperations.LogicalWindow(settings.anchor(),settings.anchor().plus(settings.period()),settings.leadTime());
        }
        PlanActivation result(PlanActivation.Status status) {
            return new PlanActivation(status,null,Map.of(),Map.of(),"test",Instant.EPOCH);
        }
        @Override public void close() { operations.close(); }
    }

    @Test void httpPrepareAndExplicitActivationIgnoreWallTimeAndCountOnlyWork() {
        try(var f=new Fixture()) {
            f.configure();
            String base="/v1/admin/offload/one-shot/sessions/session/epochs/0";
            f.client.post().uri(base+"/prepare").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .bodyValue(JsonMapper.builder().build().writeValueAsBytes(f.window())).exchange()
                    .expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("NEGOTIATED");
            var timing=org.mockito.ArgumentCaptor.forClass(EpochTiming.class);
            verify(f.coordinator).prepareLogical(eq(0L),timing.capture());
            assertThat(timing.getValue().startedNanos()).isEqualTo(100);
            assertThat(timing.getValue().activationDeadline()).isEqualTo(100+f.settings.leadTime().toNanos());
            assertThat(f.operations.settingsFor(1)).contains(f.settings);
            verify(f.actuator,never()).activate(anyString(),anyLong());
            f.nano.addAndGet(Duration.ofSeconds(15).toNanos());
            for(int i=0;i<2;i++) f.client.post().uri(base+"/activate").exchange()
                    .expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("PREPARED");
            verify(f.actuator,times(1)).activate("session",0);
            verify(f.inputs).unpin(0);
            assertThat(f.operations.status().get("busy")).isEqualTo(false);
            var complete=f.events.page(0,0,100).stream().filter(e->e.event().status().equals("PREPARED")).toList();
            assertThat(complete).hasSize(1);
            assertThat(complete.getFirst().operationalNanos()).isEqualTo(Duration.ofSeconds(1).toNanos());
        }
    }

    @Test void rejectsMixedProtocolWrongSessionAndChangedCanonicalLabelsBeforePinning() {
        try(var f=new Fixture()) {
            f.configure();
            assertThatThrownBy(()->f.operations.prepare(0,new OneShotOperations.Window(Instant.EPOCH,Instant.EPOCH.plusSeconds(300)),false).block()).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->f.operations.prepareLogical("other",0,f.window()).block()).isInstanceOf(IllegalStateException.class);
            var altered=new OneShotOperations.LogicalWindow(Instant.EPOCH.plusSeconds(1),Instant.EPOCH.plusSeconds(301),Duration.ofSeconds(20));
            assertThatThrownBy(()->f.operations.prepareLogical("session",0,altered).block()).isInstanceOf(IllegalArgumentException.class);
            verify(f.inputs,never()).pin(anyLong(),any(),any(),any());
        }
    }

    @Test void stopDuringInputPinningCannotStageAPlanAfterRestart() {
        try(var f=new Fixture()) {
            f.configure();
            doAnswer(a->{f.operations.stop();f.operations.start();return new EpochInput(null,Map.of(),Map.of(),1,1,null,100,100,"hash");})
                    .when(f.inputs).pin(anyLong(),any(),any(),any());
            assertThatThrownBy(()->f.operations.prepareLogical("session",0,f.window()).block()).isInstanceOf(IllegalStateException.class);
            verify(f.coordinator,never()).prepareLogical(anyLong(),any(EpochTiming.class));
            verify(f.inputs).unpin(0);
            assertThat(f.operations.status().get("busy")).isEqualTo(false);
        }
    }

    @Test void statusIdentifiesLogicalProtocolAndConfigurationKeepsStrictVersionContracts() {
        try(var f=new Fixture()) {
            var mapper=JsonMapper.builder().build();
            var body=(tools.jackson.databind.node.ObjectNode)mapper.valueToTree(f.settings);
            body.remove("sessionId");
            f.client.put().uri("/v1/admin/offload/one-shot/config").header("If-Match","0")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue(mapper.writeValueAsBytes(body))
                    .exchange().expectStatus().isBadRequest();
            body.put("schemaVersion",1);
            f.client.put().uri("/v1/admin/offload/one-shot/config").header("If-Match","0")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue(mapper.writeValueAsBytes(body))
                    .exchange().expectStatus().isOk();
            f.operations.configure(1,f.settings);
            f.client.get().uri("/v1/admin/offload/one-shot/status").exchange().expectStatus().isOk()
                    .expectBody().jsonPath("$.schemaVersion").isEqualTo(2).jsonPath("$.sessionId").isEqualTo("session");
        }
    }
}
