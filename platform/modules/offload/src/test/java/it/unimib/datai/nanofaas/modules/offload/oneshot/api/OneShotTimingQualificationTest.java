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
        Fixture() {
            when(profiles.compatible(any())).thenReturn(new ServiceProfileStore.Stored(1,"hash",null,1));
            when(inputs.pin(anyLong(),any(),any(),any())).thenReturn(new EpochInput(null,Map.of(),Map.of(),1,1,null,100,100,"hash"));
            when(peers.activeNeighbors()).thenReturn(List.of());
            when(peers.localEndpoint()).thenReturn(Optional.of(new PeerEndpoint("edge","inc",URI.create("http://edge:8080"))));
            when(actuator.ownedFunctions()).thenReturn(Set.of());
            when(coordinator.prepare(anyLong(),any(),any())).thenAnswer(inv->Mono.just(new EpochOutcome(inv.getArgument(0),EpochOutcome.Status.CONVERGED,null,10,5,1,"ok")));
            when(actuator.prepare(any(),any())).thenReturn(Mono.just(activation(PlanActivation.Status.PREPARED)));
            operations=new OneShotOperations(store,profiles,inputs,coordinator,actuator,events,peers,new SimpleMeterRegistry());
            operations.configure(0,config);operations.start();
        }
        void sample(long epoch) {
            var start=Instant.now().plusSeconds(20);
            operations.prepare(epoch,new OneShotOperations.Window(start,start.plusSeconds(300)),false).block();
        }
        void qualify() { for(int i=0;i<20;i++) sample(i); }
        public void close() { operations.close(); }
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
