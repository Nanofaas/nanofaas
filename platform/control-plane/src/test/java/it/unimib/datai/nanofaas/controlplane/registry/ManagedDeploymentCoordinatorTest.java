package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProperties;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProviderResolver;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        registry.put(managedFunction("fn", 1));
        when(provider.getReadyReplicas("fn")).thenReturn(2);
        when(provider.getReplicaStatus("fn")).thenReturn(new ReplicaStatus(3, 2));

        coordinator.setReplicas(target, 3);

        assertThat(coordinator.getReadyReplicas(target)).isEqualTo(2);
        assertThat(coordinator.getReplicaStatus(target)).isEqualTo(new ReplicaStatus(3, 2));
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
