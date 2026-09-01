package it.unimib.datai.nanofaas.controlplane.registry;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class FunctionOperationLocksTest {

    @Test
    void withLock_releasesLockEntriesWhenDone() throws Exception {
        FunctionOperationLocks locks = new FunctionOperationLocks();

        locks.withLock("fn", () -> { });
        locks.withLock("ghost", () -> { });

        assertThat(lockCount(locks)).isZero();
    }

    @Test
    void withLock_serializesCallsForTheSameFunction() throws Exception {
        FunctionOperationLocks locks = new FunctionOperationLocks();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> locks.withLock("fn", () -> {
                int now = inFlight.incrementAndGet();
                maxInFlight.accumulateAndGet(now, Math::max);
                entered.countDown();
                await(release);
                inFlight.decrementAndGet();
            }));

            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            Future<?> second = executor.submit(() -> locks.withLock("fn", () -> {
                int now = inFlight.incrementAndGet();
                maxInFlight.accumulateAndGet(now, Math::max);
                inFlight.decrementAndGet();
            }));

            // The second call must stay blocked on the per-function lock.
            Thread.sleep(100);
            assertThat(second.isDone()).isFalse();

            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(maxInFlight.get()).isEqualTo(1);
    }

    private static int lockCount(FunctionOperationLocks locks) throws Exception {
        Field locksField = FunctionOperationLocks.class.getDeclaredField("locks");
        locksField.setAccessible(true);
        ConcurrentHashMap<?, ?> map = (ConcurrentHashMap<?, ?>) locksField.get(locks);
        return map.size();
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
