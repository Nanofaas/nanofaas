package it.unimib.datai.nanofaas.modules.syncqueue.sync;

import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class P14WaitEstimatorMeasurement {
    private static final int WINDOW_SECONDS = 30;
    private static final int BUCKET_MILLIS = 1_000;
    private static final int BUCKETS = 31;
    private static final int MAX_FUNCTIONS = 8_192;
    private static final int CLEANUP_STATES = 16;
    private static final int EXACT_CLEANUP_SAMPLES = 4_096;
    private static final int WARMUPS = 3;
    private static final int REPETITIONS = 7;
    private static final int DEPTH = 10;
    private static final double MAX_RELATIVE_WAIT_ERROR = 0.10;

    private P14WaitEstimatorMeasurement() {
    }

    public static void main(String[] args) {
        System.out.printf("contract window=%ds bucket=%dms slots=%d warmups=%d repetitions=%d "
                        + "tolerance=one-boundary-bucket/10%%-relative-and-identical-admission%n",
                WINDOW_SECONDS, BUCKET_MILLIS, BUCKETS, WARMUPS, REPETITIONS);
        compareDeterministicStreams();
        compareCapacityBoundaries();
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
        boolean admissionSame = true;
        for (long millis = 0; millis <= 60_000; millis += 100) {
            regular.add(millis);
            bucket.record("fn", millis);
            long exact = exactCount(regular, millis);
            long approximate = bucket.count("fn", millis);
            maxAbsoluteError = Math.max(maxAbsoluteError, Math.abs(approximate - exact));
            double relativeError = relativeWaitError(exact, approximate);
            maxRelativeWaitError = Math.max(maxRelativeWaitError, relativeError);
            maxEventsInBucket = Math.max(maxEventsInBucket, eventsInBucket(regular, millis));
            admissionSame &= admits(exact, DEPTH, 2.0) == admits(approximate, DEPTH, 2.0);
        }
        require(maxAbsoluteError <= maxEventsInBucket,
                "regular stream exceeded one-bucket count tolerance");
        require(maxRelativeWaitError <= MAX_RELATIVE_WAIT_ERROR,
                "regular stream exceeded relative wait tolerance");
        require(admissionSame, "regular stream changed admission");
        require(fairnessOrderSame(), "regular stream changed fairness ordering");
        require(Double.isInfinite(relativeWaitError(100, 0)),
                "zero approximation must be an infinite error for substantial exact history");

        BucketCounter adversarial = new BucketCounter();
        List<Long> burst = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            adversarial.record("fn", 0);
            burst.add(0L);
        }
        long query = 30_999;
        long exactBoundary = exactCount(burst, query);
        long bucketBoundary = adversarial.count("fn", query);
        boolean exactAdmit = admits(exactBoundary, DEPTH, 10.0);
        boolean bucketAdmit = admits(bucketBoundary, DEPTH, 10.0);
        require(exactBoundary == 0 && bucketBoundary == 100,
                "boundary fixture no longer distinguishes exact and bucket history");
        require(exactAdmit != bucketAdmit,
                "bucket rejection fixture must expose the admission difference");

        BucketCounter lifecycle = new BucketCounter();
        lifecycle.record("idle", 0);
        lifecycle.record("removed", 0);
        lifecycle.remove("removed");
        require(lifecycle.functionStates() == 1, "explicit bucket removal retained state");
        for (int i = 0; i < 2; i++) {
            lifecycle.maintain(31_001);
        }
        require(lifecycle.functionStates() == 0 && lifecycle.cells() == BUCKETS,
                "idle bucket cleanup retained function state");

        BucketCounter minimumBoundary = new BucketCounter();
        minimumBoundary.record("min", Long.MIN_VALUE);
        BucketCounter maximumBoundary = new BucketCounter();
        maximumBoundary.record("max", Long.MAX_VALUE);
        require(minimumBoundary.count("min", Long.MIN_VALUE) == 1
                        && maximumBoundary.count("max", Long.MAX_VALUE) == 1,
                "bucket arithmetic overflowed at range boundaries");

        System.out.printf("comparison regularMaxAbs=%d maxEventsPerBucket=%d "
                        + "maxRelativeWaitError=%.2f%% regularAdmissionSame=%s "
                        + "fairnessOrderSame=true zeroApproximationError=infinite "
                        + "boundaryExact=%d boundaryBucket=%d exactAdmit=%s bucketAdmit=%s "
                        + "bucketIdleStates=0 bucketRemovedStates=0 decision=retain-exact%n",
                maxAbsoluteError, maxEventsInBucket, maxRelativeWaitError * 100.0,
                admissionSame, exactBoundary, bucketBoundary, exactAdmit, bucketAdmit);
    }

    private static void compareCapacityBoundaries() {
        Instant start = Instant.parse("2026-09-10T10:00:00Z");
        WaitEstimator exact = new WaitEstimator(Duration.ofSeconds(WINDOW_SECONDS), 1);
        exact.recordDispatch("expired", start);
        for (int i = 1; i < MAX_FUNCTIONS; i++) {
            exact.recordDispatch("live-" + i, start.plusSeconds(WINDOW_SECONDS));
        }
        double representedWait = exact.estimateWaitSeconds(
                "live-1", 1, start.plusSeconds(WINDOW_SECONDS));
        double overflowWait = exact.estimateWaitSeconds(
                "function-8193", 1, start.plusSeconds(WINDOW_SECONDS));
        double recoveredWait = exact.estimateWaitSeconds(
                "function-8193", 1, start.plusSeconds(WINDOW_SECONDS).plusNanos(1));
        require(Double.isFinite(representedWait), "represented 8,192nd function lost exact history");
        require(Double.isInfinite(overflowWait), "8,193rd live function was not rejected");
        require(Double.isFinite(recoveredWait), "expired state did not recover an overflow slot");
        require(admitsByWait(representedWait, 30.0)
                        && !admitsByWait(overflowWait, 30.0),
                "function overflow violated conservative admission");
        require(representedWait < overflowWait,
                "function overflow violated represented-function fairness ordering");
        require(exact.retentionSnapshot().functionStates() == MAX_FUNCTIONS - 1,
                "overflow recovery retained the expired state");

        WaitEstimator capped = new WaitEstimator(Duration.ofSeconds(WINDOW_SECONDS), 1);
        int exactHotSamples = 40_000;
        for (int i = 0; i < exactHotSamples; i++) {
            capped.recordDispatch("hot", start);
        }
        int retainedHotSamples = capped.retentionSnapshot().perFunctionSamples();
        double uncappedWait = DEPTH * (double) WINDOW_SECONDS / exactHotSamples;
        double cappedWait = capped.estimateWaitSeconds("hot", DEPTH, start);
        for (int i = 0; i < 100; i++) {
            capped.recordDispatch("cold", start);
        }
        double coldWait = capped.estimateWaitSeconds("cold", DEPTH, start);
        require(retainedHotSamples == 32_768, "per-function sample cap changed");
        require(cappedWait >= uncappedWait, "sample cap created a less conservative wait");
        require(!admitsByWait(uncappedWait, 0.007)
                        && !admitsByWait(cappedWait, 0.007),
                "sample cap created a false admission");
        require(admitsByWait(uncappedWait, 0.008)
                        && !admitsByWait(cappedWait, 0.008),
                "sample-cap fixture no longer measures conservative rejection");
        require(cappedWait < coldWait,
                "sample cap inverted hot/cold fairness ordering");

        System.out.printf("capacity function8192Wait=%.6f function8193Wait=%s "
                        + "recoveredWait=%.6f recoveredStates=%d "
                        + "sampleCapExact=%d sampleCapRetained=%d "
                        + "uncappedWait=%.6f cappedWait=%.6f coldWait=%.6f "
                        + "overflowAdmission=conservative sampleCapFalseReject=true "
                        + "noFalseAdmission=true fairnessOrderSame=true "
                        + "retryAfter=asserted-by-production-test%n",
                representedWait, Double.isFinite(overflowWait) ? "finite" : "infinite",
                recoveredWait, exact.retentionSnapshot().functionStates(),
                exactHotSamples, retainedHotSamples, uncappedWait, cappedWait, coldWait);
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
        String[] names = names(functions);
        Instant[] instants = instants(ratePerSecond, events);
        long[] exactNanos = new long[REPETITIONS];
        long[] exactBytes = new long[REPETITIONS];
        long[] bucketNanos = new long[REPETITIONS];
        long[] bucketBytes = new long[REPETITIONS];
        long[] exactIdleNanos = new long[REPETITIONS];
        long[] exactIdleBytes = new long[REPETITIONS];
        long[] bucketIdleNanos = new long[REPETITIONS];
        long[] bucketIdleBytes = new long[REPETITIONS];

        for (int repetition = -WARMUPS; repetition < REPETITIONS; repetition++) {
            WaitEstimator exact = new WaitEstimator(Duration.ofSeconds(WINDOW_SECONDS), 3);
            BucketCounter bucket = new BucketCounter();
            Sample exactSample = sample(() -> runExact(exact, names, instants));
            Sample bucketSample = sample(() -> runBucket(bucket, names, instants));
            Instant afterIdle = instants[events - 1].plusSeconds(WINDOW_SECONDS + 1L);
            long afterIdleMillis = afterIdle.toEpochMilli();
            int cleanupCycles = cleanupCycles(functions, events);
            Sample exactIdleSample = sample(
                    () -> runExactMaintenance(exact, afterIdle, cleanupCycles));
            Sample bucketIdleSample = sample(
                    () -> runBucketMaintenance(bucket, afterIdleMillis, cleanupCycles));
            require(exact.retentionSnapshot().globalSamples() == 0
                            && exact.retentionSnapshot().perFunctionSamples() == 0,
                    "exact idle maintenance retained expired samples");
            require(bucket.functionStates() == 0,
                    "bucket idle maintenance retained expired function state");
            if (repetition >= 0) {
                exactNanos[repetition] = exactSample.nanos / events;
                exactBytes[repetition] = exactSample.bytes / events;
                bucketNanos[repetition] = bucketSample.nanos / events;
                bucketBytes[repetition] = bucketSample.bytes / events;
                exactIdleNanos[repetition] = exactIdleSample.nanos / cleanupCycles;
                exactIdleBytes[repetition] = exactIdleSample.bytes / cleanupCycles;
                bucketIdleNanos[repetition] = bucketIdleSample.nanos / cleanupCycles;
                bucketIdleBytes[repetition] = bucketIdleSample.bytes / cleanupCycles;
            }
        }

        System.out.printf("workload=%s functions=%d rate=%d/s exactNs=%d bucketNs=%d "
                        + "exactBytes=%d bucketBytes=%d postIdleExactNs=%d postIdleBucketNs=%d "
                        + "postIdleExactBytes=%d postIdleBucketBytes=%d "
                        + "afterIdleExact=0+0 afterIdleBucketStates=0%n",
                label, functions, ratePerSecond, median(exactNanos), median(bucketNanos),
                median(exactBytes), median(bucketBytes), median(exactIdleNanos),
                median(bucketIdleNanos), median(exactIdleBytes), median(bucketIdleBytes));
    }

    private static int cleanupCycles(int functions, int events) {
        int visitsPerRound = (functions + CLEANUP_STATES - 1) / CLEANUP_STATES;
        int samplesPerFunction = Math.min(32_768, (events + functions - 1) / functions);
        int samplesPerVisit = EXACT_CLEANUP_SAMPLES / CLEANUP_STATES;
        int functionRounds = (samplesPerFunction + samplesPerVisit - 1) / samplesPerVisit;
        int stateCycles = visitsPerRound * functionRounds + 1;
        int sampleCycles = (Math.min(events, 262_144) + EXACT_CLEANUP_SAMPLES - 1)
                / EXACT_CLEANUP_SAMPLES + 1;
        return Math.max(stateCycles, sampleCycles);
    }

    private static String[] names(int functions) {
        String[] names = new String[functions];
        for (int i = 0; i < functions; i++) {
            names[i] = "fn-" + i;
        }
        return names;
    }

    private static Instant[] instants(int ratePerSecond, int events) {
        Instant[] instants = new Instant[events];
        long stepNanos = 1_000_000_000L / ratePerSecond;
        Instant origin = Instant.parse("2026-09-10T10:00:00Z");
        for (int i = 0; i < events; i++) {
            instants[i] = origin.plusNanos(stepNanos * i);
        }
        return instants;
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

    private static void runExactMaintenance(
            WaitEstimator estimator, Instant afterIdle, int cycles) {
        for (int i = 0; i < cycles; i++) {
            estimator.maintain(afterIdle);
        }
    }

    private static void runBucketMaintenance(
            BucketCounter estimator, long afterIdleMillis, int cycles) {
        for (int i = 0; i < cycles; i++) {
            estimator.maintain(afterIdleMillis);
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
        return events.stream()
                .filter(event -> event >= bucketStart && event < bucketStart + BUCKET_MILLIS)
                .count();
    }

    private static double relativeWaitError(long exact, long approximate) {
        if (exact < 10) {
            return 0.0;
        }
        if (approximate == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double exactWait = DEPTH * (double) WINDOW_SECONDS / exact;
        double approximateWait = DEPTH * (double) WINDOW_SECONDS / approximate;
        return Math.abs(approximateWait - exactWait) / exactWait;
    }

    private static boolean admits(long samples, int depth, double maximumWaitSeconds) {
        return samples > 0 && depth * (double) WINDOW_SECONDS / samples <= maximumWaitSeconds;
    }

    private static boolean admitsByWait(double wait, double maximumWaitSeconds) {
        return wait <= maximumWaitSeconds;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record Sample(long nanos, long bytes) {
    }

    private static final class BucketCounter {
        private final State global = new State();
        private final Map<String, State> functions = new HashMap<>();
        private final ArrayDeque<String> cleanupCandidates = new ArrayDeque<>();
        private final Set<String> cleanupQueued = new HashSet<>();

        private void record(String function, long millis) {
            global.add(Math.floorDiv(millis, BUCKET_MILLIS));
            State state = functions.get(function);
            if (state == null) {
                maintain(millis);
                if (functions.size() >= MAX_FUNCTIONS) {
                    return;
                }
                state = new State();
                functions.put(function, state);
            }
            state.add(Math.floorDiv(millis, BUCKET_MILLIS));
            queueForCleanup(function);
        }

        private long count(String function, long now) {
            State state = functions.get(function);
            return state == null ? 0 : state.count(now);
        }

        private void maintain(long now) {
            global.prune(now);
            for (int i = 0; i < CLEANUP_STATES; i++) {
                String function = cleanupCandidates.pollFirst();
                if (function == null) {
                    return;
                }
                cleanupQueued.remove(function);
                State state = functions.get(function);
                if (state == null) {
                    continue;
                }
                state.prune(now);
                if (state.empty()) {
                    functions.remove(function, state);
                } else {
                    queueForCleanup(function);
                }
            }
        }

        private void remove(String function) {
            functions.remove(function);
            if (cleanupQueued.remove(function)) {
                cleanupCandidates.remove(function);
            }
        }

        private void queueForCleanup(String function) {
            if (cleanupQueued.add(function)) {
                cleanupCandidates.addLast(function);
            }
        }

        private int functionStates() {
            return functions.size();
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
                if (ticks[slot] >= cutoffTick && ticks[slot] <= nowTick) {
                    total = saturatedAdd(total, counts[slot]);
                }
            }
            return total;
        }

        private void prune(long now) {
            long cutoff = saturatingSubtract(now, WINDOW_SECONDS * 1_000L);
            long cutoffTick = Math.floorDiv(cutoff, BUCKET_MILLIS);
            for (int slot = 0; slot < BUCKETS; slot++) {
                if (ticks[slot] < cutoffTick) {
                    ticks[slot] = Long.MIN_VALUE;
                    counts[slot] = 0;
                }
            }
        }

        private boolean empty() {
            for (long count : counts) {
                if (count != 0) {
                    return false;
                }
            }
            return true;
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
