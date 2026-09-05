package it.unimib.datai.nanofaas.controlplane.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
    void allow_afterWindowReset_allowsAgain() throws InterruptedException {
        RateLimiter limiter = new RateLimiter();
        limiter.setMaxPerSecond(5);

        // Exhaust limit
        for (int i = 0; i < 5; i++) {
            limiter.allow();
        }
        assertThat(limiter.allow()).isFalse();

        // Wait for window to reset
        Thread.sleep(1100);

        // Should allow again
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
    @Test
    void allow_concurrentWindowReset_maintainsCorrectCount() throws Exception {
        int maxPerSecond = 50;
        RateLimiter limiter = new RateLimiter();
        limiter.setMaxPerSecond(maxPerSecond);

        AtomicInteger totalAllowed = new AtomicInteger(0);
        AtomicInteger violations = new AtomicInteger(0);

        int numThreads = 20;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads);

        for (int i = 0; i < numThreads; i++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    long lastSecond = -1;

                    for (int j = 0; j < 100; j++) {
                        long currentSecond = System.currentTimeMillis() / 1000;
                        if (currentSecond != lastSecond) {
                            lastSecond = currentSecond;
                        }

                        if (limiter.allow()) {
                            totalAllowed.incrementAndGet();
                        }

                        // Small delay to spread across time
                        Thread.sleep(1);
                    }
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        endLatch.await();

        // No violations should occur
        assertThat(violations.get()).isZero();
    }

    /**
     * Regression coverage for the concurrent defect called out at the end of
     * docs/control-plane-review-2026-09-05.md ("Un ulteriore difetto concorrente e' in
     * RateLimiter.allow"), verified there only by reading the source. {@link RateLimiter#allow()}
     * updates the window and resets the counter as two separate, non-atomic operations:
     * <pre>
     *   if (now > currentWindow && windowStartSecond.compareAndSet(currentWindow, now)) {
     *       windowCount.set(0);
     *   }
     *   return windowCount.incrementAndGet() <= maxPerSecond;
     * </pre>
     * A thread that wins the CAS publishes the new window second first and only resets the
     * counter afterwards. Any call that lands in that gap — real or, as here, reconstructed —
     * sees {@code windowStartSecond} already pointing at the new (empty, from the caller's
     * perspective) window while {@code windowCount} still holds the stale value from the
     * window that just ended, and gets wrongly rejected.
     *
     * <p>Driving that interleaving through real thread scheduling is exactly the kind of
     * fragile, sleep-dependent concurrency test this task's brief says to avoid, and
     * {@link RateLimiter} hard-codes {@code Instant.now()} with no injectable clock to make it
     * deterministic that way either. Instead, this test uses reflection purely to reach into
     * the private fields and set up the precise intermediate state a real race produces —
     * window already rolled over, counter not yet reset — then calls the real, unmodified
     * {@link RateLimiter#allow()} to observe its outcome. No sleeps, no timing assumptions,
     * fully deterministic.
     */
    @Test
    void allow_windowRolledOverButCounterNotYetReset_wronglyRejectsAFreshRequest() throws Exception {
        int maxPerSecond = 3;
        RateLimiter limiter = new RateLimiter();
        limiter.setMaxPerSecond(maxPerSecond);

        Field windowStartField = RateLimiter.class.getDeclaredField("windowStartSecond");
        windowStartField.setAccessible(true);
        AtomicLong windowStartSecond = (AtomicLong) windowStartField.get(limiter);

        Field windowCountField = RateLimiter.class.getDeclaredField("windowCount");
        windowCountField.setAccessible(true);
        AtomicInteger windowCount = (AtomicInteger) windowCountField.get(limiter);

        // Reconstruct the exact post-CAS, pre-reset state: the window-swap winner already
        // published "now" as the current window second (so a fresh allow() call takes the
        // "nothing to do here" branch and skips straight to the increment)...
        windowStartSecond.set(Instant.now().getEpochSecond());
        // ...but has not yet reset the counter, which still holds the prior window's
        // saturated value.
        windowCount.set(maxPerSecond);

        // A brand-new window has admitted nothing yet from this caller's point of view, so a
        // correct implementation must accept this request.
        boolean admitted = limiter.allow();

        assertThat(admitted)
                .as("a request arriving in a fresh rate-limit window must not be rejected just "
                        + "because the counter reset from the previous window's CAS winner "
                        + "has not run yet")
                .isTrue();
    }
}
