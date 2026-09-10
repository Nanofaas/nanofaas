package it.unimib.datai.nanofaas.controlplane.deployment;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeploymentWakeUpCoordinatorTest {

    private final ManagedDeploymentTarget target = new ManagedDeploymentTarget("echo", "k8s");

    @Test
    void leaseCloseAndExpiryRemoveQueuedTasksAndReleaseOwnershipExactlyOnce() {
        FunctionCapacityRegistry generations = new FunctionCapacityRegistry();
        generations.register("echo", 1);
        FunctionGeneration generation = generations.activeGeneration("echo");
        AtomicLong nanoTime = new AtomicLong(10);
        long firstDeadline = Duration.ofDays(1).toNanos();
        long secondDeadline = Duration.ofDays(2).toNanos();
        ScheduledThreadPoolExecutor scheduler = scheduler();
        try {
            DeploymentWakeUpCoordinator coordinator =
                    new DeploymentWakeUpCoordinator(generations, scheduler, nanoTime::get);
            DeploymentWakeUpCoordinator.WakeUpLease first = coordinator.protectAndScaleUp(
                    generation, target, firstDeadline, () -> { });

            assertThat(coordinator.ownedLeaseCount()).isEqualTo(1);
            assertThat(scheduler.getQueue()).hasSize(1);
            first.close();
            first.close();
            assertThat(coordinator.ownedLeaseCount()).isZero();
            assertThat(scheduler.getQueue()).isEmpty();

            coordinator.protectAndScaleUp(generation, target, secondDeadline, () -> { });
            nanoTime.set(secondDeadline);
            ((Runnable) scheduler.getQueue().peek()).run();
            assertThat(coordinator.ownedLeaseCount()).isZero();
            assertThat(scheduler.getQueue()).isEmpty();
            assertThat(coordinator.scaleDownIfUnprotected(generation, target, () -> true)).isTrue();
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void replacementLeaseRemovalAndCloseCannotCrossGenerationsOrLeaveTimers() {
        FunctionCapacityRegistry generations = new FunctionCapacityRegistry();
        generations.register("echo", 1);
        FunctionGeneration oldGeneration = generations.activeGeneration("echo");
        AtomicLong nanoTime = new AtomicLong(10);
        long firstDeadline = Duration.ofDays(1).toNanos();
        long secondDeadline = Duration.ofDays(2).toNanos();
        ScheduledThreadPoolExecutor scheduler = scheduler();
        try {
            DeploymentWakeUpCoordinator coordinator =
                    new DeploymentWakeUpCoordinator(generations, scheduler, nanoTime::get);
            DeploymentWakeUpCoordinator.WakeUpLease old = coordinator.protectAndScaleUp(
                    oldGeneration, target, firstDeadline, () -> { });
            generations.remove("echo");
            coordinator.removeFunctionState("echo");
            generations.register("echo", 1);
            FunctionGeneration replacementGeneration = generations.activeGeneration("echo");
            DeploymentWakeUpCoordinator.WakeUpLease replacement = coordinator.protectAndScaleUp(
                    replacementGeneration, target, secondDeadline, () -> { });

            old.close();
            assertThat(coordinator.ownedLeaseCount()).isEqualTo(1);
            assertThat(scheduler.getQueue()).hasSize(1);
            assertThat(coordinator.scaleDownIfUnprotected(replacementGeneration, target, () -> true)).isFalse();

            coordinator.close();
            replacement.close();
            AtomicInteger postCloseScaleDowns = new AtomicInteger();
            assertThat(coordinator.scaleDownIfUnprotected(replacementGeneration, target, () -> {
                postCloseScaleDowns.incrementAndGet();
                return true;
            })).isFalse();
            assertThat(postCloseScaleDowns).hasValue(0);
            assertThat(coordinator.ownedLeaseCount()).isZero();
            assertThat(scheduler.getQueue()).isEmpty();
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void scaleDown_waitsForWakeUpCriticalSectionAndCannotRunAfterScaleUp() throws Exception {
        FunctionCapacityRegistry generations = generations();
        FunctionGeneration generation = generations.activeGeneration("echo");
        ScheduledThreadPoolExecutor scheduler = scheduler();
        DeploymentWakeUpCoordinator coordinator = new DeploymentWakeUpCoordinator(generations, scheduler);
        List<Integer> replicaChanges = new CopyOnWriteArrayList<>();
        CountDownLatch downscaleEntered = new CountDownLatch(1);
        CountDownLatch allowDownscale = new CountDownLatch(1);
        CountDownLatch wakeUpStarted = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var downscale = executor.submit((java.util.concurrent.Callable<Boolean>) () -> coordinator.scaleDownIfUnprotected(generation, target, () -> {
                downscaleEntered.countDown();
                await(allowDownscale);
                replicaChanges.add(0);
                return true;
            }));
            assertThat(downscaleEntered.await(1, TimeUnit.SECONDS)).isTrue();

            var wakeUp = executor.submit(() -> {
                wakeUpStarted.countDown();
                coordinator.protectAndScaleUp(generation, target, System.nanoTime() + Duration.ofSeconds(1).toNanos(),
                        () -> replicaChanges.add(1));
            });
            assertThat(wakeUpStarted.await(1, TimeUnit.SECONDS)).isTrue();
            allowDownscale.countDown();

            assertThat(downscale.get(1, TimeUnit.SECONDS)).isTrue();
            wakeUp.get(1, TimeUnit.SECONDS);
        }

        assertThat(replicaChanges).containsExactly(0, 1);
        scheduler.shutdownNow();
    }

    @Test
    void removalRetiresImmediatelyButKeepsStateUntilBlockedScaleUpDrains() throws Exception {
        FunctionCapacityRegistry generations = generations();
        FunctionGeneration generation = generations.activeGeneration("echo");
        ScheduledThreadPoolExecutor scheduler = scheduler();
        DeploymentWakeUpCoordinator coordinator = new DeploymentWakeUpCoordinator(generations, scheduler);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> wakeUp = executor.submit(() -> coordinator.protectAndScaleUp(
                    generation, target, Long.MAX_VALUE, () -> {
                        callbackEntered.countDown();
                        await(releaseCallback);
                    }));
            assertThat(callbackEntered.await(1, TimeUnit.SECONDS)).isTrue();
            Future<?> removal = executor.submit(() -> coordinator.removeFunctionState("echo"));
            try {
                removal.get(1, TimeUnit.SECONDS);
                assertThat(coordinator.ownedStateCount()).isEqualTo(1);
                assertThat(coordinator.ownedLeaseCount()).isZero();
            } finally {
                releaseCallback.countDown();
            }
            wakeUp.get(1, TimeUnit.SECONDS);
            assertThat(coordinator.ownedStateCount()).isZero();
            assertThat(scheduler.getQueue()).isEmpty();
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void staleGenerationDownscaleNeverRunsItsCallback() {
        FunctionCapacityRegistry generations = generations();
        FunctionGeneration stale = generations.activeGeneration("echo");
        ScheduledThreadPoolExecutor scheduler = scheduler();
        DeploymentWakeUpCoordinator coordinator = new DeploymentWakeUpCoordinator(generations, scheduler);
        generations.remove("echo");
        generations.register("echo", 1);
        AtomicInteger callbacks = new AtomicInteger();

        assertThat(coordinator.scaleDownIfUnprotected(stale, target, () -> {
            callbacks.incrementAndGet();
            return true;
        })).isFalse();
        assertThat(callbacks).hasValue(0);
        scheduler.shutdownNow();
    }

    @Test
    void exactOldAndReplacementExpiryCallbacksCannotCrossLeaseOwnership() {
        FunctionCapacityRegistry generations = generations();
        FunctionGeneration generation = generations.activeGeneration("echo");
        AtomicLong nanoTime = new AtomicLong(0);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        List<Runnable> callbacks = new CopyOnWriteArrayList<>();
        List<AtomicBoolean> queuedTasks = new CopyOnWriteArrayList<>();
        AtomicInteger queueSize = new AtomicInteger();
        when(scheduler.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.NANOSECONDS)))
                .thenAnswer(invocation -> {
                    callbacks.add(invocation.getArgument(0, Runnable.class));
                    AtomicBoolean queued = new AtomicBoolean(true);
                    queuedTasks.add(queued);
                    queueSize.incrementAndGet();
                    ScheduledFuture<?> future = mock(ScheduledFuture.class);
                    when(future.cancel(false)).thenAnswer(ignored -> {
                        if (queued.compareAndSet(true, false)) queueSize.decrementAndGet();
                        return true;
                    });
                    return future;
                });
        DeploymentWakeUpCoordinator coordinator =
                new DeploymentWakeUpCoordinator(generations, scheduler, nanoTime::get);
        long oldDeadline = 10;
        long replacementDeadline = 20;

        coordinator.protectAndScaleUp(generation, target, oldDeadline, () -> { });
        coordinator.protectAndScaleUp(generation, target, replacementDeadline, () -> { });
        assertThat(queueSize).hasValue(1);

        nanoTime.set(oldDeadline);
        callbacks.get(0).run();
        assertThat(coordinator.ownedLeaseCount()).isEqualTo(1);
        assertThat(queueSize).hasValue(1);

        nanoTime.set(replacementDeadline);
        if (queuedTasks.get(1).compareAndSet(true, false)) queueSize.decrementAndGet();
        callbacks.get(1).run();
        assertThat(coordinator.ownedLeaseCount()).isZero();
        assertThat(queueSize).hasValue(0);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for downscale");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static ScheduledThreadPoolExecutor scheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    private static FunctionCapacityRegistry generations() {
        FunctionCapacityRegistry generations = new FunctionCapacityRegistry();
        generations.register("echo", 1);
        return generations;
    }
}
