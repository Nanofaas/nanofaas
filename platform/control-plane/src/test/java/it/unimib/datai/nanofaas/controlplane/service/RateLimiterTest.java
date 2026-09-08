package it.unimib.datai.nanofaas.controlplane.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    @Test
    void allow_underLimit_returnsTrue() {
        RateLimiter limiter = new RateLimiter();
        limiter.setMaxPerSecond(10);

        for (int i = 0; i < 10; i++) {
            assertThat(limiter.allow()).isTrue();
        }
    }

    @Test
    void allow_atLimit_returnsFalse() {
        RateLimiter limiter = new RateLimiter();
        limiter.setMaxPerSecond(10);

        for (int i = 0; i < 10; i++) {
            limiter.allow();
        }

        assertThat(limiter.allow()).isFalse();
    }

    @SuppressWarnings("java:S2925") // clock advancement: the fixed sleep must cross the 1s rate-limit window boundary so the window resets
    @Test
    void allow_afterWindowReset_allowsAgain() {
        // Steered clock, not a 1.1 s sleep: the window boundary is the thing under test, so it
        // should be crossed exactly rather than waited out.
        MutableClock clock = new MutableClock(1_000L);
        RateLimiter limiter = new RateLimiter(clock);
        limiter.setMaxPerSecond(5);

        for (int i = 0; i < 5; i++) {
            limiter.allow();
        }
        assertThat(limiter.allow()).isFalse();

        clock.advanceSeconds(1);
        assertThat(limiter.allow()).isTrue();
    }

    @Test
    void allow_underConcurrentLoad_neverExceedsLimit() throws Exception {
        int maxPerSecond = 100;
        RateLimiter limiter = new RateLimiter();
        limiter.setMaxPerSecond(maxPerSecond);

        int numThreads = 50;
        int requestsPerThread = 10;

        AtomicInteger allowedCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads);

        for (int i = 0; i < numThreads; i++) {
            Thread t = new Thread(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < requestsPerThread; j++) {
                        if (limiter.allow()) {
                            allowedCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
            t.start();
        }

        // Start all threads simultaneously
        startLatch.countDown();
        endLatch.await();

        // With 50 threads x 10 requests = 500 total requests
        // But limit is 100/second, so max 100 should be allowed
        assertThat(allowedCount.get()).isLessThanOrEqualTo(maxPerSecond);
    }

    @SuppressWarnings("java:S2925") // deliberate delay to spread concurrent calls across the 1s window: rate-limit semantics depend on real time
    /**
     * Regression coverage for the concurrent defect called out at the end of
     * docs/control-plane-review-2026-09-05.md ("A further concurrency defect is in
     * RateLimiter.allow"), originally verified there only by reading the source and first
     * reproduced by task A0. The defect was that {@link RateLimiter#allow()} updated the
     * window and reset the counter as two separate, non-atomic operations:
     * <pre>
     *   if (now &gt; currentWindow &amp;&amp; windowStartSecond.compareAndSet(currentWindow, now)) {
     *       windowCount.set(0);
     *   }
     *   return windowCount.incrementAndGet() &lt;= maxPerSecond;
     * </pre>
     * A thread that won the CAS published the new window second first and only reset the
     * counter afterwards, so a call landing in that gap saw the window already advanced while
     * the counter still held the previous window's saturated value and was wrongly rejected.
     *
     * <p>That intermediate state is structurally unreachable now that window and counter live
     * in one atomically-CAS-updated state (the rollover seeds the new window's count in the
     * same transition). This test therefore drives the exact <em>caller-observable</em>
     * precondition of the old race through the controllable clock: the previous window is
     * saturated, the clock has moved into a fresh window, and no request has been admitted
     * there yet. A correct limiter must admit the next request rather than carry the stale
     * saturated count over the boundary.
     */
    @Test
    void allow_windowRolledOverButCounterNotYetReset_wronglyRejectsAFreshRequest() {
        int maxPerSecond = 3;
        MutableClock clock = new MutableClock(1_000L);
        RateLimiter limiter = new RateLimiter(clock);
        limiter.setMaxPerSecond(maxPerSecond);

        // Saturate the current window: exactly maxPerSecond admissions, then a refusal.
        for (int i = 0; i < maxPerSecond; i++) {
            assertThat(limiter.allow()).isTrue();
        }
        assertThat(limiter.allow()).isFalse();

        // The clock moves into a fresh window. Nothing has been admitted there yet, but the
        // stored count still holds the previous window's saturated value, mirroring what a
        // caller observed in the old post-CAS/pre-reset gap.
        clock.advanceSeconds(1);

        // The fresh window admits from an empty count: no admission may be lost to the stale
        // saturated counter, and the full new-window quota is available again.
        assertThat(limiter.allow()).isTrue();
        assertThat(limiter.allow()).isTrue();
        assertThat(limiter.allow()).isTrue();
        assertThat(limiter.allow()).isFalse();
    }

    /**
     * Controlled interleaving between a window change and concurrent requests: all callers are
     * parked until the clock has been advanced past the boundary, then released together so
     * they race the rollover. Every caller belongs to the fresh window and none may be lost.
     */
    @Test
    void allow_concurrentWindowRollover_losesNoAdmission() throws Exception {
        int maxPerSecond = 8;
        MutableClock clock = new MutableClock(2_000L);
        RateLimiter limiter = new RateLimiter(clock);
        limiter.setMaxPerSecond(maxPerSecond);

        for (int i = 0; i < maxPerSecond; i++) {
            assertThat(limiter.allow()).isTrue();
        }
        assertThat(limiter.allow()).isFalse();

        int workers = maxPerSecond;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(workers);
        AtomicInteger admitted = new AtomicInteger(0);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            Thread t = new Thread(() -> {
                try {
                    startLatch.await();
                    if (limiter.allow()) {
                        admitted.incrementAndGet();
                    }
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
            threads.add(t);
            t.start();
        }

        // Window change happens while the workers are parked: they all observe the fresh
        // window on their first read and contend for the single rollover CAS.
        clock.advanceSeconds(1);
        startLatch.countDown();
        endLatch.await();

        // Every worker's request is the first, second, ... admission of the fresh window, so
        // all workers (workers == maxPerSecond) must be admitted.
        assertThat(admitted.get()).isEqualTo(workers);
    }

    /** High concurrency inside a single fixed window: exactly the limit is admitted, no more and no fewer. */
    @Test
    void allow_concurrentCallsWithinOneWindow_neverExceedLimit() throws Exception {
        int maxPerSecond = 100;
        MutableClock clock = new MutableClock(3_000L);
        RateLimiter limiter = new RateLimiter(clock);
        limiter.setMaxPerSecond(maxPerSecond);

        int numThreads = 16;
        int requestsPerThread = 500;
        AtomicInteger allowedCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads);

        for (int i = 0; i < numThreads; i++) {
            Thread t = new Thread(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < requestsPerThread; j++) {
                        if (limiter.allow()) {
                            allowedCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
            t.start();
        }

        startLatch.countDown();
        endLatch.await();

        // 8000 requests compete for 100 slots in the same (clock-fixed) window: the CAS loop
        // admits exactly maxPerSecond, never more (over-admission) and never fewer (lost updates).
        assertThat(allowedCount.get()).isEqualTo(maxPerSecond);
    }

    /** Runtime updates keep working: a lower limit binds immediately, a higher one frees capacity, across rollovers. */
    @Test
    void allow_runtimeLimitChange_isRespectedAcrossWindowRollover() {
        MutableClock clock = new MutableClock(4_000L);
        RateLimiter limiter = new RateLimiter(clock);
        limiter.setMaxPerSecond(5);

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.allow()).isTrue();
        }
        assertThat(limiter.allow()).isFalse();

        // Lower the limit at runtime: the current window is already above it, so the next
        // request is refused even before the window rolls.
        limiter.setMaxPerSecond(2);
        assertThat(limiter.allow()).isFalse();

        // After the rollover the fresh window is governed by the new, lower limit.
        clock.advanceSeconds(1);
        assertThat(limiter.allow()).isTrue();
        assertThat(limiter.allow()).isTrue();
        assertThat(limiter.allow()).isFalse();

        // Raise the limit again mid-window: capacity frees up within the same window.
        limiter.setMaxPerSecond(4);
        assertThat(limiter.allow()).isTrue();
        assertThat(limiter.allow()).isTrue();
        assertThat(limiter.allow()).isFalse();
    }

    /**
     * Pins the documented fixed-window semantics: this is not a token bucket, so a burst
     * straddling a window boundary is admitted (up to {@code maxPerSecond} per window, even if
     * the two windows are adjacent in time).
     */
    @Test
    void allow_twoAdjacentWindows_eachAdmitUpToTheLimit() {
        int maxPerSecond = 3;
        MutableClock clock = new MutableClock(5_000L);
        RateLimiter limiter = new RateLimiter(clock);
        limiter.setMaxPerSecond(maxPerSecond);

        for (int i = 0; i < maxPerSecond; i++) {
            assertThat(limiter.allow()).isTrue();
        }
        assertThat(limiter.allow()).isFalse();

        // Cross the boundary immediately: three more admissions in the adjacent window, even
        // though the six requests could be back-to-back in wall-clock time.
        clock.advanceSeconds(1);
        for (int i = 0; i < maxPerSecond; i++) {
            assertThat(limiter.allow()).isTrue();
        }
        assertThat(limiter.allow()).isFalse();
    }

    /** Controllable epoch-second source for deterministic window-boundary tests. */
    private static final class MutableClock implements LongSupplier {
        private long epochSecond;

        MutableClock(long epochSecond) {
            this.epochSecond = epochSecond;
        }

        void advanceSeconds(long delta) {
            epochSecond += delta;
        }

        @Override
        public long getAsLong() {
            return epochSecond;
        }
    }
}
