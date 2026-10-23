package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.*;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.deployment.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ReplicaAdmissionOwnershipTest {
    @Test
    void onlyCurrentLeaseMayIssueAndResumeItsBoundCapability() {
        try (var fixture = new Fixture()) {
            var lease = fixture.acquire();
            var token = fixture.control.pauseReplicaAdmission(lease, fixture.target).orElseThrow();
            assertThat(token.lease()).isEqualTo(lease);
            assertThat(token.target()).isEqualTo(fixture.target);
            assertThat(token.admissionEpoch()).isEqualTo("issued");
            var foreign = new ReplicaControlLease(lease.generation(), "foreign", "other", lease.deadlineNanos());
            assertThat(fixture.control.pauseReplicaAdmission(foreign, fixture.target)).isEmpty();
            assertThat(fixture.control.resumeRetainedReplicas(foreign, fixture.target, token, "issued")).isFalse();
            verify(fixture.provider, never()).resumeRetainedReplicas(anyString(), any(), anyString());
            assertThat(fixture.control.resumeRetainedReplicas(lease, fixture.target, token, "issued")).isTrue();
            verify(fixture.provider).resumeRetainedReplicas("f", fixture.admission, "issued");
        }
    }

    @Test
    void renewalAndGenerationReplacementInvalidateOldCapabilities() {
        try (var fixture = new Fixture()) {
            var lease = fixture.acquire();
            var token = fixture.control.pauseReplicaAdmission(lease, fixture.target).orElseThrow();
            var renewed = fixture.control.renewReplicaLease(lease, Duration.ofSeconds(1)).orElseThrow();
            assertThat(fixture.control.resumeReplicas(renewed, fixture.target, token, "issued")).isFalse();
            assertThat(fixture.control.resumeReplicas(lease, fixture.target, token, "issued")).isFalse();
            fixture.capacities.remove("f");
            fixture.capacities.register("f", 4);
            assertThat(fixture.control.resumeRetainedReplicas(renewed, fixture.target, token, "issued")).isFalse();
            verify(fixture.provider, never()).resumeReplicas(anyString(), any(), anyString());
            verify(fixture.provider, never()).resumeRetainedReplicas(anyString(), any(), anyString());
        }
    }

    @Test
    void leaseExpiringDuringPhysicalQuiesceCannotAuthorizeResumeOrAnotherOwner() {
        try (var fixture = new Fixture()) {
            var lease = fixture.acquire();
            when(fixture.provider.quiesceReplicas("f", Duration.ofMillis(20))).thenAnswer(call -> {
                fixture.nano.addAndGet(Duration.ofSeconds(2).toNanos());
                return java.util.Optional.of(fixture.admission);
            });
            assertThat(fixture.control.quiesceReplicas(lease, fixture.target, Duration.ofMillis(20))).isEmpty();
            assertThat(fixture.control.acquireReplicaLease(lease.generation(), "other", Duration.ofSeconds(1))).isEmpty();
            assertThat(fixture.control.ownsReplicaLease(lease)).isFalse();
            verify(fixture.provider, never()).resumeReplicas(anyString(), any(), anyString());
        }
    }

    @Test
    void unsupportedProviderCannotIssueAnAdmissionToken() {
        try (var fixture = new Fixture()) {
            when(fixture.provider.supportsEpochReplicaControl("f")).thenReturn(false);
            assertThat(fixture.control.supportsEpochReplicaControl(fixture.target)).isFalse();
            var lease = fixture.acquire();
            assertThat(fixture.control.pauseReplicaAdmission(lease, fixture.target)).isEmpty();
            assertThat(fixture.control.quiesceReplicas(lease, fixture.target, Duration.ofMillis(20))).isEmpty();
            verify(fixture.provider, never()).pauseReplicaAdmission(anyString());
            verify(fixture.provider, never()).quiesceReplicas(anyString(), any());
        }
    }

    @Test
    void staleDeploymentTargetCannotActOnTheNewRegistration() {
        try (var fixture = new Fixture()) {
            var lease = fixture.acquire();
            var token = fixture.control.pauseReplicaAdmission(lease, fixture.target).orElseThrow();
            var changed = new ManagedDeploymentTarget("f", "other-backend");
            assertThat(fixture.control.pauseReplicaAdmission(lease, changed)).isEmpty();
            assertThat(fixture.control.resumeRetainedReplicas(lease, changed, token, "issued")).isFalse();
            verify(fixture.provider, never()).resumeRetainedReplicas(anyString(), any(), anyString());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ManagedDeploymentProvider provider = mock(ManagedDeploymentProvider.class);
        final FunctionCapacityRegistry capacities = new FunctionCapacityRegistry();
        final AtomicLong nano = new AtomicLong(100);
        final ManagedDeploymentTarget target = new ManagedDeploymentTarget("f", "local");
        final ReplicaAdmissionToken admission = new ReplicaAdmissionToken("issued");
        final ReplicaStatusSnapshot snapshot = ReplicaStatusSnapshot.withDefaults(InstantSource.system());
        final ManagedDeploymentCoordinator control;

        Fixture() {
            when(provider.backendId()).thenReturn("local");
            when(provider.supportsEpochReplicaControl("f")).thenReturn(true);
            when(provider.pauseReplicaAdmission("f")).thenReturn(java.util.Optional.of(admission));
            when(provider.resumeReplicas("f", admission, "issued")).thenReturn(true);
            when(provider.resumeRetainedReplicas("f", admission, "issued")).thenReturn(true);
            var registry = new FunctionRegistry();
            capacities.register("f", 4);
            var spec = new FunctionSpec("f", "image", null, null, null, null, null, null, null,
                    null, ExecutionMode.DEPLOYMENT, null, null, null);
            registry.put(new RegisteredFunction(spec,
                    new DeploymentMetadata(ExecutionMode.DEPLOYMENT, ExecutionMode.DEPLOYMENT, "local", null).withDesiredReplicas(1)));
            control = new ManagedDeploymentCoordinator(new DeploymentProviderResolver(List.of(provider), new DeploymentProperties(null)),
                    registry, new FunctionOperationLocks(), capacities, snapshot, nano::get);
        }

        ReplicaControlLease acquire() {
            return control.acquireReplicaLease(capacities.activeGeneration("f"), "one-shot", Duration.ofSeconds(1)).orElseThrow();
        }
        @Override public void close() { control.close(); snapshot.close(); }
    }
}
