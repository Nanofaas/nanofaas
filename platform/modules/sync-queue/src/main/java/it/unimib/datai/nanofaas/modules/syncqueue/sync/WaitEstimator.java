package it.unimib.datai.nanofaas.modules.syncqueue.sync;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class WaitEstimator {
    private static final int DEFAULT_MAX_GLOBAL_SAMPLES = 262_144;
    private static final int DEFAULT_MAX_PER_FUNCTION_SAMPLES = 32_768;
    private static final int DEFAULT_MAX_FUNCTION_STATES = 8_192;
    private static final int DEFAULT_MAX_TOTAL_PER_FUNCTION_SAMPLES = 262_144;
    private static final int DEFAULT_CLEANUP_BUDGET = 16;
    private static final int DEFAULT_MAINTENANCE_SAMPLE_BUDGET = 4_096;

    private final Duration window;
    private final int perFunctionMinSamples;
    private final int maxGlobalSamples;
    private final int maxPerFunctionSamples;
    private final int maxFunctionStates;
    private final int maxTotalPerFunctionSamples;
    private final int maxExtraPerFunctionSamples;
    private final int cleanupBudget;
    private final int maintenanceSampleBudget;
    private final Deque<Instant> globalEvents;
    private final Map<String, FunctionEvents> perFunctionEvents = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<FunctionEvents> cleanupCandidates = new ConcurrentLinkedQueue<>();
    private final AtomicInteger functionStates = new AtomicInteger();
    private final AtomicInteger retainedGlobalSamples = new AtomicInteger();
    private final AtomicInteger retainedPerFunctionSamples = new AtomicInteger();
    private final AtomicInteger retainedExtraPerFunctionSamples = new AtomicInteger();
    private final AtomicReference<Instant> latestTime = new AtomicReference<>(Instant.MIN);

    public WaitEstimator(Duration window, int perFunctionMinSamples) {
        this(window, perFunctionMinSamples, new ConcurrentLinkedDeque<>(), Map.of(),
                DEFAULT_MAX_GLOBAL_SAMPLES, DEFAULT_MAX_PER_FUNCTION_SAMPLES,
                DEFAULT_MAX_FUNCTION_STATES, DEFAULT_MAX_TOTAL_PER_FUNCTION_SAMPLES,
                DEFAULT_CLEANUP_BUDGET, DEFAULT_MAINTENANCE_SAMPLE_BUDGET);
    }

    WaitEstimator(Duration window,
                  int perFunctionMinSamples,
                  Deque<Instant> globalEvents,
                  Map<String, ? extends Deque<Instant>> perFunctionEvents) {
        this(window, perFunctionMinSamples, globalEvents, perFunctionEvents,
                DEFAULT_MAX_GLOBAL_SAMPLES, DEFAULT_MAX_PER_FUNCTION_SAMPLES,
                DEFAULT_MAX_FUNCTION_STATES, DEFAULT_MAX_TOTAL_PER_FUNCTION_SAMPLES,
                DEFAULT_CLEANUP_BUDGET, DEFAULT_MAINTENANCE_SAMPLE_BUDGET);
    }

    WaitEstimator(Duration window,
                  int perFunctionMinSamples,
                  int maxGlobalSamples,
                  int maxPerFunctionSamples,
                  int cleanupBudget) {
        this(window, perFunctionMinSamples, new ConcurrentLinkedDeque<>(), Map.of(),
                maxGlobalSamples, maxPerFunctionSamples, maxGlobalSamples, maxGlobalSamples,
                cleanupBudget, DEFAULT_MAINTENANCE_SAMPLE_BUDGET);
    }

    WaitEstimator(Duration window,
                  int perFunctionMinSamples,
                  int maxGlobalSamples,
                  int maxPerFunctionSamples,
                  int cleanupBudget,
                  int maintenanceSampleBudget) {
        this(window, perFunctionMinSamples, new ConcurrentLinkedDeque<>(), Map.of(),
                maxGlobalSamples, maxPerFunctionSamples, maxGlobalSamples, maxGlobalSamples,
                cleanupBudget, maintenanceSampleBudget);
    }

    WaitEstimator(Duration window,
                  int perFunctionMinSamples,
                  int maxGlobalSamples,
                  int maxPerFunctionSamples,
                  int maxFunctionStates,
                  int maxTotalPerFunctionSamples,
                  int cleanupBudget) {
        this(window, perFunctionMinSamples, maxGlobalSamples, maxPerFunctionSamples,
                maxFunctionStates, maxTotalPerFunctionSamples, cleanupBudget,
                DEFAULT_MAINTENANCE_SAMPLE_BUDGET);
    }

    WaitEstimator(Duration window,
                  int perFunctionMinSamples,
                  int maxGlobalSamples,
                  int maxPerFunctionSamples,
                  int maxFunctionStates,
                  int maxTotalPerFunctionSamples,
                  int cleanupBudget,
                  int maintenanceSampleBudget) {
        this(window, perFunctionMinSamples, new ConcurrentLinkedDeque<>(), Map.of(),
                maxGlobalSamples, maxPerFunctionSamples, maxFunctionStates,
                maxTotalPerFunctionSamples, cleanupBudget, maintenanceSampleBudget);
    }

    private WaitEstimator(Duration window,
                          int perFunctionMinSamples,
                          Deque<Instant> globalEvents,
                          Map<String, ? extends Deque<Instant>> perFunctionEvents,
                          int maxGlobalSamples,
                          int maxPerFunctionSamples,
                          int maxFunctionStates,
                          int maxTotalPerFunctionSamples,
                          int cleanupBudget,
                          int maintenanceSampleBudget) {
        this.window = window;
        this.perFunctionMinSamples = perFunctionMinSamples;
        this.globalEvents = globalEvents;
        this.maxGlobalSamples = positive(maxGlobalSamples, "maxGlobalSamples");
        this.maxPerFunctionSamples = positive(maxPerFunctionSamples, "maxPerFunctionSamples");
        this.maxFunctionStates = positive(maxFunctionStates, "maxFunctionStates");
        this.maxTotalPerFunctionSamples = positive(maxTotalPerFunctionSamples,
                "maxTotalPerFunctionSamples");
        if (this.maxTotalPerFunctionSamples < this.maxFunctionStates) {
            throw new IllegalArgumentException(
                    "maxTotalPerFunctionSamples must cover every function state");
        }
        this.maxExtraPerFunctionSamples =
                this.maxTotalPerFunctionSamples - this.maxFunctionStates;
        this.cleanupBudget = positive(cleanupBudget, "cleanupBudget");
        this.maintenanceSampleBudget = positive(
                maintenanceSampleBudget, "maintenanceSampleBudget");
        this.retainedGlobalSamples.set(globalEvents.size());
        perFunctionEvents.forEach((name, events) -> {
            if (!reserveFunctionState()) {
                return;
            }
            FunctionEvents state = new FunctionEvents(name, events, events.size());
            if (!reserveExtraSamples(Math.max(0, state.samples - 1))) {
                throw new IllegalArgumentException(
                        "initial function history exceeds sample capacity");
            }
            this.perFunctionEvents.put(name, state);
            queueForCleanup(state);
            this.retainedPerFunctionSamples.addAndGet(events.size());
        });
    }

    public void recordDispatch(String functionName, Instant now) {
        Instant effectiveNow = advanceTime(now);
        runBoundedCleanup(effectiveNow, null);
        synchronized (globalEvents) {
            globalEvents.addLast(effectiveNow);
            retainedGlobalSamples.incrementAndGet();
            retainedGlobalSamples.addAndGet(-prune(globalEvents, effectiveNow));
            retainedGlobalSamples.addAndGet(-cap(
                    globalEvents, retainedGlobalSamples.get(), maxGlobalSamples));
        }

        if (!perFunctionEvents.containsKey(functionName)) {
            makeRoomForFunction(effectiveNow);
        }
        FunctionEvents state = perFunctionEvents.compute(functionName, (name, existing) -> {
            FunctionEvents current = existing;
            if (current == null) {
                if (!reserveFunctionState()) {
                    return null;
                }
                current = new FunctionEvents(name, new ConcurrentLinkedDeque<>(), 0);
            }
            synchronized (current.events) {
                int expired = prune(current.events, effectiveNow);
                removeSamples(current, expired);
                if (current.samples > 0
                        && (current.samples >= maxPerFunctionSamples || !reserveExtraSamples(1))) {
                    current.events.pollFirst();
                    current.events.addLast(effectiveNow);
                } else {
                    current.events.addLast(effectiveNow);
                    current.samples++;
                    retainedPerFunctionSamples.incrementAndGet();
                }
            }
            return current;
        });
        if (state != null) {
            queueForCleanup(state);
        }
    }

    public void removeFunctionState(String functionName) {
        FunctionEvents removed = perFunctionEvents.remove(functionName);
        if (removed != null) {
            functionStates.decrementAndGet();
            synchronized (removed.events) {
                removeSamples(removed, removed.samples);
                removed.events.clear();
            }
        }
    }

    public double estimateWaitSeconds(String functionName, int queueDepth, Instant now) {
        Instant effectiveNow = advanceTime(now);
        if (queueDepth <= 0) {
            cleanupGlobal(effectiveNow);
            runBoundedCleanup(effectiveNow, null);
            return 0.0;
        }
        FunctionEvents functionEvents = perFunctionEvents.get(functionName);
        if (functionEvents == null && !makeRoomForFunction(effectiveNow)) {
            cleanupGlobal(effectiveNow);
            runBoundedCleanup(effectiveNow, null);
            return Double.POSITIVE_INFINITY;
        }
        ThroughputSnapshot perFunction = snapshot(functionEvents, effectiveNow);
        double estimate;
        if (perFunction.samples() >= perFunctionMinSamples && perFunction.throughput() > 0) {
            estimate = queueDepth / perFunction.throughput();
            cleanupGlobal(effectiveNow);
        } else {
            ThroughputSnapshot global = snapshot(globalEvents, effectiveNow);
            estimate = global.throughput() <= 0
                    ? Double.POSITIVE_INFINITY
                    : queueDepth / global.throughput();
        }
        runBoundedCleanup(effectiveNow, functionName);
        return estimate;
    }

    RetentionSnapshot retentionSnapshot() {
        return new RetentionSnapshot(functionStates.get(), retainedGlobalSamples.get(),
                retainedPerFunctionSamples.get(), cleanupCandidates.size());
    }

    public void maintain(Instant now) {
        Instant effectiveNow = advanceTime(now);
        cleanupGlobal(effectiveNow, maintenanceSampleBudget);
        runBoundedCleanup(effectiveNow, null, maintenanceSampleBudget);
    }

    private ThroughputSnapshot snapshot(FunctionEvents state, Instant now) {
        if (state == null) {
            return new ThroughputSnapshot(0, 0.0);
        }
        ThroughputSnapshot snapshot;
        boolean empty;
        synchronized (state.events) {
            int removed = prune(state.events, now);
            removeSamples(state, removed);
            snapshot = throughputSnapshot(state.samples);
            empty = state.samples == 0;
        }
        if (empty) {
            removeIfStillEmpty(state);
        }
        return snapshot;
    }

    private ThroughputSnapshot snapshot(Deque<Instant> events, Instant now) {
        synchronized (events) {
            retainedGlobalSamples.addAndGet(-prune(events, now));
            return throughputSnapshot(retainedGlobalSamples.get());
        }
    }

    private ThroughputSnapshot throughputSnapshot(int samples) {
        double seconds = Math.max(1.0, (double) window.toSeconds());
        return new ThroughputSnapshot(samples, samples / seconds);
    }

    private void cleanupGlobal(Instant now) {
        synchronized (globalEvents) {
            retainedGlobalSamples.addAndGet(-prune(globalEvents, now));
        }
    }

    private void cleanupGlobal(Instant now, int maximumRemovals) {
        synchronized (globalEvents) {
            retainedGlobalSamples.addAndGet(-prune(globalEvents, now, maximumRemovals));
        }
    }

    private void runBoundedCleanup(Instant now, String excludedFunction) {
        runBoundedCleanup(now, excludedFunction, Integer.MAX_VALUE);
    }

    private void runBoundedCleanup(Instant now,
                                   String excludedFunction,
                                   int sampleBudget) {
        FunctionEvents first = null;
        int perStateSampleBudget = Math.max(1, sampleBudget / cleanupBudget);
        for (int checked = 0; checked < cleanupBudget; checked++) {
            FunctionEvents state = cleanupCandidates.poll();
            if (state == null) {
                return;
            }
            state.cleanupQueued.set(false);
            if (state == first) {
                queueForCleanup(state);
                return;
            }
            if (first == null) {
                first = state;
            }
            if (state.functionName.equals(excludedFunction)) {
                queueForCleanup(state);
                continue;
            }
            boolean empty;
            synchronized (state.events) {
                int removed = prune(state.events, now, perStateSampleBudget);
                removeSamples(state, removed);
                empty = state.samples == 0;
            }
            if (!empty) {
                queueForCleanup(state);
                continue;
            }
            perFunctionEvents.computeIfPresent(state.functionName, (name, current) -> {
                if (current != state) {
                    return current;
                }
                synchronized (state.events) {
                    if (state.samples == 0) {
                        functionStates.decrementAndGet();
                        return null;
                    }
                }
                queueForCleanup(state);
                return state;
            });
        }
    }

    private boolean makeRoomForFunction(Instant now) {
        if (functionStates.get() < maxFunctionStates) {
            return true;
        }
        if (evictExpiredFunctionState(now)) {
            return functionStates.get() < maxFunctionStates;
        }
        return false;
    }

    private boolean evictExpiredFunctionState(Instant now) {
        Instant cutoff = cutoff(now);
        int inspected = 0;
        for (String functionName : perFunctionEvents.keySet()) {
            if (inspected++ >= maxFunctionStates) {
                break;
            }
            AtomicBoolean removed = new AtomicBoolean();
            perFunctionEvents.computeIfPresent(functionName, (name, state) -> {
                synchronized (state.events) {
                    Instant latest = state.events.peekLast();
                    if (latest != null && !latest.isBefore(cutoff)) {
                        return state;
                    }
                    removeSamples(state, state.samples);
                    state.events.clear();
                    functionStates.decrementAndGet();
                    removed.set(true);
                    return null;
                }
            });
            if (removed.get()) {
                return true;
            }
        }
        return false;
    }

    private void queueForCleanup(FunctionEvents state) {
        if (state.cleanupQueued.compareAndSet(false, true)) {
            cleanupCandidates.add(state);
        }
    }

    private void removeIfStillEmpty(FunctionEvents state) {
        perFunctionEvents.computeIfPresent(state.functionName, (name, current) -> {
            if (current != state) {
                return current;
            }
            synchronized (state.events) {
                if (state.samples != 0) {
                    queueForCleanup(state);
                    return state;
                }
            }
            functionStates.decrementAndGet();
            return null;
        });
    }

    private boolean reserveFunctionState() {
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

    private boolean reserveExtraSamples(int samples) {
        if (samples == 0) {
            return true;
        }
        while (true) {
            int current = retainedExtraPerFunctionSamples.get();
            if (samples > maxExtraPerFunctionSamples - current) {
                return false;
            }
            if (retainedExtraPerFunctionSamples.compareAndSet(current, current + samples)) {
                return true;
            }
        }
    }

    private void removeSamples(FunctionEvents state, int removed) {
        if (removed == 0) {
            return;
        }
        int extraBefore = Math.max(0, state.samples - 1);
        state.samples -= removed;
        int extraAfter = Math.max(0, state.samples - 1);
        retainedExtraPerFunctionSamples.addAndGet(extraAfter - extraBefore);
        retainedPerFunctionSamples.addAndGet(-removed);
    }

    private int prune(Deque<Instant> events, Instant now) {
        return prune(events, now, Integer.MAX_VALUE);
    }

    private int prune(Deque<Instant> events, Instant now, int maximumRemovals) {
        int removed = 0;
        Instant cutoff = cutoff(now);
        while (removed < maximumRemovals) {
            Instant first = events.peekFirst();
            if (first == null || !first.isBefore(cutoff)) {
                return removed;
            }
            if (events.pollFirst() != null) {
                removed++;
            }
        }
        return removed;
    }

    private static int cap(Deque<Instant> events, int current, int maximum) {
        int removed = 0;
        while (current - removed > maximum) {
            if (events.pollFirst() != null) {
                removed++;
            }
        }
        return removed;
    }

    private Instant cutoff(Instant now) {
        try {
            return now.minus(window);
        } catch (DateTimeException | ArithmeticException ignored) {
            return Instant.MIN;
        }
    }

    private Instant advanceTime(Instant candidate) {
        while (true) {
            Instant previous = latestTime.get();
            if (candidate.compareTo(previous) <= 0) {
                return previous;
            }
            if (latestTime.compareAndSet(previous, candidate)) {
                return candidate;
            }
        }
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    record RetentionSnapshot(int functionStates,
                             int globalSamples,
                             int perFunctionSamples,
                             int cleanupCandidates) {
    }

    private record ThroughputSnapshot(int samples, double throughput) {
    }

    private static final class FunctionEvents {
        private final String functionName;
        private final Deque<Instant> events;
        private final AtomicBoolean cleanupQueued = new AtomicBoolean();
        private int samples;

        private FunctionEvents(String functionName, Deque<Instant> events, int samples) {
            this.functionName = functionName;
            this.events = events;
            this.samples = samples;
        }
    }
}
