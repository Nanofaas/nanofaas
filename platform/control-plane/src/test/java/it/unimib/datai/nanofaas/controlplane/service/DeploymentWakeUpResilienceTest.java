package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentWakeUpCoordinator;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentWakeUpProperties;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaObservation;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatus;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatusSnapshot.ReplicaStatusUnavailableException;
import it.unimib.datai.nanofaas.controlplane.registry.DeploymentMetadata;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistry;
import it.unimib.datai.nanofaas.controlplane.registry.ManagedDeploymentCoordinator;
import it.unimib.datai.nanofaas.controlplane.registry.RegisteredFunction;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A wake-up is for a function at zero replicas: a provider read that fails once, or a caller that
 * arrives late, must not turn into a failed invocation of a function that is, or is about to be, up.
 */
class DeploymentWakeUpResilienceTest {

    private static final ManagedDeploymentTarget TARGET = new ManagedDeploymentTarget("echo", "k8s");

    private final FunctionRegistry registry = mock(FunctionRegistry.class);
    private final ManagedDeploymentCoordinator coordinator = mock(ManagedDeploymentCoordinator.class);
    private final FunctionCapacityRegistry generations = new FunctionCapacityRegistry();
    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final DeploymentWakeUpCoordinator wakeUps = new DeploymentWakeUpCoordinator(generations, scheduler);

    @BeforeEach
    void registerAScaleToZeroDeployment() {
        generations.register("echo", 1);
        RegisteredFunction function = new RegisteredFunction(spec(),
                new DeploymentMetadata(ExecutionMode.DEPLOYMENT, ExecutionMode.DEPLOYMENT, "k8s", null));
        when(registry.getRegistered("echo")).thenReturn(Optional.of(function));
        when(coordinator.generationOf(any(RegisteredFunction.class)))
                .thenAnswer(invocation -> generations.activeGeneration("echo"));
    }

    @AfterEach
    void stop() {
        workers.shutdownNow();
        scheduler.shutdownNow();
    }

    /**
     * Problem 1: the snapshot's reading expired (STALE) while the function kept running. The gate asks
     * the provider again, and one failed answer used to fail every invocation waiting on it.
     */
    @Test
    void aRunningFunctionIsServedOnItsLastReadyReadingWhenOneProviderReadFails() {
        when(coordinator.observeReplicaStatus(TARGET)).thenReturn(
                ReplicaObservation.stale(new ReplicaStatus(1, 1), Instant.now().minusSeconds(6)));
        when(coordinator.getFreshReplicaStatus(TARGET))
                .thenThrow(new ReplicaStatusUnavailableException("the API server did not answer in time"));

        CompletableFuture<Void> ready = gate(Duration.ofSeconds(5)).ensureReady(task());

        assertThat(ready).succeedsWithin(Duration.ofSeconds(2));
        verify(coordinator, never()).scaleUpTo(any(FunctionGeneration.class), any(), anyInt());
    }

    /**
     * Problem 3: the function is waking (scaled to one, pod starting) and one poll fails. The wake-up
     * used to end there for every waiter; the next poll would have seen the pod ready.
     */
    @Test
    void aPollThatFailsOnceDuringAWakeUpIsRetriedUntilTheDeadline() {
        when(coordinator.observeReplicaStatus(TARGET))
                .thenReturn(ReplicaObservation.unavailable(Instant.now(), "no reading yet"));
        when(coordinator.getFreshReplicaStatus(TARGET))
                .thenReturn(new ReplicaStatus(0, 0))
                .thenThrow(new ReplicaStatusUnavailableException("the API server did not answer in time"))
                .thenReturn(new ReplicaStatus(1, 1));

        CompletableFuture<Void> ready = gate(Duration.ofSeconds(5)).ensureReady(task());

        assertThat(ready).succeedsWithin(Duration.ofSeconds(2));
        verify(coordinator, times(1)).scaleUpTo(any(FunctionGeneration.class), eq(TARGET), eq(1));
    }

    /**
     * Problem 4: a caller that joins a wake-up already under way waits its own budget, not what is
     * left of the first caller's; and the function stays protected from scale-down while it waits.
     */
    @Test
    void aCallerThatJoinsLateWaitsItsOwnBudgetAndKeepsTheFunctionProtected() throws Exception {
        AtomicBoolean podReady = new AtomicBoolean();
        when(coordinator.observeReplicaStatus(TARGET))
                .thenReturn(ReplicaObservation.unavailable(Instant.now(), "no reading yet"));
        when(coordinator.getFreshReplicaStatus(TARGET)).thenAnswer(invocation ->
                podReady.get() ? new ReplicaStatus(1, 1) : new ReplicaStatus(0, 0));
        DeploymentWakeUpGate gate = gate(Duration.ofSeconds(2));

        CompletableFuture<Void> first = gate.ensureReady(task());
        Thread.sleep(1_200);
        CompletableFuture<Void> late = gate.ensureReady(task());

        // Past the first caller's budget: it times out, the late caller keeps waiting...
        assertThat(first).failsWithin(Duration.ofSeconds(2));
        assertThat(late).isNotDone();
        // ...and the autoscaler still may not take the function to zero under it.
        assertThat(wakeUps.scaleDownIfUnprotected(generations.activeGeneration("echo"), TARGET, () -> true))
                .isFalse();

        podReady.set(true);
        assertThat(late).succeedsWithin(Duration.ofSeconds(2));
    }

    private DeploymentWakeUpGate gate(Duration timeout) {
        return new DeploymentWakeUpGate(registry, coordinator, generations,
                new DeploymentWakeUpProperties(timeout, Duration.ofMillis(20)), workers, scheduler, wakeUps,
                InstantSource.system(), System::nanoTime);
    }

    private static InvocationTask task() {
        return new InvocationTask("execution-echo", "echo", spec(), new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1, InvocationKind.SYNC);
    }

    private static FunctionSpec spec() {
        return new FunctionSpec("echo", "image", null, Map.of(), null, 1_000, 1, 10, 0, null,
                ExecutionMode.DEPLOYMENT, null, null,
                new ScalingConfig(ScalingStrategy.INTERNAL, 0, 3, List.of()));
    }
}
