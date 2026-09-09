package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProperties;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProviderResolver;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import it.unimib.datai.nanofaas.controlplane.deployment.MutableInstantSource;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaObservation;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatus;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatusSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManagedDeploymentCoordinatorTest {

    private final ManagedDeploymentProvider provider = mock(ManagedDeploymentProvider.class);
    private final FunctionRegistry registry = spy(new FunctionRegistry());
    private final FunctionOperationLocks locks = new FunctionOperationLocks();
    private final ManagedDeploymentCoordinator coordinator = new ManagedDeploymentCoordinator(
            new DeploymentProviderResolver(List.of(provider), new DeploymentProperties(null)),
            registry,
            locks
    );
    private final ManagedDeploymentTarget target = new ManagedDeploymentTarget("fn", "k8s");

    @BeforeEach
    void setUp() {
        when(provider.backendId()).thenReturn("k8s");
    }

    @Test
    void delegatesOperationsUsingTheTargetBackend() {
        ManagedDeploymentCoordinator coordinator = coordinatorWithSnapshot();
        registry.put(managedFunction("fn", 1));
        when(provider.getReadyReplicas("fn")).thenReturn(2);
        when(provider.getReplicaStatus("fn")).thenReturn(new ReplicaStatus(3, 2));

        coordinator.setReplicas(target, 3);

        assertThat(observedStatus(coordinator, target)).isEqualTo(new ReplicaStatus(3, 2));
        coordinator.deprovision(target);

        verify(provider).setReplicas("fn", 3);
        verify(provider).deprovision("fn");
    }

    @Test
    void rejectsMissingTargetValues() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ManagedDeploymentTarget("fn", " "));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ManagedDeploymentTarget(null, "k8s"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ManagedDeploymentTarget(" ", "k8s"));
    }

    @Test
    void setReplicasPersistsTargetBeforeApplyingProviderChange() {
        registry.put(managedFunction("fn", 1));

        coordinator.setReplicas(target, 3);

        var order = inOrder(registry, provider);
        order.verify(registry).put(argThat((RegisteredFunction function) -> function.desiredReplicas() == 3));
        order.verify(provider).setReplicas("fn", 3);
    }

    @Test
    void providerFailureRestoresThePreviousDurableTarget() {
        registry.put(managedFunction("fn", 1));
        doThrow(new IllegalStateException("scale failed")).when(provider).setReplicas("fn", 3);

        assertThatThrownBy(() -> coordinator.setReplicas(target, 3))
                .hasMessageContaining("scale failed");
        assertThat(registry.getRegistered("fn")).get()
                .extracting(RegisteredFunction::desiredReplicas)
                .isEqualTo(1);
    }

    @Test
    void setReplicasSerializesWithRemovalSoItNeverScalesARemovedFunction() throws Exception {
        FunctionRegistry sharedRegistry = new FunctionRegistry();
        FunctionOperationLocks sharedLocks = new FunctionOperationLocks();
        DeploymentProviderResolver resolver =
                new DeploymentProviderResolver(List.of(provider), new DeploymentProperties(null));
        ManagedDeploymentCoordinator sharedCoordinator =
                new ManagedDeploymentCoordinator(resolver, sharedRegistry, sharedLocks);
        FunctionRegistrationListener listener = mock(FunctionRegistrationListener.class);
        FunctionService service = new FunctionService(
                sharedRegistry,
                new FunctionDefaults(30000, 4, 100, 3),
                ImageValidator.noOp(),
                List.of(listener),
                resolver,
                sharedLocks,
                sharedCoordinator
        );

        CountDownLatch removalEntered = new CountDownLatch(1);
        CountDownLatch releaseRemoval = new CountDownLatch(1);
        doAnswer(invocation -> {
            removalEntered.countDown();
            await(releaseRemoval);
            return null;
        }).when(listener).onRemove("fn");

        sharedRegistry.put(managedFunction("fn", 1));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<FunctionSpec>> removeFuture = executor.submit(() -> service.remove("fn"));
            assertThat(removalEntered.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Boolean> scaleFuture = executor.submit(() -> {
                return sharedCoordinator.setReplicas(target, 3);
            });

            // The autoscaler-style scale must stay blocked while removal holds the shared lock.
            assertThatThrownBy(() -> scaleFuture.get(100, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            releaseRemoval.countDown();
            assertThat(removeFuture.get(5, TimeUnit.SECONDS)).isPresent();

            assertThat(scaleFuture.get(5, TimeUnit.SECONDS)).isFalse();
            verify(provider, never()).setReplicas("fn", 3);
            verify(provider).deprovision("fn");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void observeReplicaStatus_servesOneProviderReadAcrossRepeatedReadsWithinTheTtl() {
        ManagedDeploymentCoordinator cached = coordinatorWithSnapshot();
        registry.put(managedFunction("fn", 1));
        when(provider.getReplicaStatus("fn")).thenReturn(new ReplicaStatus(3, 2));

        assertThat(observedStatus(cached, target)).isEqualTo(new ReplicaStatus(3, 2));
        assertThat(observedStatus(cached, target)).isEqualTo(new ReplicaStatus(3, 2));

        verify(provider, times(1)).getReplicaStatus("fn");
    }

    @Test
    void observeReplicaStatus_reportsUnavailableWhenTheProviderFails_neverZeroReplicas() {
        ManagedDeploymentCoordinator cached = coordinatorWithSnapshot();
        registry.put(managedFunction("fn", 1));
        when(provider.getReplicaStatus("fn")).thenThrow(new IllegalStateException("provider down"));

        ReplicaObservation observation = cached.observeReplicaStatus(target);

        assertThat(observation).isInstanceOf(ReplicaObservation.Unavailable.class);
        assertThat(observation.isUsable()).isFalse();
    }

    @Test
    void observeReplicaStatus_carriesDesiredAndReadyFromTheSameProviderRead() {
        ManagedDeploymentCoordinator cached = coordinatorWithSnapshot();
        registry.put(managedFunction("fn", 1));
        when(provider.getReplicaStatus("fn")).thenReturn(new ReplicaStatus(3, 2));

        ReplicaStatus status = observedStatus(cached, target);

        assertThat(status.desiredReplicas()).isEqualTo(3);
        assertThat(status.readyReplicas()).isEqualTo(2);
        verify(provider, times(1)).getReplicaStatus("fn");
    }

    @Test
    void setReplicas_invalidatesTheSnapshotSoTheNextReadRefetches() {
        ManagedDeploymentCoordinator cached = coordinatorWithSnapshot();
        registry.put(managedFunction("fn", 1));
        when(provider.getReplicaStatus("fn")).thenReturn(new ReplicaStatus(3, 2), new ReplicaStatus(4, 3));

        assertThat(observedStatus(cached, target)).isEqualTo(new ReplicaStatus(3, 2));
        cached.setReplicas(target, 4);
        assertThat(observedStatus(cached, target)).isEqualTo(new ReplicaStatus(4, 3));

        verify(provider, times(2)).getReplicaStatus("fn");
    }

    @Test
    void deprovision_invalidatesTheSnapshotSoTheNextReadRefetches() {
        ManagedDeploymentCoordinator cached = coordinatorWithSnapshot();
        registry.put(managedFunction("fn", 1));
        when(provider.getReplicaStatus("fn")).thenReturn(new ReplicaStatus(3, 2));

        assertThat(observedStatus(cached, target)).isEqualTo(new ReplicaStatus(3, 2));
        cached.deprovision(target);
        cached.observeReplicaStatus(target);

        verify(provider, times(2)).getReplicaStatus("fn");
    }

    @Test
    void invalidate_forcesTheNextReadToRefetch() {
        ManagedDeploymentCoordinator cached = coordinatorWithSnapshot();
        registry.put(managedFunction("fn", 1));
        when(provider.getReplicaStatus("fn")).thenReturn(new ReplicaStatus(3, 2));

        assertThat(observedStatus(cached, target)).isEqualTo(new ReplicaStatus(3, 2));
        cached.invalidate(target);
        cached.observeReplicaStatus(target);

        verify(provider, times(2)).getReplicaStatus("fn");
    }

    @Test
    void getFreshReplicaStatus_alwaysReachesTheProvider() {
        ManagedDeploymentCoordinator cached = coordinatorWithSnapshot();
        registry.put(managedFunction("fn", 1));
        when(provider.getReplicaStatus("fn")).thenReturn(new ReplicaStatus(3, 2));

        assertThat(cached.getFreshReplicaStatus(target)).isEqualTo(new ReplicaStatus(3, 2));
        assertThat(cached.getFreshReplicaStatus(target)).isEqualTo(new ReplicaStatus(3, 2));

        verify(provider, times(2)).getReplicaStatus("fn");
    }

    /**
     * The periodic path never blocks on a first fetch, so the cold observation is UNAVAILABLE while
     * the refresh it scheduled runs on the inline executor; the next one reports the value.
     */
    private static ReplicaStatus observedStatus(ManagedDeploymentCoordinator coordinator,
                                                ManagedDeploymentTarget target) {
        coordinator.observeReplicaStatus(target);
        ReplicaObservation observation = coordinator.observeReplicaStatus(target);
        assertThat(observation).isInstanceOf(ReplicaObservation.Available.class);
        return ((ReplicaObservation.Available) observation).status();
    }

    private ManagedDeploymentCoordinator coordinatorWithSnapshot() {
        ReplicaStatusSnapshot snapshot = new ReplicaStatusSnapshot(
                new MutableInstantSource(0).instantSource(), Duration.ofSeconds(5), Runnable::run);
        return new ManagedDeploymentCoordinator(
                new DeploymentProviderResolver(List.of(provider), new DeploymentProperties(null)),
                registry,
                locks,
                snapshot
        );
    }

    private static RegisteredFunction managedFunction(String name, int replicas) {
        FunctionSpec spec = new FunctionSpec(
                name, "img:latest",
                null, null, null, null, null, null, null, null, ExecutionMode.DEPLOYMENT, null, null, null);
        return new RegisteredFunction(spec,
                new DeploymentMetadata(ExecutionMode.DEPLOYMENT, ExecutionMode.DEPLOYMENT, "k8s", null)
                        .withDesiredReplicas(replicas));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for latch");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
