package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.*;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.deployment.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ReplicaAdmissionExpiryTest {
    @Test
    void expiryDuringProviderResumeClosesAdmissionAndRetainsOwnership() {
        var provider = mock(ManagedDeploymentProvider.class);
        when(provider.backendId()).thenReturn("local");
        when(provider.supportsEpochReplicaControl("f")).thenReturn(true);
        var registry = new FunctionRegistry();
        var capacities = new FunctionCapacityRegistry();
        capacities.register("f", 4);
        var spec = new FunctionSpec("f", "image", null, null, null, null, null, null, null,
                null, ExecutionMode.DEPLOYMENT, null, null, null);
        registry.put(new RegisteredFunction(spec,
                new DeploymentMetadata(ExecutionMode.DEPLOYMENT, ExecutionMode.DEPLOYMENT, "local", null).withDesiredReplicas(1)));
        var target = new ManagedDeploymentTarget("f", "local");
        var nano = new AtomicLong(100);
        var admission = new ReplicaAdmissionToken("issued");
        when(provider.pauseReplicaAdmission("f")).thenReturn(Optional.of(new ReplicaAdmissionToken("closed")));
        org.mockito.stubbing.Answer<Boolean> expire = call -> {
            nano.addAndGet(Duration.ofSeconds(2).toNanos());
            return true;
        };
        when(provider.resumeRetainedReplicas("f", admission, "issued")).thenAnswer(expire);
        try (var snapshot = ReplicaStatusSnapshot.withDefaults(InstantSource.system());
             var control = new ManagedDeploymentCoordinator(new DeploymentProviderResolver(List.of(provider), new DeploymentProperties(null)),
                     registry, new FunctionOperationLocks(), capacities, snapshot, nano::get)) {
            var lease = control.acquireReplicaLease(capacities.activeGeneration("f"), "one-shot", Duration.ofSeconds(1)).orElseThrow();
            var token = new ReplicaQuiesceToken(lease, target, admission);
            boolean resumed = control.resumeRetainedReplicas(lease, target, token, "issued");
            assertThat(resumed).isFalse();
            verify(provider).pauseReplicaAdmission("f");
            assertThat(control.acquireReplicaLease(lease.generation(), "other", Duration.ofSeconds(1))).isEmpty();
        }
    }
}
