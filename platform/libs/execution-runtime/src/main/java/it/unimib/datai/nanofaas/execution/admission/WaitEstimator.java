package it.unimib.datai.nanofaas.execution.admission;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Sync-queue wait estimate from windowed dispatch throughput, counted in a ring of buckets per
 * function plus one global ring. The window is split into {@value #BUCKETS} buckets of
 * {@code window / BUCKETS} (rounded up to a whole nanosecond): one second at the default 30 s
 * window, about 3.3 ms at a 100 ms one. Recording and estimating touch a constant
 * {@value #BUCKETS} + 1 slots, allocate nothing per dispatch, and use memory proportional to the
 * number of function states, never to throughput.
 *
 * <h2>Rounding</h2>
 * The exact estimate counts the dispatches in {@code [now - window, now]}. The bucket straddling
 * {@code now - window} makes that count unknowable, so two bounds are read: the <em>lower</em>
 * count sums the buckets wholly inside the window, the current partial bucket included; the
 * <em>upper</em> count also adds the straddling bucket, so it can include up to one bucket of
 * dispatches older than the window. The per-function estimate is used only when the lower count
 * reaches {@code perFunctionMinSamples} (the exact count then reaches it too); otherwise the
 * global one is used. Every estimate divides the depth by the upper count over the configured
 * window in whole seconds, at least one second, exactly as before. The estimate is therefore
 * never above the exact one: it never rejects a request the exact computation would admit. It
 * errs toward admitting, by at most one bucket of older dispatches, or by falling back to the
 * global throughput (never lower than a function's own) just below the per-function threshold.
 *
 * <h2>Concurrency</h2>
 * No lock on either path. Each slot is one packed {@code long} (bucket number, count) updated by
 * CAS, and {@code estimateWaitSeconds} only reads. Idle function states are dropped by
 * {@link #maintain}, and by eviction when the state table is full, through a per-state retire
 * protocol: a dispatch landing on a state being retired either cancels the retirement or records
 * itself again into a fresh state, so none is lost or counted twice.
 */
public class WaitEstimator {
    static final int BUCKETS = 30;
    private static final int SLOTS = BUCKETS + 1;
    private static final int DEFAULT_MAX_FUNCTION_STATES = 8_192;
    /** Instants are counted in epoch nanoseconds clamped to +-this (years 1824 to 2116). */
    private static final long MAX_NANOS = Long.MAX_VALUE / 2;
    private static final long LOW_32 = 0xFFFF_FFFFL;
    private static final int LIVE = 0;
    private static final int RETIRING = 1;
    private static final int RETIRED = 2;

    private final long windowNanos;
    private final long bucketNanos;
    private final double divisorSeconds;
    private final int perFunctionMinSamples;
    private final int maxFunctionStates;
    private final Ring global = new Ring();
    private final Map<String, Ring> perFunction = new ConcurrentHashMap<>();
    private final AtomicInteger functionStates = new AtomicInteger();
    private final AtomicLong latestNanos = new AtomicLong(Long.MIN_VALUE);

    public WaitEstimator(Duration window, int perFunctionMinSamples) {
        this(window, perFunctionMinSamples, DEFAULT_MAX_FUNCTION_STATES);
    }

    /** Public for {@code :modules:sync-queue}'s tests, which size the state table. */
    public WaitEstimator(Duration window, int perFunctionMinSamples, int maxFunctionStates) {
        if (maxFunctionStates <= 0) {
            throw new IllegalArgumentException("maxFunctionStates must be positive");
        }
        long nanos;
        try {
            nanos = window.toNanos();
        } catch (ArithmeticException _) {
            nanos = MAX_NANOS;
        }
        this.windowNanos = Math.clamp(nanos, 0, MAX_NANOS);
        this.bucketNanos = Math.max(1, Math.ceilDiv(windowNanos, BUCKETS));
        this.divisorSeconds = Math.max(1.0, (double) window.toSeconds());
        this.perFunctionMinSamples = perFunctionMinSamples;
        this.maxFunctionStates = maxFunctionStates;
    }

    /** Called on the engine's worker for every settled dispatch: two CAS increments. */
    public void recordDispatch(String functionName, Instant now) {
        long nowNanos = advance(now);
        long bucket = Math.floorDiv(nowNanos, bucketNanos);
        global.add(bucket);
        Ring ring = perFunction.get(functionName);
        // Repeats only when a retirement wins a race against this very dispatch, once per race.
        while (true) {
            if (ring == null) {
                ring = newState(functionName, nowNanos);
                if (ring == null) {
                    return; // Table full of live states: counted globally only, as before.
                }
            }
            ring.add(bucket);
            if (ring.keptAfterAdd()) {
                return;
            }
            // Retired under this dispatch: make sure it is gone, then record into a fresh state.
            if (perFunction.remove(functionName, ring)) {
                functionStates.decrementAndGet();
            }
            ring = null;
        }
    }

    public void removeFunctionState(String functionName) {
        if (perFunction.remove(functionName) != null) {
            functionStates.decrementAndGet();
        }
    }

    public double estimateWaitSeconds(String functionName, int queueDepth, Instant now) {
        long nowNanos = advance(now);
        if (queueDepth <= 0) {
            return 0.0;
        }
        Ring ring = perFunction.get(functionName);
        if (ring == null && !hasRoomForAState(nowNanos)) {
            // Every slot holds a live function: refuse rather than guess, as before.
            return Double.POSITIVE_INFINITY;
        }
        Window w = new Window(nowNanos);
        if (ring != null) {
            long lower = ring.count(w.current, w.firstInside);
            if (lower >= perFunctionMinSamples) {
                long upper = lower + ring.straddling(w);
                if (upper > 0) {
                    return queueDepth / (upper / divisorSeconds);
                }
            }
        }
        long upper = global.count(w.current, w.firstInside) + global.straddling(w);
        return upper == 0 ? Double.POSITIVE_INFINITY : queueDepth / (upper / divisorSeconds);
    }

    /** Diagnostic counts (upper bounds) for the window ending at the latest observed instant. */
    public RetentionSnapshot retentionSnapshot() {
        long latest = latestNanos.get();
        if (latest == Long.MIN_VALUE) {
            return new RetentionSnapshot(functionStates.get(), 0, 0);
        }
        Window w = new Window(latest);
        long perFunctionCount = 0;
        for (Ring ring : perFunction.values()) {
            perFunctionCount += ring.count(w.current, w.firstOverlapping);
        }
        return new RetentionSnapshot(functionStates.get(),
                (int) Math.min(Integer.MAX_VALUE, global.count(w.current, w.firstOverlapping)),
                (int) Math.min(Integer.MAX_VALUE, perFunctionCount));
    }

    /**
     * Drops the state of every function with no dispatch left in the window. Not needed for
     * correctness or bounded memory (the table is capped, and a full table evicts an idle state
     * on demand), only so idle functions are not retained indefinitely; run off the hot paths.
     */
    public void maintain(Instant now) {
        Window w = new Window(advance(now));
        for (Map.Entry<String, Ring> entry : perFunction.entrySet()) {
            retireIfIdle(entry.getKey(), entry.getValue(), w);
        }
    }

    private Ring newState(String functionName, long nowNanos) {
        if (!reserveState() && !(evictIdleState(nowNanos) && reserveState())) {
            return null;
        }
        Ring fresh = new Ring();
        Ring existing = perFunction.putIfAbsent(functionName, fresh);
        if (existing != null) {
            functionStates.decrementAndGet();
            return existing;
        }
        return fresh;
    }

    private boolean hasRoomForAState(long nowNanos) {
        return functionStates.get() < maxFunctionStates || evictIdleState(nowNanos);
    }

    /** Only when the table is full: stops at the first idle state, like the eviction before it. */
    private boolean evictIdleState(long nowNanos) {
        Window w = new Window(nowNanos);
        for (Map.Entry<String, Ring> entry : perFunction.entrySet()) {
            if (retireIfIdle(entry.getKey(), entry.getValue(), w)) {
                return functionStates.get() < maxFunctionStates;
            }
        }
        return false;
    }

    /**
     * The retire protocol. Marking RETIRING before reading the slots means a dispatch that this
     * read missed sees the mark after its own increment, and then either cancels the retirement
     * (RETIRING back to LIVE: the state is kept with its count) or, once RETIRED won, records
     * itself into a fresh state.
     */
    private boolean retireIfIdle(String functionName, Ring ring, Window w) {
        if (!ring.phase.compareAndSet(LIVE, RETIRING)) {
            return false;
        }
        if (ring.count(w.current, w.firstOverlapping) != 0 || !ring.phase.compareAndSet(RETIRING, RETIRED)) {
            ring.phase.compareAndSet(RETIRING, LIVE);
            return false;
        }
        if (perFunction.remove(functionName, ring)) {
            functionStates.decrementAndGet();
        }
        return true;
    }

    private boolean reserveState() {
        while (true) {
            int current = functionStates.get();
            if (current >= maxFunctionStates) {
                return false;
            }
            if (functionStates.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /** Time never goes backwards: an older reading counts as the latest one seen. */
    private long advance(Instant candidate) {
        long seconds = candidate.getEpochSecond();
        long nanos = seconds >= MAX_NANOS / 1_000_000_000L ? MAX_NANOS
                : seconds <= -MAX_NANOS / 1_000_000_000L ? -MAX_NANOS
                : seconds * 1_000_000_000L + candidate.getNano();
        long current = latestNanos.get();
        while (nanos > current) {
            if (latestNanos.compareAndSet(current, nanos)) {
                return nanos;
            }
            current = latestNanos.get();
        }
        return current;
    }

    /** Bucket numbers bounding the window that ends at one instant. */
    private final class Window {
        /** The bucket holding the window's end. */
        final long current;
        /** First bucket starting at or after {@code now - window}. */
        final long firstInside;
        /** First bucket ending after {@code now - window}: the straddling one when it differs. */
        final long firstOverlapping;

        Window(long nowNanos) {
            long cutoff = nowNanos - windowNanos;
            this.current = Math.floorDiv(nowNanos, bucketNanos);
            this.firstInside = Math.ceilDiv(cutoff, bucketNanos);
            this.firstOverlapping = Math.floorDiv(cutoff, bucketNanos);
        }
    }

    public record RetentionSnapshot(int functionStates,
                                    int globalSamples,
                                    int perFunctionSamples) {
    }

    /**
     * {@value #SLOTS} slots cover the {@value #BUCKETS} + 1 buckets a window can overlap
     * ({@code bucketNanos * BUCKETS >= window}). A slot packs the low 32 bits of its bucket
     * number with that bucket's count.
     */
    private static final class Ring {
        private final AtomicLongArray slots = new AtomicLongArray(SLOTS);
        private final AtomicInteger phase = new AtomicInteger(LIVE);

        void add(long bucket) {
            int index = (int) Math.floorMod(bucket, SLOTS);
            long tag = bucket & LOW_32;
            while (true) {
                long current = slots.get(index);
                long next;
                if (current != 0 && current >>> 32 == tag) {
                    if ((current & LOW_32) == LOW_32) {
                        return; // ponytail: 4e9 dispatches in one bucket saturate rather than wrap
                    }
                    next = current + 1;
                } else if (current != 0 && (int) (tag - (current >>> 32)) < 0) {
                    return; // The slot already holds a newer bucket: this one left every window.
                } else {
                    next = tag << 32 | 1;
                }
                if (slots.compareAndSet(index, current, next)) {
                    return;
                }
            }
        }

        /** Dispatches counted in buckets {@code from..current}; at most {@value #SLOTS} slots. */
        long count(long current, long from) {
            long sum = 0;
            for (long bucket = Math.max(from, current - BUCKETS); bucket <= current; bucket++) {
                sum += countOf(bucket);
            }
            return sum;
        }

        long straddling(Window w) {
            return w.firstOverlapping < w.firstInside ? countOf(w.firstOverlapping) : 0;
        }

        private long countOf(long bucket) {
            long value = slots.get((int) Math.floorMod(bucket, SLOTS));
            return value != 0 && value >>> 32 == (bucket & LOW_32) ? value & LOW_32 : 0;
        }

        /** After an increment: is this still the live state, cancelling a retirement in flight? */
        boolean keptAfterAdd() {
            int current = phase.get();
            while (current == RETIRING) {
                if (phase.compareAndSet(RETIRING, LIVE)) {
                    return true;
                }
                current = phase.get();
            }
            return current == LIVE;
        }
    }
}
