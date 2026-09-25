package it.unimib.datai.nanofaas.controlplane.execution;

import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.assertThat;

class ReviewAccountingRegressionTest {
    private static Outcome outcome(Object value) {
        return new Outcome(ExecutionState.SUCCESS, 0, 1, value, null, null, null,
                200, 0, false, true);
    }

    @Test
    void aMegabyteBigIntegerMustBeWeighedOrDeclined() {
        BigInteger payload = BigInteger.ONE.shiftLeft(8 * 1024 * 1024);
        var frozen = OutcomeWeigher.freeze("id", outcome(payload));
        System.out.println("BIG_INTEGER payloadMagnitudeBytes=" + (payload.bitLength() / 8)
                + " estimated=" + (frozen == null ? "declined" : frozen.weight()));
        assertThat(frozen == null || frozen.weight() >= 1024 * 1024).isTrue();
    }

    @Test
    void nullListMustIncludeTheBackingArrayReferences() {
        var payload = new ArrayList<>(Collections.nCopies(256, null));
        var frozen = OutcomeWeigher.freeze("id", outcome(payload));
        System.out.println("NULL_LIST entries=256 estimated=" + frozen.weight());
        assertThat(frozen.weight()).isGreaterThanOrEqualTo(256L * 4);
    }

    @Test
    void mutableNumberMustNotChangeTheArchivedResult() {
        AtomicInteger original = new AtomicInteger(1);
        var frozen = OutcomeWeigher.freeze("id", outcome(original));
        if (frozen == null) return;
        original.set(99);
        assertThat(((Number) frozen.outcome().output()).intValue()).isEqualTo(1);
    }

    @Test
    void aTreeWithinPerContainerLimitsStillHasATotalTraversalBudget() {
        AtomicInteger visited = new AtomicInteger();
        var root = new ArrayList<Object>();
        for (int i = 0; i < 256; i++) {
            root.add(new java.util.AbstractList<Object>() {
                @Override public int size() { return 256; }
                @Override public Object get(int index) { visited.incrementAndGet(); return null; }
            });
        }
        assertThat(OutcomeWeigher.freeze("id", outcome(root))).isNull();
        assertThat(visited.get()).isLessThanOrEqualTo(1024);
    }

    @Test
    void delayedExpiryAfterClearMustNotReleaseANewBindingsQuota() throws Exception {
        AtomicLong clock = new AtomicLong();
        var props = new ExecutionStoreProperties(Duration.ofMinutes(5), Duration.ofMinutes(30),
                Duration.ofSeconds(30), 100, 1, 11600);
        IdempotencyStore keys = new IdempotencyStore(props, clock::get);
        keys.acquireOrGet("fn", "old");

        int workers = ForkJoinPool.getCommonPoolParallelism();
        CountDownLatch started = new CountDownLatch(workers);
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < workers; i++) {
            ForkJoinPool.commonPool().execute(() -> {
                started.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException _) { Thread.currentThread().interrupt(); }
            });
        }
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            clock.set(Duration.ofMinutes(31).toNanos());
            keys.size(); // removes old, but its real asynchronous EXPIRED listener is queued
            keys.clear();
            assertThat(keys.acquireOrGet("fn", "new").state())
                    .isEqualTo(IdempotencyStore.AcquireResult.State.CLAIMED);
        } finally { release.countDown(); }
        assertThat(ForkJoinPool.commonPool().awaitQuiescence(3, TimeUnit.SECONDS)).isTrue();
        var second = keys.acquireOrGet("fn", "second-new");
        System.out.println("KEY_CLEAR configured=1 actualKeys=" + keys.size()
                + " occupied=" + keys.occupied() + " second=" + second.state());
        assertThat(keys.size()).isLessThanOrEqualTo(1);
    }
}
