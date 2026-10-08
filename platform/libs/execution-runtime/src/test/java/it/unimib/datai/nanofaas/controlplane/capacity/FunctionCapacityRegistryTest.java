package it.unimib.datai.nanofaas.controlplane.capacity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class FunctionCapacityRegistryTest {
    @Test
    void capacityIsBoundedAndReleaseReturnsHoldDuration() {
        AtomicLong clock = new AtomicLong(10);
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry(clock::get);
        FunctionCapacityState returned = registry.register("echo", 2);

        assertThat(returned).isSameAs(registry.state("echo"));
        assertThat(registry.configuredConcurrency("echo")).isEqualTo(2);
        assertThat(registry.inFlight("echo")).isZero();
        AtomicLong held = new AtomicLong(-1);
        var first = registry.tryAcquireLease(returned.generation(), held::set);
        var second = registry.tryAcquireLease(returned.generation(), held::set);
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(registry.tryAcquireLease(returned.generation(), held::set)).isNull();

        clock.set(25);
        first.release();
        assertThat(held).hasValue(15);
        assertThat(registry.inFlight("echo")).isEqualTo(1);
        registry.setEffectiveConcurrency("echo", 1);
        assertThat(registry.tryAcquireLease(registry.activeGeneration("echo"), ignored -> { })).isNull();
        second.release();
        assertThat(held).hasValue(15);
        second.release();
        assertThat(registry.inFlight("echo")).isZero();
    }

    @Test
    void capacityListenerFiresOnlyWhenARaiseOpensADispatchableSlot() {
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry();
        AtomicInteger notifications = new AtomicInteger();
        registry.addCapacityListener(name -> notifications.incrementAndGet());

        FunctionCapacityState state = registry.register("echo", 3);
        assertThat(state.canDispatch()).isTrue();
        assertThat(registry.tryAcquireLease(registry.activeGeneration("echo"), ignored -> { })).isNotNull();
        assertThat(registry.tryAcquireLease(registry.activeGeneration("echo"), ignored -> { })).isNotNull();

        // Lower the effective limit below in-flight: the function is now full, no slot opens.
        registry.setEffectiveConcurrency("echo", 2);
        assertThat(state.canDispatch()).isFalse();
        assertThat(notifications).hasValue(0);

        // Raise it back above in-flight: a slot opens -> exactly one notification.
        registry.setEffectiveConcurrency("echo", 3);
        assertThat(state.canDispatch()).isTrue();
        assertThat(notifications).hasValue(1);

        // A raise that leaves an already-dispatchable function dispatchable is not a capacity
        // opening and must not spam listeners (the governor ticks every function every cycle).
        registry.setEffectiveConcurrency("echo", 3);
        assertThat(notifications).hasValue(1);
    }

    @Test
    void configuredConcurrencyAndRemovalAreSafe() {
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry();
        registry.register("echo", 6);
        registry.setEffectiveConcurrency("echo", 4);
        registry.register("echo", 3);
        assertThat(registry.configuredConcurrency("echo")).isEqualTo(3);
        assertThat(registry.effectiveConcurrency("echo")).isEqualTo(3);

        registry.remove("echo");
        assertThat(registry.inFlight("echo")).isZero();
        assertThat(registry.tryAcquireLease(registry.activeGeneration("echo"), ignored -> { })).isNull();
    }

    @Test
    void lateReleaseCannotAffectReRegisteredGeneration() {
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry();
        FunctionCapacityState oldState = registry.register("echo", 1);
        assertThat(registry.tryAcquireLease(registry.activeGeneration("echo"), ignored -> { })).isNotNull();

        registry.remove("echo");
        assertThat(oldState.tryAcquireSlot()).isFalse();
        assertThat(oldState.releaseSlotAndGetHoldNanos()).isGreaterThanOrEqualTo(0);
        registry.register("echo", 1);
        assertThat(registry.tryAcquireLease(registry.activeGeneration("echo"), ignored -> { })).isNotNull();
        assertThat(oldState.releaseSlotAndGetHoldNanos()).isEqualTo(-1);
        assertThat(registry.inFlight("echo")).isEqualTo(1);
    }

    @Test
    void removedReturnedStateCannotAcquireAndLateDirectReleaseDoesNotTouchNewGeneration() {
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry();
        FunctionCapacityState oldState = registry.register("echo", 1);
        assertThat(oldState.tryAcquireSlot()).isTrue();

        registry.remove("echo");
        assertThat(oldState.tryAcquireSlot()).isFalse();

        // once the last slot drains the generation is dropped, so the next registration is new
        assertThat(oldState.releaseSlotAndGetHoldNanos()).isGreaterThanOrEqualTo(0);

        FunctionCapacityState newState = registry.register("echo", 1);
        assertThat(newState).isNotSameAs(oldState);
        assertThat(newState.tryAcquireSlot()).isTrue();
        assertThat(oldState.releaseSlotAndGetHoldNanos()).isEqualTo(-1);
        assertThat(registry.inFlight("echo")).isEqualTo(1);
    }

    @Test
    void uniqueRemovedFunctionsDoNotRetainLifecycleEntries() {
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry();

        for (int i = 0; i < 1_000; i++) {
            String functionName = "function-" + i;
            registry.register(functionName, 1);
            registry.remove(functionName);
        }

        assertThat(registry.entryCount()).isZero();
    }

    @Test
    void differentFunctionsDoNotShareLifecycleLock() throws Exception {
        CountDownLatch firstTimestampEntered = new CountDownLatch(1);
        CountDownLatch allowFirstTimestamp = new CountDownLatch(1);
        AtomicBoolean firstRead = new AtomicBoolean(true);
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry(() -> {
            if (firstRead.getAndSet(false)) {
                firstTimestampEntered.countDown();
                await(allowFirstTimestamp);
            }
            return 10;
        });
        registry.register("first", 1);
        registry.register("second", 1);

        // Own threads, not the common pool: the first task parks inside the timestamp read, and
        // on a two-core runner the pool's single worker would never free up to start the second.
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Boolean> first =
                    CompletableFuture.supplyAsync(() -> registry.tryAcquireLease("first", 1) != null, workers);
            assertThat(firstTimestampEntered.await(1, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Boolean> second =
                    CompletableFuture.supplyAsync(() -> registry.tryAcquireLease("second", 1) != null, workers);

            assertThat(second.get(1, TimeUnit.SECONDS)).isTrue();
            allowFirstTimestamp.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void removalWithoutActiveSlotsAllowsImmediateReRegistration() {
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry();
        registry.register("echo", 2);
        registry.remove("echo");

        registry.register("echo", 3);

        assertThat(registry.configuredConcurrency("echo")).isEqualTo(3);
        assertThat(registry.inFlight("echo")).isZero();
    }

    @Test
    void directDeactivationOfIdleReturnedStateRemovesRegistryEntry() {
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry();
        FunctionCapacityState state = registry.register("echo", 1);

        state.deactivate();

        assertThat(registry.state("echo")).isNull();
        assertThat(registry.entryCount()).isZero();
    }

    @Test
    void acquisitionAndReleaseKeepSlotTimestampTogether() throws Exception {
        CountDownLatch timestampEntered = new CountDownLatch(1);
        CountDownLatch allowTimestamp = new CountDownLatch(1);
        AtomicBoolean firstRead = new AtomicBoolean(true);
        FunctionCapacityState state = new FunctionCapacityRegistry(() -> {
            if (firstRead.getAndSet(false)) {
                timestampEntered.countDown();
                await(allowTimestamp);
            }
            return 10;
        }).register("timed", 1);

        // Own threads, not the common pool: the acquiring task parks inside the timestamp read,
        // and on a two-core runner the pool's single worker would never free up for the release.
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Boolean> acquire =
                    CompletableFuture.supplyAsync(state::tryAcquireSlot, workers);
            assertThat(timestampEntered.await(1, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Long> release =
                    CompletableFuture.supplyAsync(state::releaseSlotAndGetHoldNanos, workers);
            // The one thing with no signal to wait on: this proves the releasing thread
            // reaches the lock while the acquirer still holds it, and "has blocked on a
            // lock" is not observable. The latch above already replaced the wait that
            // could be waited on; this window only widens the race the test is about,
            // so a short nap here cannot make it pass falsely, only miss.
            Thread.sleep(50);   // NOSONAR (java:S2925)
            allowTimestamp.countDown();

            assertThat(acquire.get(1, TimeUnit.SECONDS)).isTrue();
            assertThat(release.get(1, TimeUnit.SECONDS)).isZero();
            assertThat(state.inFlight()).isZero();
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void concurrentAcquisitionNeverExceedsConfiguredBound() throws Exception {
        FunctionCapacityState state = new FunctionCapacityRegistry().register("concurrent", 8);
        ExecutorService workers = Executors.newFixedThreadPool(32);
        CountDownLatch ready = new CountDownLatch(32);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch acquired = new CountDownLatch(8);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger maxInFlight = new AtomicInteger();
        List<Future<?>> tasks = new ArrayList<>();

        try {
            for (int i = 0; i < 32; i++) {
                tasks.add(workers.submit(() -> {
                    ready.countDown();
                    await(start);
                    if (state.tryAcquireSlot()) {
                        maxInFlight.accumulateAndGet(state.inFlight(), Math::max);
                        acquired.countDown();
                        await(release);
                        state.releaseSlot();
                    }
                }));
            }
            assertThat(ready.await(1, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(acquired.await(1, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            workers.shutdown();
            assertThat(workers.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
            for (Future<?> task : tasks) {
                task.get(1, TimeUnit.SECONDS);
            }
        } finally {
            workers.shutdownNow();
        }

        assertThat(maxInFlight).hasValue(8);
        assertThat(state.inFlight()).isLessThanOrEqualTo(state.effectiveConcurrency());
        assertThat(state.inFlight()).isLessThanOrEqualTo(state.configuredConcurrency());
    }

    @Test
    void concurrentAcquisitionNeverExceedsLoweredEffectiveBound() throws Exception {
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry();
        registry.register("echo", 8);
        registry.setEffectiveConcurrency("echo", 2);
        ExecutorService workers = Executors.newFixedThreadPool(32);
        CountDownLatch ready = new CountDownLatch(32);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch acquired = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger maxInFlight = new AtomicInteger();
        List<Future<?>> tasks = new ArrayList<>();

        try {
            for (int i = 0; i < 32; i++) {
                tasks.add(workers.submit(() -> {
                    ready.countDown();
                    await(start);
                    var lease = registry.tryAcquireLease(registry.activeGeneration("echo"), ignored -> { });
                    if (lease != null) {
                        maxInFlight.accumulateAndGet(registry.inFlight("echo"), Math::max);
                        acquired.countDown();
                        await(release);
                        lease.release();
                    }
                }));
            }
            assertThat(ready.await(1, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(acquired.await(1, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            workers.shutdown();
            assertThat(workers.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
            for (Future<?> task : tasks) {
                task.get(1, TimeUnit.SECONDS);
            }
        } finally {
            workers.shutdownNow();
        }

        assertThat(maxInFlight).hasValue(2);
        assertThat(registry.inFlight("echo")).isLessThanOrEqualTo(registry.effectiveConcurrency("echo"));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
    @Test
    void removeAndReRegisterMintANewIdentityAndFenceTheOldLease() {
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry();
        registry.register("echo", 2);
        FunctionGeneration retiring = registry.activeGeneration("echo");
        DispatchLease oldLease = registry.tryAcquireLease("echo", 2);
        assertThat(oldLease.generation()).isEqualTo(retiring);

        registry.remove("echo");
        assertThat(registry.activeGeneration("echo"))
                .as("a retired generation is no longer the active identity")
                .isNull();

        registry.register("echo", 2);
        FunctionGeneration current = registry.activeGeneration("echo");
        assertThat(current.supersedes(retiring)).isTrue();
        assertThat(registry.tryAcquireLease("echo", 2).generation()).isEqualTo(current);
        assertThat(registry.inFlight("echo")).isEqualTo(1);

        // The late release belongs to the retired generation: it drains that one only.
        oldLease.release();

        assertThat(registry.activeGeneration("echo")).isEqualTo(current);
        assertThat(registry.inFlight("echo")).isEqualTo(1);
    }

    @Test
    void aGenerationClosesOnlyOnceItsLastSlotComesBack() {
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry();
        FunctionCapacityState state = registry.register("echo", 2);
        assertThat(state.phase()).isEqualTo(GenerationPhase.ACTIVE);
        DispatchLease lease = registry.tryAcquireLease("echo", 2);

        registry.remove("echo");

        assertThat(state.phase())
                .as("removal with work in flight retires, it does not close")
                .isEqualTo(GenerationPhase.RETIRING);

        lease.release();

        assertThat(state.phase()).isEqualTo(GenerationPhase.CLOSED);
        assertThat(registry.entryCount()).isZero();

        // A late duplicate release cannot reopen or recreate anything.
        lease.release();
        assertThat(registry.entryCount()).isZero();
    }

    @Test
    void reRegistersWhileRemovedFunctionStillDrains() {
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry();
        registry.register("fn", 2);
        var oldLease = registry.tryAcquireLease("fn", 2);
        assertThat(oldLease).isNotNull();
        registry.remove("fn");

        FunctionCapacityState state = registry.register("fn", 4);

        assertThat(state.isActive()).isTrue();
        assertThat(state.configuredConcurrency()).isEqualTo(4);
        assertThat(state.effectiveConcurrency()).isEqualTo(4);
        oldLease.release();
        assertThat(registry.inFlight("fn")).isZero();
    }

    @Test
    void observesRemovedGenerationUntilItsLeaseDrains() {
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry();
        assertThat(registry.retiredGenerationCount()).isZero();
        registry.register("fn", 1);
        DispatchLease lease = registry.tryAcquireLease("fn", 1);

        registry.remove("fn");

        assertThat(registry.retiredGenerationCount()).isEqualTo(1);
        lease.release();
        assertThat(registry.retiredGenerationCount()).isZero();
    }

    @Test
    void observesOldGenerationWhileReplacementIsActive() {
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry();
        registry.register("fn", 1);
        DispatchLease oldLease = registry.tryAcquireLease("fn", 1);
        registry.remove("fn");

        registry.register("fn", 1);

        assertThat(registry.retiredGenerationCount()).isEqualTo(1);
        oldLease.release();
        assertThat(registry.retiredGenerationCount()).isZero();
    }

}
