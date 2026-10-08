package it.unimib.datai.nanofaas.modules.offload.oneshot.api;
import it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.*;
import it.unimib.datai.nanofaas.modules.offload.oneshot.actuation.*;
import it.unimib.datai.nanofaas.p2papi.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.time.*;
import java.net.URI;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class OneShotTimingQualificationTest {
    static final class Fixture implements AutoCloseable {
        final OneShotConfigurationStore store=new OneShotConfigurationStore(()->true,()->true);
        final ServiceProfileStore profiles=mock(ServiceProfileStore.class);
        final ProfileEpochInputFactory inputs=mock(ProfileEpochInputFactory.class);
        final EpochCoordinator coordinator=mock(EpochCoordinator.class);
        final ReplicaPlanActuator actuator=mock(ReplicaPlanActuator.class);
        final PeerTransport peers=mock(PeerTransport.class);
        final EpochEventStore events=new EpochEventStore(100);
        final OneShotSettings config=OneShotConfigurationTest.config();
        final OneShotOperations operations;
        Fixture() { this(Clock.systemUTC()); }
        Fixture(Clock clock) {
            when(profiles.compatible(any())).thenReturn(new ServiceProfileStore.Stored(1,"hash",null,1));
            when(coordinator.lastPreparedEpoch()).thenReturn(-1L);
            when(inputs.pin(anyLong(),any(),any(),any())).thenReturn(new EpochInput(null,Map.of(),Map.of(),1,1,null,100,100,"hash"));
            when(peers.activeNeighbors()).thenReturn(List.of());
            when(peers.localEndpoint()).thenReturn(Optional.of(new PeerEndpoint("edge","inc",URI.create("http://edge:8080"))));
            when(actuator.ownedFunctions()).thenReturn(Set.of());
            when(coordinator.prepare(anyLong(),any(),any())).thenAnswer(inv->Mono.just(new EpochOutcome(inv.getArgument(0),EpochOutcome.Status.CONVERGED,null,10,5,1,"ok")));
            when(actuator.prepare(any(),any())).thenReturn(Mono.just(activation(PlanActivation.Status.PREPARED)));
            operations=new OneShotOperations(store,profiles,inputs,coordinator,actuator,events,peers,new SimpleMeterRegistry(),true,clock);
            operations.configure(0,config);operations.start();
        }
        void sample(long epoch) {
            var start=Instant.now().plusSeconds(20);
            operations.prepare(epoch,new OneShotOperations.Window(start,start.plusSeconds(300)),false).block();
        }
        void qualify() { for(int i=0;i<20;i++) sample(i); }
        public void close() { operations.close(); }
    }
    static final class MutableClock extends Clock {
        volatile Instant now=Instant.EPOCH;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    static OneShotSettings grid(OneShotSettings s,Instant anchor,Duration period,boolean scheduled) {
        return new OneShotSettings(s.schemaVersion(),s.profileId(),s.environmentFingerprint(),s.purpose(),s.allowSynthetic(),s.cloudUri(),s.memoryCapacityMiB(),s.flowQuantum(),period,s.leadTime(),scheduled,anchor,s.preparationBudget(),s.maxOperationalFraction(),s.burst(),s.functions(),s.negotiation(),s.maxSolverStates(),s.maxSolverBytes());
    }
    @Test void rejectsGridChangeAfterFirstScheduledEpoch() {
        var clock=new MutableClock();
        try(var f=new Fixture(clock)) {
            f.qualify(); var scheduled=grid(f.config,Instant.EPOCH,f.config.period(),true);
            f.operations.configure(1,scheduled);
            clock.now=Instant.EPOCH.plusSeconds(280); f.operations.tick();
            verify(f.coordinator).prepare(1,Instant.EPOCH.plusSeconds(300),Instant.EPOCH.plusSeconds(600));
            for(var changed:List.of(grid(scheduled,Instant.EPOCH.plusSeconds(1),scheduled.period(),true),
                    grid(scheduled,scheduled.anchor(),scheduled.period().plusSeconds(1),true))) {
                assertThatThrownBy(()->f.operations.configure(2,changed)).isInstanceOf(IllegalStateException.class);
                assertThat(f.store.snapshot().orElseThrow().revision()).isEqualTo(2);
            }
            var s=scheduled;
            var equivalentProfile=new OneShotSettings(s.schemaVersion(),"equivalent-profile",s.environmentFingerprint(),s.purpose(),s.allowSynthetic(),s.cloudUri(),s.memoryCapacityMiB(),s.flowQuantum(),s.period(),s.leadTime(),true,s.anchor(),s.preparationBudget(),s.maxOperationalFraction(),s.burst(),s.functions(),s.negotiation(),s.maxSolverStates(),s.maxSolverBytes());
            assertThat(f.operations.configure(2,equivalentProfile).revision()).isEqualTo(3);
            clock.now=Instant.EPOCH.plusSeconds(580); f.operations.tick();
            verify(f.coordinator).prepare(2,Instant.EPOCH.plusSeconds(600),Instant.EPOCH.plusSeconds(900));
        }
    }
    @Test void fractionalPeriodCreatesContiguousWindows() {
        var clock=new MutableClock();
        try(var f=new Fixture(clock)) {
            f.qualify();var period=Duration.ofSeconds(300).plusNanos(500_000);
            var anchor=Instant.EPOCH.plusNanos(123_456);
            f.operations.configure(1,grid(f.config,anchor,period,true));
            clearInvocations(f.coordinator);
            clock.now=anchor.plus(period).minusSeconds(20);f.operations.tick();
            clock.now=anchor.plus(period.multipliedBy(2)).minusSeconds(20);f.operations.tick();
            var starts=org.mockito.ArgumentCaptor.forClass(Instant.class);
            var ends=org.mockito.ArgumentCaptor.forClass(Instant.class);
            verify(f.coordinator,times(2)).prepare(anyLong(),starts.capture(),ends.capture());
            assertThat(ends.getAllValues().getFirst()).isEqualTo(starts.getAllValues().get(1));
            assertThat(starts.getAllValues().getFirst()).isEqualTo(anchor.plus(period));
            for(int i=0;i<2;i++) assertThat(Duration.between(starts.getAllValues().get(i),ends.getAllValues().get(i))).isEqualTo(period);
        }
    }
    @Test void scheduleWindowRespectsAnchorLeadAndExactStartBoundary() {
        var base=OneShotConfigurationTest.config();var anchor=Instant.EPOCH.plusNanos(123_456);
        var s=grid(base,anchor,Duration.ofSeconds(300).plusNanos(500_000),true);
        assertThat(OneShotOperations.nextWindow(s,anchor.minus(s.leadTime()).minusNanos(1))).isEmpty();
        var first=OneShotOperations.nextWindow(s,anchor.minus(s.leadTime())).orElseThrow();
        assertThat(first.epoch()).isZero();assertThat(first.window().startsAt()).isEqualTo(anchor);
        assertThat(OneShotOperations.nextWindow(s,anchor)).isEmpty();
        var next=anchor.plus(s.period());
        assertThat(OneShotOperations.nextWindow(s,next.minus(s.leadTime()).minusNanos(1))).isEmpty();
        assertThat(OneShotOperations.nextWindow(s,next.minus(s.leadTime())).orElseThrow().epoch()).isEqualTo(1);
        assertThat(OneShotOperations.nextWindow(s,next)).isEmpty();
    }
    @Test void largeEpochKeepsPrecisionWithoutConvertingDeltaToNanos() {
        var base=OneShotConfigurationTest.config();var s=grid(base,Instant.EPOCH.plusNanos(123),Duration.ofSeconds(300).plusNanos(500_000),true);
        long epoch=1_000_000_000_000L;
        var from=s.anchor().plus(s.period().multipliedBy(epoch));
        var candidate=OneShotOperations.nextWindow(s,from.minus(s.leadTime())).orElseThrow();
        assertThat(candidate.epoch()).isEqualTo(epoch);
        assertThat(candidate.window().startsAt()).isEqualTo(from);
        assertThat(candidate.window().endsAt()).isEqualTo(from.plus(s.period()));
    }
    @Test void outOfRangeWindowAndEpochFailExplicitly() {
        var base=OneShotConfigurationTest.config();
        var s=grid(base,Instant.MAX.minusSeconds(100),base.period(),true);
        assertThatThrownBy(()->OneShotOperations.nextWindow(s,Instant.MAX.minusSeconds(1))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("time range");
        assertThatThrownBy(()->OneShotOperations.nextWindow(s,s.anchor().minusSeconds(20))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("time range");
        var tiny=new OneShotSettings(base.schemaVersion(),base.profileId(),base.environmentFingerprint(),base.purpose(),base.allowSynthetic(),base.cloudUri(),base.memoryCapacityMiB(),base.flowQuantum(),Duration.ofNanos(3),Duration.ofNanos(2),true,Instant.MIN,Duration.ofNanos(1),base.maxOperationalFraction(),base.burst(),base.functions(),base.negotiation(),base.maxSolverStates(),base.maxSolverBytes());
        assertThatThrownBy(()->OneShotOperations.nextWindow(tiny,Instant.EPOCH)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("time range");
    }
    @Test void failedAutomaticClaimAndLifecycleChangesDoNotUnfreezeGrid() {
        var clock=new MutableClock();
        try(var f=new Fixture(clock)) {
            f.qualify(); var s=grid(f.config,Instant.EPOCH,f.config.period(),true);
            f.operations.configure(1,s);
            when(f.coordinator.prepare(anyLong(),any(),any())).thenReturn(Mono.error(new IllegalStateException("network failure")));
            clock.now=Instant.EPOCH.plusSeconds(280); f.operations.tick();
            f.operations.stop(); f.operations.start();
            f.operations.configure(2,grid(s,s.anchor(),s.period(),false));
            assertThatThrownBy(()->f.operations.configure(3,grid(s,s.anchor().plusSeconds(1),s.period(),false)))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(f.store.snapshot().orElseThrow().revision()).isEqualTo(3);
            assertThat(f.operations.configure(3,s).revision()).isEqualTo(4);
        }
    }
    @Test void sameGridCanResumeAfterAnAutomaticEpochAdvancedTheFence() {
        var clock=new MutableClock();
        try(var f=new Fixture(clock)) {
            f.qualify();var s=grid(f.config,Instant.EPOCH,f.config.period(),true);
            f.operations.configure(1,s);clock.now=Instant.EPOCH.plusSeconds(280);f.operations.tick();
            when(f.coordinator.lastPreparedEpoch()).thenReturn(1L);
            f.operations.configure(2,grid(s,s.anchor(),s.period(),false));
            assertThat(f.operations.configure(3,s).revision()).isEqualTo(4);
            clock.now=Instant.EPOCH.plusSeconds(580);f.operations.tick();
            verify(f.coordinator).prepare(2,Instant.EPOCH.plusSeconds(600),Instant.EPOCH.plusSeconds(900));
            f.operations.configure(4,grid(s,s.anchor(),s.period(),false));
            f.sample(20);when(f.coordinator.lastPreparedEpoch()).thenReturn(20L);
            assertThatThrownBy(()->f.operations.configure(5,s)).hasMessageContaining("manual epoch");
            assertThat(f.store.snapshot().orElseThrow().revision()).isEqualTo(5);
        }
    }
    @Test void gridCanChangeBeforeFirstAutomaticClaim() {
        var clock=new MutableClock();
        try(var f=new Fixture(clock)) {
            f.qualify(); var s=grid(f.config,Instant.EPOCH.plusSeconds(1),f.config.period(),true);
            assertThat(f.operations.configure(1,s).settings()).isEqualTo(s);
            var changed=grid(s,Instant.EPOCH.plusSeconds(2),s.period().plusSeconds(1),true);
            assertThat(f.operations.configure(2,changed).settings()).isEqualTo(changed);
        }
    }
    @Test void staleRevisionRemainsARevisionConflict() {
        try(var f=new Fixture(new MutableClock())) {
            assertThatThrownBy(()->f.operations.configure(0,f.config))
                    .isInstanceOf(OneShotConfigurationStore.RevisionConflict.class);
            assertThat(f.store.snapshot().orElseThrow().revision()).isEqualTo(1);
        }
    }
    @Test void manualEpochFenceRejectsAStaleScheduledCandidate() {
        var clock=new MutableClock();
        try(var f=new Fixture(clock)) {
            f.qualify();clock.now=Instant.EPOCH.plusSeconds(280);
            when(f.coordinator.lastPreparedEpoch()).thenReturn(19L);
            var stale=grid(f.config,Instant.EPOCH,f.config.period(),true);
            assertThatThrownBy(()->f.operations.configure(1,stale)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("manual epoch");
            assertThat(f.store.snapshot().orElseThrow().revision()).isEqualTo(1);
            var future=grid(f.config,Instant.EPOCH.minusSeconds(6000),f.config.period(),true);
            assertThat(f.operations.configure(1,future).revision()).isEqualTo(2);
        }
    }
    @Test void cannotEnableSchedulingDuringAnActiveManualPreparation() {
        try(var f=new Fixture(new MutableClock())) {
            f.qualify();when(f.actuator.prepare(any(),any())).thenReturn(Mono.never());
            var start=Instant.EPOCH.plusSeconds(300);
            var preparation=f.operations.prepare(20,new OneShotOperations.Window(start,start.plusSeconds(300)),false).subscribe();
            try {
                var scheduled=grid(f.config,Instant.EPOCH,f.config.period(),true);
                assertThatThrownBy(()->f.operations.configure(1,scheduled)).isInstanceOf(IllegalStateException.class);
                assertThat(f.store.snapshot().orElseThrow().revision()).isEqualTo(1);
            } finally { preparation.dispose(); }
        }
    }
    @Test void frozenGridReturnsHttp409WithoutConsumingRevision() throws Exception {
        var clock=new MutableClock();
        try(var f=new Fixture(clock)) {
            f.qualify();var scheduled=grid(f.config,Instant.EPOCH,f.config.period(),true);
            f.operations.configure(1,scheduled);clock.now=Instant.EPOCH.plusSeconds(280);f.operations.tick();
            var controller=new OneShotController(f.operations,f.profiles,f.events,
                    new ClockHealth(Duration.ofMillis(100),Duration.ofSeconds(10),clock::instant));
            var client=org.springframework.test.web.reactive.server.WebTestClient.bindToController(controller).build();
            var changed=grid(scheduled,Instant.EPOCH.plusSeconds(1),scheduled.period(),true);
            byte[] body=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsBytes(changed);
            client.put().uri("/v1/admin/offload/one-shot/config").header("If-Match","2")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue(body).exchange()
                    .expectStatus().isEqualTo(409).expectBody().jsonPath("$.error").isEqualTo("ONE_SHOT_CONFLICT");
            assertThat(f.store.snapshot().orElseThrow().revision()).isEqualTo(2);
        }
    }
    @Test void firstTickAndConfigurationRaceKeepOneConsistentGrid() throws Exception {
        var clock=new MutableClock();
        try(var f=new Fixture(clock);var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            f.qualify();var original=grid(f.config,Instant.EPOCH,f.config.period(),true);
            f.operations.configure(1,original);
            var changed=grid(original,Instant.EPOCH.minusSeconds(1),original.period(),true);
            var barrier=new java.util.concurrent.CyclicBarrier(2);
            clearInvocations(f.inputs);
            clock.now=Instant.EPOCH.plusSeconds(280);
            var update=executor.submit(()->{
                barrier.await();
                try { f.operations.configure(2,changed);return true; }
                catch(IllegalStateException conflict) { return false; }
            });
            var tick=executor.submit(()->{barrier.await();f.operations.tick();return true;});
            boolean accepted=update.get(2,java.util.concurrent.TimeUnit.SECONDS);
            tick.get(2,java.util.concurrent.TimeUnit.SECONDS);
            var selected=accepted?changed:original;
            assertThat(f.store.snapshot().orElseThrow().settings()).isEqualTo(selected);
            verify(f.inputs).pin(eq(1L),eq(selected.anchor().plusSeconds(300)),eq(selected.anchor().plusSeconds(600)),
                    argThat(snapshot->snapshot.settings().equals(selected)));
        }
    }
    static PlanActivation activation(PlanActivation.Status status) {
        return new PlanActivation(status,null,Map.of(),Map.of(),"fixture",Instant.now());
    }
    static OneShotSettings scheduled(OneShotSettings s,EpochSettings negotiation,long states,long bytes,Duration preparation) {
        return new OneShotSettings(s.schemaVersion(),s.profileId(),s.environmentFingerprint(),s.purpose(),s.allowSynthetic(),s.cloudUri(),s.memoryCapacityMiB(),s.flowQuantum(),s.period(),s.leadTime(),true,s.anchor(),preparation,s.maxOperationalFraction(),s.burst(),s.functions(),negotiation,states,bytes);
    }
    @Test void onlyTwentyCompleteSuccessfulSamplesQualifyScheduling() {
        try(var f=new Fixture()) {
            for(int i=0;i<19;i++) f.sample(i);
            var scheduled=scheduled(f.config,f.config.negotiation(),f.config.maxSolverStates(),f.config.maxSolverBytes(),f.config.preparationBudget());
            assertThatThrownBy(()->f.operations.configure(1,scheduled)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("20 complete");
            f.sample(19);
            assertThat(f.operations.configure(1,scheduled).settings().scheduled()).isTrue();
        }
    }
    @Test void degradedAndDeadlineOutcomesRemainCensoredAndDoNotQualify() {
        try(var f=new Fixture()) {
            for(int i=0;i<19;i++) f.sample(i);
            when(f.actuator.prepare(any(),any())).thenReturn(Mono.just(activation(PlanActivation.Status.DEGRADED)));
            f.sample(19);
            when(f.coordinator.prepare(anyLong(),any(),any())).thenReturn(Mono.just(new EpochOutcome(20,EpochOutcome.Status.DEADLINE,null,10,5,1,"deadline")));
            f.sample(20);
            assertThatThrownBy(()->f.operations.configure(1,scheduled(f.config,f.config.negotiation(),f.config.maxSolverStates(),f.config.maxSolverBytes(),f.config.preparationBudget()))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("20 complete");
        }
    }
    @Test void solverAndNegotiationPolicyChangesRequireFreshQualification() {
        try(var f=new Fixture()) {
            f.qualify();var s=f.config;var n=s.negotiation();
            var changed=new EpochSettings(n.auctionBudget(),n.peerTimeout(),n.solverBudget(),n.maxRounds()+1,n.parallelism(),n.queueCapacity(),n.maxPeers(),n.maxAuctionFraction());
            for(var candidate:List.of(
                    scheduled(s,n,s.maxSolverStates()+1,s.maxSolverBytes(),s.preparationBudget()),
                    scheduled(s,n,s.maxSolverStates(),s.maxSolverBytes()+1,s.preparationBudget()),
                    scheduled(s,changed,s.maxSolverStates(),s.maxSolverBytes(),s.preparationBudget()),
                    scheduled(s,n,s.maxSolverStates(),s.maxSolverBytes(),s.preparationBudget().plusMillis(1)))) {
                assertThatThrownBy(()->f.operations.configure(1,candidate)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("20 complete");
            }
        }
    }
    @Test void peerIncarnationAndProfileChangesInvalidateMeasuredSamples() {
        try(var f=new Fixture()) {
            f.qualify();var s=f.config;
            when(f.peers.activeNeighbors()).thenReturn(List.of(new PeerEndpoint("other","new-inc",URI.create("http://other:8080"))));
            assertThatThrownBy(()->f.operations.configure(1,scheduled(s,s.negotiation(),s.maxSolverStates(),s.maxSolverBytes(),s.preparationBudget()))).isInstanceOf(IllegalArgumentException.class);
            when(f.peers.activeNeighbors()).thenReturn(List.of());
            when(f.profiles.compatible(any())).thenReturn(new ServiceProfileStore.Stored(2,"changed",null,1));
            assertThatThrownBy(()->f.operations.configure(1,scheduled(s,s.negotiation(),s.maxSolverStates(),s.maxSolverBytes(),s.preparationBudget()))).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
