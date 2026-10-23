package it.unimib.datai.nanofaas.modules.offload.oneshot.api;

import it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.EpochTiming;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LogicalAutomaticOperationsTest {
    @Test void catchUpOwnsTheOperationalGateUntilPhysicalDrainIsProven() throws Exception {
        try(var f=new LogicalOperationsTest.Fixture()) {
            ((java.util.concurrent.ScheduledFuture<?>)org.springframework.test.util.ReflectionTestUtils.getField(f.operations,"tickTask")).cancel(false);
            when(f.peers.activeNeighbors()).thenReturn(java.util.List.of(new it.unimib.datai.nanofaas.p2papi.PeerEndpoint("neighbor","neighbor-run",java.net.URI.create("http://neighbor:8080"))));
            f.configure();
            for(long epoch=0;epoch<20;epoch++) {
                var from=f.settings.anchor().plus(f.settings.period().multipliedBy(epoch));
                f.operations.prepareLogical("session",epoch,new OneShotOperations.LogicalWindow(from,from.plus(f.settings.period()),f.settings.leadTime())).block();
                f.operations.activate("session",epoch).block();
            }
            f.operations.configure(1,LogicalOperationsTest.logical(f.settings,true));
            f.operations.startSession("session",new OneShotOperations.SessionStart(20,Duration.ofSeconds(20)));
            var entered=new java.util.concurrent.atomic.AtomicBoolean();
            var drain=reactor.core.publisher.Sinks.<Boolean>one();
            when(f.actuator.drainAndRelease()).thenReturn(drain.asMono().doOnSubscribe(ignored->entered.set(true)));
            var receiver=org.mockito.ArgumentCaptor.forClass(it.unimib.datai.nanofaas.p2papi.PeerReceiver.class);
            verify(f.peers).subscribe(eq(LogicalEpochInitiator.TOPIC),receiver.capture());
            var from=f.settings.anchor().plus(f.settings.period().multipliedBy(22));
            var mapper=tools.jackson.databind.json.JsonMapper.builder().build();
            var status=new LogicalEpochInitiator.Message(2,"STATUS","session","neighbor","neighbor-run",22,from,from.plus(f.settings.period()),Duration.ofSeconds(20));
            when(f.peers.request(eq("neighbor"),eq(LogicalEpochInitiator.TOPIC),any(),any())).thenReturn(reactor.core.publisher.Mono.just(mapper.writeValueAsBytes(status)));
            var proposal=new LogicalEpochInitiator.Message(2,"PROPOSE","session","neighbor","neighbor-run",22,from,from.plus(f.settings.period()),Duration.ofSeconds(20));
            receiver.getValue().onMessage("neighbor",mapper.writeValueAsBytes(proposal)).block();
            LogicalEpochInitiatorTest.await(entered::get);
            assertThat(f.operations.status().get("busy")).isEqualTo(true);
            drain.tryEmitValue(false);
            LogicalEpochInitiatorTest.await(()->!((Boolean)f.operations.status().get("busy")));
            verify(f.inputs,never()).pin(eq(22L),any(),any(),any());
            verify(f.coordinator,never()).prepareLogical(eq(22L),any(EpochTiming.class));
            assertThat(f.operations.status().get("state")).isEqualTo("FAILED");
        }
    }

    @Test void explicitSessionStartUsesMonotonicPreparationAndActivationAfterRealTimingQualification() throws Exception {
        try(var f=new LogicalOperationsTest.Fixture()) {
            f.configure();
            for(long epoch=0;epoch<20;epoch++) {
                var from=f.settings.anchor().plus(f.settings.period().multipliedBy(epoch));
                f.operations.prepareLogical("session",epoch,new OneShotOperations.LogicalWindow(from,from.plus(f.settings.period()),f.settings.leadTime())).block();
                f.operations.activate("session",epoch).block();
            }
            var automatic=LogicalOperationsTest.logical(f.settings,true);
            f.operations.configure(1,automatic);
            assertThatThrownBy(()->f.operations.startSession("session",new OneShotOperations.SessionStart(0,Duration.ofSeconds(20))))
                    .isInstanceOf(IllegalStateException.class);
            clearInvocations(f.coordinator,f.actuator);
            for(int i=0;i<2;i++) f.client.post().uri("/v1/admin/offload/one-shot/sessions/session/start")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .bodyValue("{\"firstEpoch\":20,\"activationDelay\":\"PT20S\"}").exchange().expectStatus().isOk();
            f.operations.tick();
            LogicalEpochInitiatorTest.await(()->f.operations.status().get("state").equals("NEGOTIATED"));
            verify(f.coordinator,times(1)).prepareLogical(eq(20L),any(EpochTiming.class));
            verify(f.coordinator,never()).prepare(anyLong(),any(),any());
            verify(f.actuator,never()).activate(anyString(),anyLong());
            f.nano.addAndGet(Duration.ofSeconds(20).toNanos());
            LogicalEpochInitiatorTest.await(()-> {f.operations.tick();return f.operations.status().get("state").equals("PREPARED");});
            verify(f.actuator,times(1)).activate("session",20);
            f.operations.configure(2,LogicalOperationsTest.logical(f.settings,false));
            assertThat(f.operations.status().get("automaticArmed")).isEqualTo(false);
        }
    }
}
