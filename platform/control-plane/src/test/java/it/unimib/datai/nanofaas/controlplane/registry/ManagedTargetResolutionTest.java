package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.*;
import it.unimib.datai.nanofaas.controlplane.capacity.*;
import it.unimib.datai.nanofaas.controlplane.deployment.*;
import it.unimib.datai.nanofaas.controlplane.scheduler.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ManagedTargetResolutionTest {
    @Test
    void externalDoesNotResolveAProvider() {
        try (var fixture = new Fixture("container-local")) {
            assertThat(fixture.coordinator.acquireInvocationTarget(fixture.task(ExecutionMode.EXTERNAL, null)))
                    .isEqualTo(ManagedTargetResolution.UNSUPPORTED);
            verify(fixture.provider, never()).acquireInvocationTarget(anyString(), anyMap(), anyString(), anyString());
        }
    }

    @Test
    void missingCapabilityIsDifferentFromNoCapacityForBothLocalProviders() {
        for (String backend : List.of("k8s", "container-local", "containerd")) {
            try (var f = new Fixture(backend)) {
                var generation = f.capacity.register("fn", 2);
                var ownership = f.capacity.tryAcquireLease(generation.generation(), ignored -> {});
                when(f.provider.acquireInvocationTarget(anyString(), anyMap(), anyString(), anyString()))
                        .thenReturn(ManagedTargetResolution.UNSUPPORTED);
                assertThat(f.coordinator.acquireInvocationTarget(f.task(ExecutionMode.DEPLOYMENT, ownership)))
                        .isEqualTo(ManagedTargetResolution.UNSUPPORTED);
                when(f.provider.acquireInvocationTarget(anyString(), anyMap(), anyString(), anyString()))
                        .thenReturn(ManagedTargetResolution.UNAVAILABLE);
                assertThat(f.coordinator.acquireInvocationTarget(f.task(ExecutionMode.DEPLOYMENT, ownership)))
                        .isEqualTo(ManagedTargetResolution.UNAVAILABLE);
                ownership.release();
            }
        }
    }

    @Test
    void recreatedFunctionCannotReceiveAnOldGenerationsAttempt() {
        try (var f = new Fixture("container-local")) {
            var generation = f.capacity.register("fn", 2);
            var ownership = f.capacity.tryAcquireLease(generation.generation(), ignored -> {});
            f.capacity.remove("fn");
            f.capacity.register("fn", 2);
            assertThat(f.coordinator.acquireInvocationTarget(f.task(ExecutionMode.DEPLOYMENT, ownership)))
                    .isEqualTo(ManagedTargetResolution.UNAVAILABLE);
            verify(f.provider, never()).acquireInvocationTarget(anyString(), anyMap(), anyString(), anyString());
            ownership.release();
        }
    }

    @Test
    void generationIsCheckedAgainImmediatelyBeforeSendingAReservedTarget() {
        try (var f = new Fixture("container-local")) {
            var generation = f.capacity.register("fn", 2).generation();
            var ownership = f.capacity.tryAcquireLease(generation, ignored -> {});
            var target = mock(ManagedInvocationTarget.class);
            when(target.markDispatched()).thenReturn(true);
            when(f.provider.acquireInvocationTarget(anyString(), anyMap(), anyString(), anyString()))
                    .thenReturn(ManagedTargetResolution.reserved(target));
            var reserved = f.coordinator.acquireInvocationTarget(f.task(ExecutionMode.DEPLOYMENT, ownership)).target();
            f.capacity.remove("fn"); f.capacity.register("fn", 2);
            assertThat(reserved.markDispatched()).isFalse();
            reserved.close(); ownership.release();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ManagedDeploymentProvider provider = mock(ManagedDeploymentProvider.class);
        final FunctionCapacityRegistry capacity = new FunctionCapacityRegistry();
        final ReplicaStatusSnapshot snapshot = ReplicaStatusSnapshot.withDefaults(InstantSource.system());
        final ManagedDeploymentCoordinator coordinator;
        Fixture(String backend) {
            when(provider.backendId()).thenReturn(backend);
            var registry = new FunctionRegistry();
            registry.put(new RegisteredFunction(spec(ExecutionMode.DEPLOYMENT),
                    new DeploymentMetadata(ExecutionMode.DEPLOYMENT, ExecutionMode.DEPLOYMENT, backend, null)));
            coordinator = new ManagedDeploymentCoordinator(
                    new DeploymentProviderResolver(List.of(provider), new DeploymentProperties(null)),
                    registry, new FunctionOperationLocks(), capacity, snapshot);
        }
        InvocationTask task(ExecutionMode mode, DispatchOwnership ownership) {
            return new InvocationTask("execution", "fn", spec(mode), new InvocationRequest("hello", null),
                    null, null, Instant.now(), 1, InvocationKind.SYNC, ownership);
        }
        @Override public void close() { coordinator.close(); snapshot.close(); }
    }
    private static FunctionSpec spec(ExecutionMode mode) {
        return new FunctionSpec("fn", "image", null, null, null, 1000, 2, 10,
                3, "http://localhost/invoke", mode, null, null, null);
    }
}
