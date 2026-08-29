package it.unimib.datai.nanofaas.workloadmetrics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
        registry.register("echo", 2);

        assertThat(registry.configuredConcurrency("echo")).isEqualTo(2);
        assertThat(registry.inFlight("echo")).isZero();
        assertThat(registry.tryAcquireSlot("echo")).isTrue();
        assertThat(registry.tryAcquireSlot("echo")).isTrue();
        assertThat(registry.tryAcquireSlot("echo")).isFalse();

        clock.set(25);
        assertThat(registry.releaseSlotAndGetHoldNanos("echo")).isEqualTo(15);
        assertThat(registry.inFlight("echo")).isEqualTo(1);
        registry.setEffectiveConcurrency("echo", 1);
        assertThat(registry.tryAcquireSlot("echo")).isFalse();
        assertThat(registry.releaseSlotAndGetHoldNanos("echo")).isEqualTo(15);
        assertThat(registry.releaseSlotAndGetHoldNanos("echo")).isEqualTo(-1);
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
        assertThat(registry.tryAcquireSlot("echo")).isFalse();
    }

    @Test
    void acquisitionAndReleaseKeepSlotTimestampTogether() throws Exception {
        CountDownLatch timestampEntered = new CountDownLatch(1);
        CountDownLatch allowTimestamp = new CountDownLatch(1);
        AtomicBoolean firstRead = new AtomicBoolean(true);
        FunctionCapacityState state = new FunctionCapacityState(1, () -> {
            if (firstRead.getAndSet(false)) {
                timestampEntered.countDown();
                await(allowTimestamp);
            }
            return 10;
        });

        CompletableFuture<Boolean> acquire = CompletableFuture.supplyAsync(state::tryAcquireSlot);
        assertThat(timestampEntered.await(1, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Long> release = CompletableFuture.supplyAsync(state::releaseSlotAndGetHoldNanos);
        Thread.sleep(50);
        allowTimestamp.countDown();

        assertThat(acquire.get(1, TimeUnit.SECONDS)).isTrue();
        assertThat(release.get(1, TimeUnit.SECONDS)).isEqualTo(0);
        assertThat(state.inFlight()).isZero();
    }

    @Test
    void concurrentAcquisitionNeverExceedsConfiguredBound() throws Exception {
        FunctionCapacityState state = new FunctionCapacityState(8);
        ExecutorService workers = Executors.newFixedThreadPool(32);
        CountDownLatch ready = new CountDownLatch(32);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch acquired = new CountDownLatch(8);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger maxInFlight = new AtomicInteger();

        try {
            for (int i = 0; i < 32; i++) {
                workers.submit(() -> {
                    ready.countDown();
                    await(start);
                    if (state.tryAcquireSlot()) {
                        maxInFlight.accumulateAndGet(state.inFlight(), Math::max);
                        acquired.countDown();
                        await(release);
                        state.releaseSlot();
                    }
                });
            }
            assertThat(ready.await(1, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(acquired.await(1, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            workers.shutdown();
            assertThat(workers.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
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

        try {
            for (int i = 0; i < 32; i++) {
                workers.submit(() -> {
                    ready.countDown();
                    await(start);
                    if (registry.tryAcquireSlot("echo")) {
                        maxInFlight.accumulateAndGet(registry.inFlight("echo"), Math::max);
                        acquired.countDown();
                        await(release);
                        registry.releaseSlotAndGetHoldNanos("echo");
                    }
                });
            }
            assertThat(ready.await(1, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(acquired.await(1, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            workers.shutdown();
            assertThat(workers.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
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
}
