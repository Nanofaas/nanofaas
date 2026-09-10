package it.unimib.datai.nanofaas.modules.syncqueue.sync;

import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class P14WaitEstimatorMeasurement {
    private static final int WINDOW_SECONDS = 30;
    private static final int BUCKET_MILLIS = 1_000;
    private static final int BUCKETS = 31;
    private static final int WARMUPS = 3;
    private static final int REPETITIONS = 7;

    private P14WaitEstimatorMeasurement() {
    }

    public static void main(String[] args) {
        System.out.printf("contract window=%ds bucket=%dms slots=%d warmups=%d repetitions=%d "
                        + "tolerance=one-boundary-bucket/10%%-relative-and-identical-admission%n",
                WINDOW_SECONDS, BUCKET_MILLIS, BUCKETS, WARMUPS, REPETITIONS);
        compareDeterministicStreams();
        measure("one-low", 1, 1, 10_000);
        measure("one-high", 1, 1_000, 50_000);
        measure("many-low", 1_000, 1, 10_000);
        measure("many-high", 1_000, 1_000, 50_000);
    }

    private static void compareDeterministicStreams() {
        List<Long> regular = new ArrayList<>();
        BucketCounter bucket = new BucketCounter();
        long maxAbsoluteError = 0;
        double maxRelativeWaitError = 0.0;
        long maxEventsInBucket = 0;
        for (long millis = 0; millis <= 60_000; millis += 100) {
            regular.add(millis);
            bucket.record("fn", millis);
            long exact = exactCount(regular, millis);
            long approximate = bucket.count("fn", millis);
            maxAbsoluteError = Math.max(maxAbsoluteError, Math.abs(approximate - exact));
            maxRelativeWaitError = Math.max(maxRelativeWaitError, relativeWaitError(exact, approximate));
            maxEventsInBucket = Math.max(maxEventsInBucket, eventsInBucket(regular, millis));
        }

        BucketCounter adversarial = new BucketCounter();
        List<Long> burst = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            adversarial.record("fn", 0);
            burst.add(0L);
        }
        long query = 30_999;
        long exactBoundary = exactCount(burst, query);
        long bucketBoundary = adversarial.count("fn", query);
        boolean exactAdmit = admits(exactBoundary, 10, 10.0);
        boolean bucketAdmit = admits(bucketBoundary, 10, 10.0);
        BucketCounter rangeBoundaries = new BucketCounter();
        rangeBoundaries.record("min", Long.MIN_VALUE);
        rangeBoundaries.record("max", Long.MAX_VALUE);
        if (rangeBoundaries.count("min", Long.MIN_VALUE) != 1
                || rangeBoundaries.count("max", Long.MAX_VALUE) != 1) {
            throw new AssertionError("bucket arithmetic overflowed at range boundaries");
        }
        if (maxAbsoluteError > maxEventsInBucket) {
            throw new AssertionError("regular stream exceeded one-bucket count tolerance");
        }
        if (exactAdmit == bucketAdmit) {
            throw new AssertionError("adversarial boundary did not expose the admission difference");
        }
        System.out.printf("comparison regularMaxAbs=%d maxEventsPerBucket=%d maxRelativeWaitError=%.2f%% "
                        + "boundaryExact=%d boundaryBucket=%d exactAdmit=%s bucketAdmit=%s "
                        + "fairnessOrderSame=%s retryAfterChanged=false decision=retain-exact%n",
                maxAbsoluteError, maxEventsInBucket, maxRelativeWaitError * 100.0,
                exactBoundary, bucketBoundary, exactAdmit, bucketAdmit, fairnessOrderSame());
    }

    private static boolean fairnessOrderSame() {
        List<Long> hot = new ArrayList<>();
        List<Long> cold = new ArrayList<>();
        BucketCounter bucket = new BucketCounter();
        for (int i = 0; i < 30; i++) {
            long millis = i * 1_000L;
            hot.add(millis);
            bucket.record("hot", millis);
            if (i % 10 == 0) {
                cold.add(millis);
                bucket.record("cold", millis);
            }
        }
        long now = 30_000;
        return Long.compare(exactCount(hot, now), exactCount(cold, now))
                == Long.compare(bucket.count("hot", now), bucket.count("cold", now));
    }

    private static void measure(String label, int functions, int ratePerSecond, int events) {
        String[] names = new String[functions];
        for (int i = 0; i < functions; i++) {
            names[i] = "fn-" + i;
        }
        Instant[] instants = new Instant[events];
        long stepNanos = 1_000_000_000L / ratePerSecond;
        Instant origin = Instant.parse("2026-09-10T10:00:00Z");
        for (int i = 0; i < events; i++) {
            instants[i] = origin.plusNanos(stepNanos * i);
        }

        long[] exactNanos = new long[REPETITIONS];
        long[] exactBytes = new long[REPETITIONS];
        long[] bucketNanos = new long[REPETITIONS];
        long[] bucketBytes = new long[REPETITIONS];
        for (int repetition = -WARMUPS; repetition < REPETITIONS; repetition++) {
            WaitEstimator exact = new WaitEstimator(Duration.ofSeconds(WINDOW_SECONDS), 3);
            BucketCounter bucket = new BucketCounter();
            runExact(exact, names, instants);
            runBucket(bucket, names, instants);
            Sample exactSample = sample(() -> runExact(exact, names, instants));
            Sample bucketSample = sample(() -> runBucket(bucket, names, instants));
            if (repetition >= 0) {
                exactNanos[repetition] = exactSample.nanos / events;
                exactBytes[repetition] = exactSample.bytes / events;
                bucketNanos[repetition] = bucketSample.nanos / events;
                bucketBytes[repetition] = bucketSample.bytes / events;
            }
        }

        WaitEstimator retained = new WaitEstimator(Duration.ofSeconds(WINDOW_SECONDS), 3);
        BucketCounter retainedBuckets = new BucketCounter();
        runExact(retained, names, instants);
        runBucket(retainedBuckets, names, instants);
        WaitEstimator.RetentionSnapshot beforeIdle = retained.retentionSnapshot();
        Instant afterIdle = instants[events - 1].plusSeconds(WINDOW_SECONDS + 1L);
        for (int i = 0; i < Math.max(1, (functions + 15) / 16); i++) {
            retained.recordDispatch("active", afterIdle.plusNanos(i));
        }
        WaitEstimator.RetentionSnapshot afterCleanup = retained.retentionSnapshot();
        System.out.printf("workload=%s functions=%d rate=%d/s exactNs=%d bucketNs=%d "
                        + "exactBytes=%d bucketBytes=%d exactRetained=%d+%d bucketCells=%d "
                        + "afterIdleCleanup=%d+%d%n",
                label, functions, ratePerSecond, median(exactNanos), median(bucketNanos),
                median(exactBytes), median(bucketBytes), beforeIdle.globalSamples(),
                beforeIdle.perFunctionSamples(), retainedBuckets.cells(),
                afterCleanup.globalSamples(), afterCleanup.perFunctionSamples());
    }

    private static void runExact(WaitEstimator estimator, String[] names, Instant[] instants) {
        for (int i = 0; i < instants.length; i++) {
            estimator.recordDispatch(names[i % names.length], instants[i]);
        }
    }

    private static void runBucket(BucketCounter estimator, String[] names, Instant[] instants) {
        for (int i = 0; i < instants.length; i++) {
            estimator.record(names[i % names.length], instants[i].toEpochMilli());
        }
    }

    private static Sample sample(Runnable operation) {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        long bytesBefore = bean.getThreadAllocatedBytes(thread);
        long start = System.nanoTime();
        operation.run();
        return new Sample(System.nanoTime() - start,
                bean.getThreadAllocatedBytes(thread) - bytesBefore);
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static long exactCount(List<Long> events, long now) {
        long cutoff = now - WINDOW_SECONDS * 1_000L;
        return events.stream().filter(event -> event >= cutoff && event <= now).count();
    }

    private static long eventsInBucket(List<Long> events, long now) {
        long bucketStart = Math.floorDiv(now, BUCKET_MILLIS) * BUCKET_MILLIS;
        return events.stream().filter(event -> event >= bucketStart && event < bucketStart + BUCKET_MILLIS).count();
    }

    private static double relativeWaitError(long exact, long approximate) {
        if (exact < 10 || approximate == 0) {
            return 0.0;
        }
        double exactWait = 10.0 * WINDOW_SECONDS / exact;
        double approximateWait = 10.0 * WINDOW_SECONDS / approximate;
        return Math.abs(approximateWait - exactWait) / exactWait;
    }

    private static boolean admits(long samples, int depth, double maximumWaitSeconds) {
        return samples > 0 && depth * (double) WINDOW_SECONDS / samples <= maximumWaitSeconds;
    }

    private record Sample(long nanos, long bytes) {
    }

    private static final class BucketCounter {
        private final State global = new State();
        private final Map<String, State> functions = new HashMap<>();

        private void record(String function, long millis) {
            long tick = Math.floorDiv(millis, BUCKET_MILLIS);
            global.add(tick);
            functions.computeIfAbsent(function, ignored -> new State()).add(tick);
        }

        private long count(String function, long now) {
            State state = functions.get(function);
            return state == null ? 0 : state.count(now);
        }

        private long cells() {
            return (long) (functions.size() + 1) * BUCKETS;
        }
    }

    private static final class State {
        private final long[] ticks = new long[BUCKETS];
        private final long[] counts = new long[BUCKETS];

        private State() {
            Arrays.fill(ticks, Long.MIN_VALUE);
        }

        private void add(long tick) {
            int slot = Math.floorMod(tick, BUCKETS);
            if (ticks[slot] != tick) {
                ticks[slot] = tick;
                counts[slot] = 0;
            }
            if (counts[slot] != Long.MAX_VALUE) {
                counts[slot]++;
            }
        }

        private long count(long now) {
            long cutoff = saturatingSubtract(now, WINDOW_SECONDS * 1_000L);
            long cutoffTick = Math.floorDiv(cutoff, BUCKET_MILLIS);
            long nowTick = Math.floorDiv(now, BUCKET_MILLIS);
            long total = 0;
            for (int slot = 0; slot < BUCKETS; slot++) {
                if (ticks[slot] == Long.MIN_VALUE) {
                    continue;
                }
                if (ticks[slot] >= cutoffTick && ticks[slot] <= nowTick) {
                    total = saturatedAdd(total, counts[slot]);
                }
            }
            return total;
        }

        private static long saturatingSubtract(long value, long decrement) {
            return value < Long.MIN_VALUE + decrement ? Long.MIN_VALUE : value - decrement;
        }

        private static long saturatedAdd(long left, long right) {
            if (Long.MAX_VALUE - left < right) {
                return Long.MAX_VALUE;
            }
            return left + right;
        }
    }
}
