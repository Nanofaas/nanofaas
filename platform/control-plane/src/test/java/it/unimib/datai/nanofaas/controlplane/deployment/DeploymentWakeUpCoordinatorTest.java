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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

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
            assertThat(coordinator.scaleDownIfUnprotected(target, () -> { })).isTrue();
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
            assertThat(coordinator.scaleDownIfUnprotected(target, () -> { })).isFalse();

            coordinator.close();
            replacement.close();
            AtomicInteger postCloseScaleDowns = new AtomicInteger();
            assertThat(coordinator.scaleDownIfUnprotected(target, postCloseScaleDowns::incrementAndGet)).isFalse();
            assertThat(postCloseScaleDowns).hasValue(0);
            assertThat(coordinator.ownedLeaseCount()).isZero();
            assertThat(scheduler.getQueue()).isEmpty();
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void scaleDown_waitsForWakeUpCriticalSectionAndCannotRunAfterScaleUp() throws Exception {
        DeploymentWakeUpCoordinator coordinator = new DeploymentWakeUpCoordinator();
        List<Integer> replicaChanges = new CopyOnWriteArrayList<>();
        CountDownLatch downscaleEntered = new CountDownLatch(1);
        CountDownLatch allowDownscale = new CountDownLatch(1);
        CountDownLatch wakeUpStarted = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var downscale = executor.submit((java.util.concurrent.Callable<Boolean>) () -> coordinator.scaleDownIfUnprotected(target, () -> {
                downscaleEntered.countDown();
                await(allowDownscale);
                replicaChanges.add(0);
            }));
            assertThat(downscaleEntered.await(1, TimeUnit.SECONDS)).isTrue();

            var wakeUp = executor.submit(() -> {
                wakeUpStarted.countDown();
                coordinator.protectAndScaleUp(target, System.nanoTime() + Duration.ofSeconds(1).toNanos(),
                        () -> replicaChanges.add(1));
            });
            assertThat(wakeUpStarted.await(1, TimeUnit.SECONDS)).isTrue();
            allowDownscale.countDown();

            assertThat(downscale.get(1, TimeUnit.SECONDS)).isTrue();
            wakeUp.get(1, TimeUnit.SECONDS);
        }

        assertThat(replicaChanges).containsExactly(0, 1);
    }

    @Test
    void lease_expiresAutomaticallyAndPermitsLaterScaleDown() {
        DeploymentWakeUpCoordinator coordinator = new DeploymentWakeUpCoordinator();
        coordinator.protectAndScaleUp(target, System.nanoTime() + Duration.ofMillis(30).toNanos(), () -> { });

        assertThat(coordinator.scaleDownIfUnprotected(target, () -> { })).isFalse();
        await(() -> !coordinator.isScaleDownProtected(target.functionName()));
        assertThat(coordinator.scaleDownIfUnprotected(target, () -> { })).isTrue();
    }

    @Test
    void lease_cleanupFromAnOlderWakeUpCannotRemoveReplacementLease() {
        DeploymentWakeUpCoordinator coordinator = new DeploymentWakeUpCoordinator();
        coordinator.protectAndScaleUp(target, System.nanoTime() + Duration.ofMillis(30).toNanos(), () -> { });
        coordinator.protectAndScaleUp(target, System.nanoTime() + Duration.ofMillis(150).toNanos(), () -> { });

        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(60));

        assertThat(coordinator.isScaleDownProtected(target.functionName())).isTrue();
        await(() -> !coordinator.isScaleDownProtected(target.functionName()));
    }

    private static void await(Condition condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!condition.test() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        assertThat(condition.test()).isTrue();
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

    @FunctionalInterface
    private interface Condition {
        boolean test();
    }
}
