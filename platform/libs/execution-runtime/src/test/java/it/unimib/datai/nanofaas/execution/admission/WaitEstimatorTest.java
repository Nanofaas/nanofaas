package it.unimib.datai.nanofaas.execution.admission;

import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaitEstimatorTest {
    @Test
    void returnsZeroWhenQueueDepthIsZeroEvenWithoutSamples() {
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 3);
        Instant now = Instant.parse("2026-02-01T00:00:10Z");

        double est = estimator.estimateWaitSeconds("fn", 0, now);

        assertEquals(0.0, est, 0.0);
    }

    @Test
    void returnsInfinityWithoutAnyThroughput() {
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 3);

        assertEquals(Double.POSITIVE_INFINITY,
                estimator.estimateWaitSeconds("fn", 1, Instant.parse("2026-02-01T00:00:10Z")), 0.0);
    }

    @Test
    void usesPerFunctionWhenEnoughSamples() {
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 3);
        Instant now = Instant.parse("2026-02-01T00:00:10Z");
        estimator.recordDispatch("fn", now.minusSeconds(9));
        estimator.recordDispatch("fn", now.minusSeconds(8));
        estimator.recordDispatch("fn", now.minusSeconds(7));

        double est = estimator.estimateWaitSeconds("fn", 6, now);

        assertEquals(20.0, est, 0.01);
    }

    @Test
    void fallsBackToGlobalWhenSamplesInsufficient() {
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 3);
        Instant now = Instant.parse("2026-02-01T00:00:10Z");
        estimator.recordDispatch("other", now.minusSeconds(9));
        estimator.recordDispatch("other", now.minusSeconds(8));
        estimator.recordDispatch("other", now.minusSeconds(7));
        estimator.recordDispatch("fn", now.minusSeconds(9));

        double est = estimator.estimateWaitSeconds("fn", 3, now);

        assertEquals(7.5, est, 0.01);
    }

    @Test
    void removeFunctionState_clearsPerFunctionEvents() {
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 3);
        Instant now = Instant.parse("2026-02-01T00:00:10Z");
        estimator.recordDispatch("fn", now.minusSeconds(9));
        estimator.recordDispatch("fn", now.minusSeconds(8));
        estimator.recordDispatch("fn", now.minusSeconds(7));
        estimator.recordDispatch("other", now.minusSeconds(6));

        assertEquals(20.0, estimator.estimateWaitSeconds("fn", 6, now), 0.01);

        estimator.removeFunctionState("fn");

        assertEquals(20.0, estimator.estimateWaitSeconds("fn", 8, now), 0.01);
    }

    /**
     * The accepted rounding, checked against an exact window on random streams: the bucketed
     * estimate is never above the exact one, so it never rejects what the exact one admits.
     */
    @Test
    void theEstimateNeverExceedsTheExactWindowEstimate() {
        Duration window = Duration.ofSeconds(10);
        int minSamples = 5;
        Random random = new Random(208);
        for (int run = 0; run < 50; run++) {
            WaitEstimator estimator = new WaitEstimator(window, minSamples);
            List<Instant> times = new ArrayList<>();
            List<String> functions = new ArrayList<>();
            Instant t = Instant.parse("2026-02-01T00:00:00Z").plusNanos(random.nextInt(1_000_000_000));
            for (int i = 0; i < 400; i++) {
                t = t.plusMillis(random.nextInt(random.nextBoolean() ? 20 : 400));
                String function = "fn-" + random.nextInt(3);
                estimator.recordDispatch(function, t);
                times.add(t);
                functions.add(function);
                if (i % 7 == 0) {
                    Instant now = t.plusMillis(random.nextInt(2_000));
                    for (String candidate : List.of("fn-0", "fn-1", "fn-2", "absent")) {
                        int depth = 1 + random.nextInt(50);
                        double exact = exactEstimate(times, functions, candidate, depth, now, window, minSamples);
                        double bucketed = estimator.estimateWaitSeconds(candidate, depth, now);
                        assertTrue(bucketed <= exact, () -> "bucketed " + bucketed + " > exact " + exact);
                    }
                    t = now; // the estimator's clock never goes back: neither does the stream
                }
            }
        }
    }

    /**
     * The one-bucket bound, at the default 30 s window (one-second buckets): the burst P14
     * measured. 100 dispatches 30.999 s ago are outside the exact window but inside the
     * straddling bucket, so they still count toward the estimate (admitting where the exact
     * computation would reject), never toward the per-function threshold, and never once a whole
     * bucket separates them from the window.
     */
    @Test
    void theStraddlingBucketCountsTowardTheEstimateButNotTowardThePerFunctionThreshold() {
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(30), 50);
        Instant start = Instant.parse("2026-02-01T00:00:00Z");
        for (int i = 0; i < 100; i++) {
            estimator.recordDispatch("burst", start);
        }
        for (int i = 0; i < 10; i++) {
            estimator.recordDispatch("other", start.plusMillis(30_500));
        }

        // 100 per-function dispatches, but all in the straddling bucket: below the threshold, so
        // the global estimate, which counts them as well as the 10 inside the window.
        assertEquals(1 / (110 / 30.0), estimator.estimateWaitSeconds("burst", 1, start.plusMillis(30_999)), 0.0);
        // One whole bucket later the burst no longer counts at all.
        assertEquals(1 / (10 / 30.0), estimator.estimateWaitSeconds("burst", 1, start.plusSeconds(31)), 0.0);
    }

    @Test
    void subSecondWindowsUseProportionalBuckets() {
        WaitEstimator estimator = new WaitEstimator(Duration.ofMillis(100), 1);
        Instant now = Instant.parse("2026-02-01T00:00:10Z");
        for (int i = 9; i >= 0; i--) {
            estimator.recordDispatch("fn", now.minusMillis(i * 10L));
        }

        // Divisor: the window in whole seconds, at least one, as before.
        assertEquals(0.1, estimator.estimateWaitSeconds("fn", 1, now), 1e-12);
        // 100 ms window plus one 3.33 ms bucket later, the oldest dispatch has gone...
        assertEquals(1 / 9.0, estimator.estimateWaitSeconds("fn", 1, now.plusMillis(14)), 1e-12);
        // ...and after another window, every one of them.
        assertEquals(Double.POSITIVE_INFINITY, estimator.estimateWaitSeconds("fn", 1, now.plusMillis(105)), 0.0);
    }

    /** No per-function sample cap distorts a hot function's throughput any more. */
    @Test
    void aHotFunctionsEstimateCountsEveryDispatch() {
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 1);
        Instant now = Instant.parse("2026-02-01T00:00:10Z");
        for (int i = 0; i < 100_000; i++) {
            estimator.recordDispatch("hot", now);
        }

        assertEquals(0.1, estimator.estimateWaitSeconds("hot", 1_000, now), 1e-12);
        assertEquals(1, estimator.retentionSnapshot().functionStates());
    }

    /** recordDispatch runs on the engine's only worker thread: it must not allocate. */
    @Test
    void recordingADispatchAllocatesNothing() {
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(30), 50);
        Instant now = Instant.parse("2026-02-01T00:00:10Z");
        for (int i = 0; i < 200_000; i++) {
            estimator.recordDispatch("fn", now);
        }
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long before = threads.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < 1_000_000; i++) {
            estimator.recordDispatch("fn", now);
        }
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;

        assertTrue(allocated < 64 * 1024, () -> allocated + " bytes for 1,000,000 dispatches");
    }

    @Test
    void concurrentRecordingAndEstimatingLoseNoDispatch() throws Exception {
        WaitEstimator estimator = new WaitEstimator(Duration.ofSeconds(10), 1);
        Instant now = Instant.parse("2026-02-01T00:00:10Z");
        AtomicBoolean done = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<?>> writers = new ArrayList<>();
            for (int w = 0; w < 4; w++) {
                String function = "fn-" + w;
                writers.add(pool.submit(() -> {
                    for (int i = 0; i < 25_000; i++) {
                        estimator.recordDispatch(function, now);
                    }
                }));
            }
            List<Future<?>> readers = new ArrayList<>();
            for (int r = 0; r < 2; r++) {
                readers.add(pool.submit(() -> {
                    while (!done.get()) {
                        assertTrue(estimator.estimateWaitSeconds("fn-0", 10, now) >= 0);
                    }
                }));
            }
            for (Future<?> writer : writers) {
                writer.get(30, TimeUnit.SECONDS);
            }
            done.set(true);
            for (Future<?> reader : readers) {
                reader.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        WaitEstimator.RetentionSnapshot snapshot = estimator.retentionSnapshot();
        assertEquals(4, snapshot.functionStates());
        assertEquals(100_000, snapshot.globalSamples());
        assertEquals(100_000, snapshot.perFunctionSamples());
    }

    /**
     * A dispatch for a function whose state maintenance is retiring as idle, at the same
     * instant: whichever wins, that dispatch is counted exactly once and the state stays.
     */
    @Test
    void aDispatchRacingTheRetirementOfItsIdleStateIsCountedExactlyOnce() throws Exception {
        Duration window = Duration.ofSeconds(1);
        WaitEstimator estimator = new WaitEstimator(window, 1);
        Instant t = Instant.parse("2026-02-01T00:00:00Z");
        estimator.recordDispatch("fn", t);
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5_000; round++) {
                Instant now = t.plus(window.multipliedBy(3L * (round + 1)));
                Future<?> maintenance = pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    estimator.maintain(now);
                    return null;
                });
                Future<?> dispatch = pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    estimator.recordDispatch("fn", now);
                    return null;
                });
                maintenance.get(5, TimeUnit.SECONDS);
                dispatch.get(5, TimeUnit.SECONDS);
                WaitEstimator.RetentionSnapshot snapshot = estimator.retentionSnapshot();
                assertEquals(1, snapshot.functionStates(), "round " + round);
                assertEquals(1, snapshot.perFunctionSamples(), "round " + round);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static double exactEstimate(List<Instant> times, List<String> functions, String function,
                                        int depth, Instant now, Duration window, int minSamples) {
        Instant cutoff = now.minus(window);
        long perFunction = 0;
        long global = 0;
        for (int i = 0; i < times.size(); i++) {
            if (!times.get(i).isBefore(cutoff) && !times.get(i).isAfter(now)) {
                global++;
                if (functions.get(i).equals(function)) {
                    perFunction++;
                }
            }
        }
        double seconds = Math.max(1.0, window.toSeconds());
        if (perFunction >= minSamples && perFunction > 0) {
            return depth / (perFunction / seconds);
        }
        return global == 0 ? Double.POSITIVE_INFINITY : depth / (global / seconds);
    }
}
