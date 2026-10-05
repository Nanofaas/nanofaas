package it.unimib.datai.nanofaas.controlplane.registry;
import it.unimib.datai.nanofaas.common.model.*;
import it.unimib.datai.nanofaas.controlplane.capacity.*;
import it.unimib.datai.nanofaas.controlplane.deployment.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class ReplicaOwnershipTest {
    @Test void ownershipBlocksOtherWritersAndSurvivesExpiryUntilPositiveDrain() {
        var provider=mock(ManagedDeploymentProvider.class); when(provider.backendId()).thenReturn("local");
        var registry=new FunctionRegistry();var capacities=new FunctionCapacityRegistry(); capacities.register("f",4);
        var generation=capacities.activeGeneration("f");var target=new ManagedDeploymentTarget("f","local");
        var spec=new FunctionSpec("f","image",null,null,null,null,null,null,null,null,ExecutionMode.DEPLOYMENT,null,null,null);
        registry.put(new RegisteredFunction(spec,new DeploymentMetadata(ExecutionMode.DEPLOYMENT,ExecutionMode.DEPLOYMENT,"local",null).withDesiredReplicas(1)));
        var nano=new AtomicLong(100);
        try(var snapshot=ReplicaStatusSnapshot.withDefaults(InstantSource.system()); var coordinator=new ManagedDeploymentCoordinator(new DeploymentProviderResolver(List.of(provider),new DeploymentProperties(null)),registry,new FunctionOperationLocks(),capacities,snapshot,nano::get)) {
            var lease=coordinator.acquireReplicaLease(generation,"one-shot",Duration.ofSeconds(1)).orElseThrow();
            assertThat(coordinator.acquireReplicaLease(generation,"other",Duration.ofSeconds(1))).isEmpty();
            assertThatThrownBy(()->coordinator.setReplicas(generation,target,2)).isInstanceOf(ReplicaOwnershipException.class);
            assertThatThrownBy(()->coordinator.setReplicas(target,2)).isInstanceOf(ReplicaOwnershipException.class);
            assertThat(coordinator.setReplicas(lease,target,2)).isTrue();
            assertThat(coordinator.setReadyConcurrency(lease,3)).isTrue();
            assertThat(capacities.effectiveConcurrency("f")).isEqualTo(3);
            assertThat(coordinator.setReadyConcurrency(lease,5)).isFalse();
            var renewed=coordinator.renewReplicaLease(lease,Duration.ofSeconds(1)).orElseThrow();
            assertThat(coordinator.setReplicas(lease,target,3)).isFalse();
            assertThat(coordinator.setReadyConcurrency(lease,2)).isFalse();
            nano.addAndGet(Duration.ofSeconds(2).toNanos()); assertThat(coordinator.setReplicas(renewed,target,3)).isFalse();
            assertThatThrownBy(()->coordinator.setReplicas(target,3)).isInstanceOf(ReplicaOwnershipException.class);
            when(provider.getReplicaStatus("f")).thenReturn(new ReplicaStatus(0,0));
            assertThat(coordinator.drainAndReleaseReplicaLease(renewed,target)).isTrue();
            assertThat(coordinator.setReplicas(target,1)).isTrue();
            var next=coordinator.acquireReplicaLease(generation,"next",Duration.ofSeconds(1)).orElseThrow();
            capacities.remove("f"); capacities.register("f",4);
            assertThat(coordinator.setReplicas(next,target,4)).isFalse();
            assertThat(coordinator.acquireReplicaLease(capacities.activeGeneration("f"),"new",Duration.ofSeconds(1))).isPresent();
        }
    }
}
