package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.*;
import it.unimib.datai.nanofaas.controlplane.capacity.*;
import it.unimib.datai.nanofaas.controlplane.deployment.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ZeroReadyDispatchTest {
    @Test
    void zeroReadyClosesDispatchAfterRunningWorkFinishesAndReopeningNotifiesTheScheduler() {
        var provider = mock(ManagedDeploymentProvider.class);
        when(provider.backendId()).thenReturn("local");
        var registry = new FunctionRegistry();
        var capacity = new FunctionCapacityRegistry();
        capacity.register("f", 2);
        var spec = new FunctionSpec("f", "image", null, null, null, null, null, null, null,
                null, ExecutionMode.DEPLOYMENT, null, null, null);
        registry.put(new RegisteredFunction(spec, new DeploymentMetadata(
                ExecutionMode.DEPLOYMENT, ExecutionMode.DEPLOYMENT, "local", null).withDesiredReplicas(2)));
        var wakeups = new AtomicInteger();
        capacity.addCapacityListener(name -> wakeups.incrementAndGet());
        try (var snapshot = ReplicaStatusSnapshot.withDefaults(InstantSource.system());
             var control = new ManagedDeploymentCoordinator(new DeploymentProviderResolver(List.of(provider),
                     new DeploymentProperties(null)), registry, new FunctionOperationLocks(), capacity, snapshot)) {
            var generation = capacity.activeGeneration("f");
            var owner = control.acquireReplicaLease(generation, "one-shot", Duration.ofMinutes(1)).orElseThrow();
            var running = capacity.tryAcquireLease(generation, ignored -> { });
            assertThat(running).isNotNull();
            assertThat(control.setReadyConcurrency(owner, 0)).isTrue();
            assertThat(capacity.inFlight("f")).isEqualTo(1);
            assertThat(capacity.tryAcquireLease(generation, ignored -> { })).isNull();
            running.release();
            assertThat(capacity.inFlight("f")).isZero();
            assertThat(capacity.tryAcquireLease(generation, ignored -> { })).isNull();
            assertThat(control.setReadyConcurrency(owner, 1)).isTrue();
            assertThat(wakeups).hasValue(1);
            var resumed = capacity.tryAcquireLease(generation, ignored -> { });
            assertThat(resumed).isNotNull();
            resumed.release();
        }
    }
}
