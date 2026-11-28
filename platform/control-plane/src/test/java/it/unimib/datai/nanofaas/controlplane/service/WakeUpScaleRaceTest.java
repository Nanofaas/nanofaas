package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProperties;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProviderResolver;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentWakeUpCoordinator;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentWakeUpProperties;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatus;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatusSnapshot;
import it.unimib.datai.nanofaas.controlplane.registry.DeploymentMetadata;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionOperationLocks;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistry;
import it.unimib.datai.nanofaas.controlplane.registry.ManagedDeploymentCoordinator;
import it.unimib.datai.nanofaas.controlplane.registry.RegisteredFunction;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Problem 5: the wake-up decides to ask for one replica on a reading taken before it holds any lock.
 * At a scale from zero under load the autoscaler acts at the same moment, raising the target with a
 * plain {@code setReplicas}; the wake-up's one, landing after it, must not take that target back down.
 */
class WakeUpScaleRaceTest {

    private static final ManagedDeploymentTarget TARGET = new ManagedDeploymentTarget("echo", "k8s");

    @Test
    void aWakeUpDoesNotLowerATargetRaisedAfterItsReading() throws Exception {
        AtomicInteger providerReplicas = new AtomicInteger();
        ManagedDeploymentProvider provider = mock(ManagedDeploymentProvider.class);
        when(provider.backendId()).thenReturn("k8s");
        when(provider.getReplicaStatus("echo")).thenAnswer(call -> new ReplicaStatus(providerReplicas.get(), 0));
        doAnswer(call -> {
            providerReplicas.set(call.getArgument(1));
            return null;
        }).when(provider).setReplicas(anyString(), anyInt());

        FunctionRegistry registry = new FunctionRegistry();
        registry.put(new RegisteredFunction(spec(), new DeploymentMetadata(
                ExecutionMode.DEPLOYMENT, ExecutionMode.DEPLOYMENT, "k8s", null).withDesiredReplicas(0)));
        FunctionCapacityRegistry capacity = new FunctionCapacityRegistry();
        capacity.register("echo", 1);
        FunctionGeneration generation = capacity.activeGeneration("echo");

        AtomicReference<Thread> wakeUpThread = new AtomicReference<>();
        ExecutorService wakeUpWorker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "wake-up-worker");
            wakeUpThread.set(thread);
            return thread;
        });
        ExecutorService reads = Executors.newSingleThreadExecutor();
        ExecutorService downscaler = Executors.newSingleThreadExecutor();
        ScheduledThreadPoolExecutor timers = new ScheduledThreadPoolExecutor(1);
        CountDownLatch downscaleEntered = new CountDownLatch(1);
        CountDownLatch releaseDownscale = new CountDownLatch(1);
        try (ReplicaStatusSnapshot snapshot = new ReplicaStatusSnapshot(InstantSource.system(),
                     Duration.ofSeconds(5), reads, reads, Duration.ofSeconds(2));
             ManagedDeploymentCoordinator coordinator = new ManagedDeploymentCoordinator(
                     new DeploymentProviderResolver(List.of(provider), new DeploymentProperties(null)),
                     registry, new FunctionOperationLocks(), capacity, snapshot);
             DeploymentWakeUpCoordinator wakeUps = new DeploymentWakeUpCoordinator(capacity, timers)) {
            DeploymentWakeUpGate gate = new DeploymentWakeUpGate(registry, coordinator, capacity,
                    new DeploymentWakeUpProperties(Duration.ofSeconds(5), Duration.ofMillis(50)), wakeUpWorker,
                    timers, wakeUps, InstantSource.system(), System::nanoTime);

            // A scale-down under way holds the function's wake-up lock, as the autoscaler's would.
            downscaler.submit(() -> wakeUps.scaleDownIfUnprotected(generation, TARGET, () -> {
                downscaleEntered.countDown();
                awaitQuietly(releaseDownscale);
                return false;
            }));
            assertThat(downscaleEntered.await(2, TimeUnit.SECONDS)).isTrue();

            // The wake-up reads zero replicas, decides to ask for one, and waits for the lock.
            gate.ensureReady(task());
            await().atMost(Duration.ofSeconds(2)).until(() -> waitingIn(wakeUpThread.get(), "protectAndScaleUp"));

            // Meanwhile the autoscaler raises the target, as InternalScaler.scaleUp does.
            assertThat(coordinator.setReplicas(generation, TARGET, 3)).isTrue();
            releaseDownscale.countDown();

            // The wake-up has asked for its replica once it holds the protection it took with it.
            await().atMost(Duration.ofSeconds(2)).until(() -> WakeUpMeters.protectedFunctions(wakeUps) == 1);
            assertThat(registry.getRegistered("echo").orElseThrow().desiredReplicas()).isEqualTo(3);
            assertThat(providerReplicas).hasValue(3);
            gate.close();
        } finally {
            releaseDownscale.countDown();
            wakeUpWorker.shutdownNow();
            reads.shutdownNow();
            downscaler.shutdownNow();
            timers.shutdownNow();
        }
    }

    @Test
    void scaleUpToRaisesAZeroTargetAndNeverLowersAHigherOne() {
        AtomicInteger providerReplicas = new AtomicInteger();
        ManagedDeploymentProvider provider = mock(ManagedDeploymentProvider.class);
        when(provider.backendId()).thenReturn("k8s");
        doAnswer(call -> {
            providerReplicas.set(call.getArgument(1));
            return null;
        }).when(provider).setReplicas(anyString(), anyInt());
        FunctionRegistry registry = new FunctionRegistry();
        registry.put(new RegisteredFunction(spec(), new DeploymentMetadata(
                ExecutionMode.DEPLOYMENT, ExecutionMode.DEPLOYMENT, "k8s", null).withDesiredReplicas(0)));
        FunctionCapacityRegistry capacity = new FunctionCapacityRegistry();
        capacity.register("echo", 1);
        FunctionGeneration generation = capacity.activeGeneration("echo");
        try (ReplicaStatusSnapshot snapshot = ReplicaStatusSnapshot.withoutRefreshCapacity(InstantSource.system());
             ManagedDeploymentCoordinator coordinator = new ManagedDeploymentCoordinator(
                     new DeploymentProviderResolver(List.of(provider), new DeploymentProperties(null)),
                     registry, new FunctionOperationLocks(), capacity, snapshot)) {
            assertThat(coordinator.scaleUpTo(generation, TARGET, 1)).isTrue();
            assertThat(registry.getRegistered("echo").orElseThrow().desiredReplicas()).isEqualTo(1);
            assertThat(providerReplicas).hasValue(1);

            assertThat(coordinator.setReplicas(generation, TARGET, 3)).isTrue();
            assertThat(coordinator.scaleUpTo(generation, TARGET, 1)).isTrue();
            assertThat(registry.getRegistered("echo").orElseThrow().desiredReplicas()).isEqualTo(3);
            assertThat(providerReplicas).hasValue(3);
        }
    }

    private static boolean waitingIn(Thread thread, String method) {
        return thread != null
                && thread.getState() == Thread.State.WAITING
                && Arrays.stream(thread.getStackTrace()).anyMatch(frame -> frame.getMethodName().equals(method));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
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
