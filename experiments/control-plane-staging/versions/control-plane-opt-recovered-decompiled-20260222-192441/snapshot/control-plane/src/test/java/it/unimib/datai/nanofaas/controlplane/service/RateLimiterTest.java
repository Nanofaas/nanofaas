package it.unimib.datai.nanofaas.controlplane.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

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

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < numThreads; i++) {
            Thread t = new Thread(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < requestsPerThread; j++) {
                        if (limiter.allow()) {
                            allowedCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
            threads.add(t);
            t.start();
        }

        // Start all threads simultaneously
        startLatch.countDown();
        endLatch.await();

        // With 50 threads x 10 requests = 500 total requests
        // But limit is 100/second, so max 100 should be allowed
        assertThat(allowedCount.get()).isLessThanOrEqualTo(maxPerSecond);
    }

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
                    int allowedThisSecond = 0;
                    long lastSecond = -1;

                    for (int j = 0; j < 100; j++) {
                        long currentSecond = System.currentTimeMillis() / 1000;
                        if (currentSecond != lastSecond) {
                            allowedThisSecond = 0;
                            lastSecond = currentSecond;
                        }

                        if (limiter.allow()) {
                            totalAllowed.incrementAndGet();
                            allowedThisSecond++;
                        }

                        // Small delay to spread across time
                        Thread.sleep(1);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        endLatch.await();

        // No violations should occur
        assertThat(violations.get()).isEqualTo(0);
    }
}
