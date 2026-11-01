package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.*;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.deployment.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReplicaLeaseDrainTest {
    @Test
    void expiredRecoveryExcludesDispatchUntilNewReadinessDespiteGovernorUpdates() {
        try (var f = new Fixture()) {
            var expired = f.expiredLease();
            var wakeups = new AtomicInteger();
            f.capacity.addCapacityListener(name -> wakeups.incrementAndGet());
            doAnswer(call -> {
                f.capacity.setEffectiveConcurrency("f", 4);
                f.assertDispatchPaused();
                assertThat(f.control.acquireReplicaLease(expired.generation(), "other", Duration.ofSeconds(1))).isEmpty();
                return null;
            }).when(f.provider).setReplicas("f", 0);

            var recovered = f.control.recoverReplicaLease(expired, f.target, Duration.ofSeconds(1)).orElseThrow();
            assertThat(recovered).isNotEqualTo(expired);
            assertThat(f.control.ownsReplicaLease(recovered)).isTrue();
            f.capacity.setEffectiveConcurrency("f", 4);
            f.assertDispatchPaused();
            assertThat(f.control.setReadyConcurrency(expired, 2)).isFalse();
            f.assertDispatchPaused();
            assertThat(f.control.setReplicas(recovered, f.target, 2)).isTrue();
            assertThat(wakeups).hasValue(0);
            assertThat(f.control.setReadyConcurrency(recovered, 2)).isTrue();
            assertThat(wakeups).hasValue(1);
            var resumed = f.capacity.tryAcquireLease(recovered.generation(), ignored -> { });
            assertThat(resumed).isNotNull();
            resumed.release();
        }
    }

    @Test
    void recoveryWaitsForRunningDispatchAndKeepsItsOwnershipForRetry() {
        try (var f = new Fixture()) {
            var expired = f.expiredLease();
            var running = f.capacity.tryAcquireLease(expired.generation(), ignored -> { });
            assertThat(running).isNotNull();
            try {
                assertThat(f.control.recoverReplicaLease(expired, f.target, Duration.ofSeconds(1))).isEmpty();
                f.assertDispatchPaused();
                assertThat(f.capacity.inFlight("f")).isEqualTo(1);
                verify(f.provider, never()).setReplicas(anyString(), anyInt());
                assertThat(f.control.acquireReplicaLease(expired.generation(), "other", Duration.ofSeconds(1))).isEmpty();
            } finally {
                running.release();
            }
            assertThat(f.control.recoverReplicaLease(expired, f.target, Duration.ofSeconds(1))).isPresent();
            f.assertDispatchPaused();
        }
    }

    @Test
    void failedDrainStaysPausedAndCanBeRetriedWithoutReleasingOwnership() {
        try (var f = new Fixture()) {
            var expired = f.expiredLease();
            doThrow(new IllegalStateException("provider unavailable")).doNothing().when(f.provider).setReplicas("f", 0);
            assertThatThrownBy(() -> f.control.recoverReplicaLease(expired, f.target, Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalStateException.class).hasMessage("provider unavailable");
            f.capacity.setEffectiveConcurrency("f", 4);
            f.assertDispatchPaused();
            assertThat(f.control.acquireReplicaLease(expired.generation(), "other", Duration.ofSeconds(1))).isEmpty();
            when(f.provider.getReplicaStatus("f")).thenReturn(new ReplicaStatus(0, 1));
            assertThat(f.control.recoverReplicaLease(expired, f.target, Duration.ofSeconds(1))).isEmpty();
            f.assertDispatchPaused();
            when(f.provider.getReplicaStatus("f")).thenReturn(new ReplicaStatus(0, 0));
            assertThat(f.control.recoverReplicaLease(expired, f.target, Duration.ofSeconds(1))).isPresent();
        }
    }

    @Test
    void explicitReleaseAlsoPausesDrainThenReturnsDispatchToOrdinaryControl() {
        try (var f = new Fixture()) {
            var expired = f.expiredLease();
            doAnswer(call -> {
                f.capacity.setEffectiveConcurrency("f", 4);
                f.assertDispatchPaused();
                return null;
            }).when(f.provider).setReplicas("f", 0);
            assertThat(f.control.drainAndReleaseReplicaLease(expired, f.target)).isTrue();
            assertThat(f.capacity.state("f").canDispatch()).isTrue();
            assertThat(f.control.acquireReplicaLease(expired.generation(), "next", Duration.ofSeconds(1))).isPresent();
        }
    }

    @Test
    void obsoleteLeaseOrTargetCannotPauseTheCurrentFunction() {
        try (var f = new Fixture()) {
            var original = f.control.acquireReplicaLease(f.capacity.activeGeneration("f"), "owner", Duration.ofSeconds(1)).orElseThrow();
            var renewed = f.control.renewReplicaLease(original, Duration.ofSeconds(1)).orElseThrow();
            assertThat(f.control.recoverReplicaLease(original, f.target, Duration.ofSeconds(1))).isEmpty();
            assertThat(f.control.recoverReplicaLease(renewed, new ManagedDeploymentTarget("f", "other"), Duration.ofSeconds(1))).isEmpty();
            assertThat(f.capacity.state("f").canDispatch()).isTrue();
            f.capacity.remove("f");
            f.capacity.register("f", 4);
            assertThat(f.control.recoverReplicaLease(renewed, f.target, Duration.ofSeconds(1))).isEmpty();
            assertThat(f.control.drainAndReleaseReplicaLease(renewed, f.target)).isTrue();
            assertThat(f.capacity.state("f").canDispatch()).isTrue();
            verify(f.provider, never()).setReplicas(anyString(), anyInt());
        }
    }

    @Test
    void recoveryBudgetExhaustedByDrainRetainsTheOldLeaseForAnotherAttempt() {
        try (var f = new Fixture()) {
            var expired = f.expiredLease();
            doAnswer(call -> {
                f.nano.addAndGet(Duration.ofSeconds(2).toNanos());
                return null;
            }).when(f.provider).setReplicas("f", 0);
            assertThat(f.control.recoverReplicaLease(expired, f.target, Duration.ofSeconds(1))).isEmpty();
            f.assertDispatchPaused();
            assertThat(f.control.acquireReplicaLease(expired.generation(), "other", Duration.ofSeconds(1))).isEmpty();
            assertThat(f.control.recoverReplicaLease(expired, f.target, Duration.ofSeconds(1))).isPresent();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ManagedDeploymentProvider provider = mock(ManagedDeploymentProvider.class);
        final FunctionCapacityRegistry capacity = new FunctionCapacityRegistry();
        final AtomicLong nano = new AtomicLong(100);
        final ManagedDeploymentTarget target = new ManagedDeploymentTarget("f", "local");
        final ReplicaStatusSnapshot snapshot = ReplicaStatusSnapshot.withDefaults(InstantSource.system());
        final ManagedDeploymentCoordinator control;

        Fixture() {
            when(provider.backendId()).thenReturn("local");
            when(provider.getReplicaStatus("f")).thenReturn(new ReplicaStatus(0, 0));
            var registry = new FunctionRegistry();
            capacity.register("f", 4);
            var spec = new FunctionSpec("f", "image", null, null, null, null, null, null, null,
                    null, ExecutionMode.DEPLOYMENT, null, null, null);
            registry.put(new RegisteredFunction(spec, new DeploymentMetadata(
                    ExecutionMode.DEPLOYMENT, ExecutionMode.DEPLOYMENT, "local", null).withDesiredReplicas(2)));
            control = new ManagedDeploymentCoordinator(new DeploymentProviderResolver(List.of(provider), new DeploymentProperties(null)),
                    registry, new FunctionOperationLocks(), capacity, snapshot, nano::get);
        }

        ReplicaControlLease expiredLease() {
            var lease = control.acquireReplicaLease(capacity.activeGeneration("f"), "one-shot", Duration.ofSeconds(1)).orElseThrow();
            nano.addAndGet(Duration.ofSeconds(2).toNanos());
            assertThat(control.renewReplicaLease(lease, Duration.ofSeconds(1))).isEmpty();
            return lease;
        }

        void assertDispatchPaused() {
            assertThat(capacity.state("f").canDispatch()).isFalse();
            var accidental = capacity.tryAcquireLease(capacity.activeGeneration("f"), ignored -> { });
            if (accidental != null) accidental.release();
            assertThat(accidental).isNull();
            var standalone = capacity.tryAcquireLease("f", 4);
            if (standalone != null) standalone.release();
            assertThat(standalone).isNull();
        }

        @Override public void close() { control.close(); snapshot.close(); }
    }
}
