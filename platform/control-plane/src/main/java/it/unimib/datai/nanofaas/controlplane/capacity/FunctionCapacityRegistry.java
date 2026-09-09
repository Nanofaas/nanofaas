package it.unimib.datai.nanofaas.controlplane.capacity;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single, core-owned capacity authority: what limits a function's in-flight
 * work, independently of any optional queue or governor module.
 *
 * <p>Each registration is a {@link FunctionGeneration}, minted here: this is the
 * core's generation authority, and {@link #activeGeneration(String)} is where the
 * other per-function resource owners read the identity they attribute their own
 * state to. Removing a function retires its current generation (no further
 * acquisition) while its already-acquired slots keep draining under the old
 * identity; re-registering the same name creates a new generation, so an old
 * lease can never decrement the new registration's state (ADR 0001 §7 / invariant
 * I7). A {@link DispatchLease} carries its generation, so completion paths release
 * exactly the capacity the attempt acquired (invariant I4) rather than releasing by
 * name alone.
 *
 * <p>The name-based methods ({@link #tryAcquireSlot},
 * {@link #releaseSlotAndGetHoldNanos}, {@link #state}, ...) are the temporary
 * adapters retained for legacy callers; their retirement is P20. Production
 * direct, retry and queue dispatch paths use {@link #tryAcquireLease}.
 */
public final class FunctionCapacityRegistry {
    private static final Logger log = LoggerFactory.getLogger(FunctionCapacityRegistry.class);

    private final LongSupplier nanoTime;
    private final AtomicLong nextGeneration = new AtomicLong(1);
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Set<Consumer<String>> capacityListeners = ConcurrentHashMap.newKeySet();

    public FunctionCapacityRegistry() {
        this(System::nanoTime);
    }

    public FunctionCapacityRegistry(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    /**
     * Registers a listener invoked (after the registry lock is released) when a function
     * transitions to having at least one free dispatch slot. Optional: a registry with no
     * listeners behaves exactly as before.
     */
    public void addCapacityListener(Consumer<String> listener) {
        capacityListeners.add(listener);
    }

    /**
     * Registers (or re-registers) a function's configured concurrency and returns the
     * active generation's state. Re-registering while a previous generation still drains
     * retires that generation and creates a new one.
     */
    public FunctionCapacityState register(String functionName, int configuredConcurrency) {
        return register(functionName, configuredConcurrency, true);
    }

    private FunctionCapacityState register(String functionName, int configuredConcurrency, boolean lifecycleOwned) {
        Entry entry = entries.computeIfAbsent(functionName, ignored -> new Entry());
        entry.lock.lock();
        try {
            if (entries.get(functionName) != entry) {
                return register(functionName, configuredConcurrency, lifecycleOwned);
            }
            entry.lifecycleOwned |= lifecycleOwned;
            FunctionCapacityState current = entry.state;
            if (current == null || !current.isActive()) {
                if (current != null && current.inFlight() > 0) {
                    // Retire the old generation: it keeps its slots and drains on release.
                    entry.draining.put(entry.generation, current);
                }
                FunctionGeneration newGeneration =
                        new FunctionGeneration(functionName, nextGeneration.getAndIncrement());
                FunctionCapacityState fresh = new FunctionCapacityState(configuredConcurrency, nanoTime,
                        () -> onStateDrained(functionName, entry, newGeneration));
                entry.state = fresh;
                entry.generation = newGeneration;
                return fresh;
            }
            current.concurrency(configuredConcurrency);
            return current;
        } finally {
            entry.lock.unlock();
        }
    }

    /**
     * Retires the current generation. The entry stays while any slot is held (active or a
     * draining generation) and disappears once everything drains.
     */
    public void remove(String functionName) {
        for (; ; ) {
            Entry entry = entries.get(functionName);
            if (entry == null) {
                return;
            }
            entry.lock.lock();
            try {
                if (entries.get(functionName) != entry) {
                    continue;
                }
                FunctionCapacityState state = entry.state;
                if (state == null) {
                    entries.remove(functionName, entry);
                } else {
                    state.deactivate();
                    if (fullyDrained(entry)) {
                        entries.remove(functionName, entry);
                    }
                }
                return;
            } finally {
                entry.lock.unlock();
            }
        }
    }

    /** The active generation's state, or {@code null} when absent or retired. */
    public FunctionCapacityState state(String functionName) {
        Entry entry = entries.get(functionName);
        if (entry == null) {
            return null;
        }
        entry.lock.lock();
        try {
            FunctionCapacityState state = activeState(functionName, entry);
            return state;
        } finally {
            entry.lock.unlock();
        }
    }

    /**
     * Whether any generation of the name is still known here — the active one, or a
     * retired one that has not finished draining.
     */
    public boolean hasGeneration(String functionName) {
        return entries.containsKey(functionName);
    }

    /**
     * The identity of the name's active incarnation, or {@code null} when the name has
     * no registration (or only retired ones still draining).
     *
     * <p>This is the read other per-function resource owners use to fence a stale event:
     * an event carrying an older {@link FunctionGeneration} may close what that
     * generation owned, but must neither recreate an entry nor mutate the current one
     * (ADR 0001 §8.2, invariant I7).
     */
    public FunctionGeneration activeGeneration(String functionName) {
        Entry entry = entries.get(functionName);
        if (entry == null) {
            return null;
        }
        entry.lock.lock();
        try {
            return activeState(functionName, entry) == null ? null : entry.generation;
        } finally {
            entry.lock.unlock();
        }
    }

    /**
     * Runs an accounting acquisition only while {@code expectedGeneration} is the
     * exact active incarnation. Holding the entry lock across the callback makes the
     * lifecycle check atomic with the acquisition: remove/re-register linearizes
     * entirely before or after it.
     *
     * <p>Package-private because this is an authority hook for sibling ownership
     * primitives, not another public lifecycle API. Returns {@code null} when the
     * generation is absent, retired, or superseded.
     */
    <T> T withActiveGeneration(FunctionGeneration expectedGeneration, Supplier<T> acquisition) {
        Entry entry = entries.get(expectedGeneration.functionName());
        if (entry == null) {
            return null;
        }
        entry.lock.lock();
        try {
            FunctionCapacityState state = activeState(expectedGeneration.functionName(), entry);
            if (state == null || !expectedGeneration.equals(entry.generation)) {
                return null;
            }
            return acquisition.get();
        } finally {
            entry.lock.unlock();
        }
    }

    /**
     * Name-based acquisition (temporary adapter for the queue modules). Acquires against
     * the active generation only.
     */
    public boolean tryAcquireSlot(String functionName) {
        Entry entry = entries.get(functionName);
        if (entry == null) {
            return false;
        }
        entry.lock.lock();
        try {
            FunctionCapacityState state = activeState(functionName, entry);
            if (state == null || !state.tryAcquireSlot()) return false;
            return true;
        } finally {
            entry.lock.unlock();
        }
    }

    /**
     * Attempt-scoped acquisition used by the core dispatch path. Auto-registers the
     * function for standalone callers on first use. In production the core registration
     * listener owns configuration, so an old task cannot override an update or governor.
     * Acquires against the active generation and returns the
     * lease the attempt owns, or {@code null} when the function is at capacity.
     */
    public DispatchLease tryAcquireLease(String functionName, int configuredConcurrency) {
        Entry entry = entries.get(functionName);
        if (entry == null) {
            register(functionName, configuredConcurrency, false);
            entry = entries.get(functionName);
            if (entry == null) {
                return null;
            }
        }
        entry.lock.lock();
        try {
            FunctionCapacityState state = activeState(functionName, entry);
            if (state == null) return null;
            if (!entry.lifecycleOwned) state.concurrency(configuredConcurrency);
            if (!state.tryAcquireSlot()) return null;
            entry.leased.merge(state, 1, Integer::sum);
            FunctionGeneration generation = entry.generation;
            return new DispatchLease(generation, () -> releaseSlot(generation));
        } finally {
            entry.lock.unlock();
        }
    }

    /**
     * Legacy release: may drain only slots acquired without leases. Production completion
     * uses the attempt's lease; the name adapter must never consume a lease-owned slot.
     */
    public long releaseSlotAndGetHoldNanos(String functionName) {
        Entry entry = entries.get(functionName);
        if (entry == null) {
            return -1L;
        }
        entry.lock.lock();
        try {
            return releaseAnySlot(entry);
        } finally {
            entry.lock.unlock();
        }
    }

    public int configuredConcurrency(String functionName) {
        FunctionCapacityState state = state(functionName);
        return state == null ? 0 : state.configuredConcurrency();
    }

    public int effectiveConcurrency(String functionName) {
        FunctionCapacityState state = state(functionName);
        return state == null ? 0 : state.effectiveConcurrency();
    }

    /**
     * In-flight count of the active generation, matching the pre-move contract: a retired
     * generation's still-draining slots are internal drain bookkeeping, not reported work.
     */
    public int inFlight(String functionName) {
        FunctionCapacityState state = state(functionName);
        return state == null ? 0 : state.inFlight();
    }

    public void setEffectiveConcurrency(String functionName, int concurrency) {
        Entry entry = entries.get(functionName);
        if (entry == null) {
            return;
        }
        boolean opened;
        entry.lock.lock();
        try {
            FunctionCapacityState state = activeState(functionName, entry);
            if (state == null) {
                return;
            }
            boolean wasDispatchable = state.canDispatch();
            state.setEffectiveConcurrency(concurrency);
            opened = !wasDispatchable && state.canDispatch();
        } finally {
            entry.lock.unlock();
        }
        // Fire outside the entry lock: listeners may take other locks.
        if (opened) {
            for (Consumer<String> listener : capacityListeners) {
                try {
                    listener.accept(functionName);
                } catch (RuntimeException ex) {
                    log.warn("Capacity listener failed for function {}", functionName, ex);
                }
            }
        }
    }

    int entryCount() {
        return entries.size();
    }

    private FunctionCapacityState activeState(String functionName, Entry entry) {
        return entries.get(functionName) == entry && entry.state != null && entry.state.isActive()
                ? entry.state
                : null;
    }

    private long releaseAnySlot(Entry entry) {
        for (FunctionCapacityState old : new java.util.ArrayList<>(entry.draining.values())) {
            if (old.inFlight() > entry.leased.getOrDefault(old, 0)) return old.releaseSlotAndGetHoldNanos();
        }
        FunctionCapacityState current = entry.state;
        return current != null && current.inFlight() > entry.leased.getOrDefault(current, 0)
                ? current.releaseSlotAndGetHoldNanos() : -1L;
    }

    /** Release for a lease carrying its generation: never crosses into another generation. */
    private long releaseSlot(FunctionGeneration generation) {
        Entry entry = entries.get(generation.functionName());
        if (entry == null) return -1L;
        entry.lock.lock();
        try {
            FunctionCapacityState acquired = generation.equals(entry.generation)
                    ? entry.state : entry.draining.get(generation);
            if (acquired == null) return -1L;
            entry.leased.computeIfPresent(acquired, (state, count) -> count == 1 ? null : count - 1);
            return acquired.releaseSlotAndGetHoldNanos();
        } finally {
            entry.lock.unlock();
        }
    }

    /** Queue admission: bind to the exact state that supplied the queued work. */
    public DispatchLease tryAcquireLease(String functionName, FunctionCapacityState expectedState,
                                         java.util.function.LongConsumer onReleased) {
        Entry entry = entries.get(functionName);
        if (entry == null) return null;
        entry.lock.lock();
        try {
            FunctionCapacityState current = activeState(functionName, entry);
            if (current == null || current != expectedState || !current.tryAcquireSlot()) return null;
            entry.leased.merge(current, 1, Integer::sum);
            FunctionGeneration generation = entry.generation;
            return new DispatchLease(generation, () -> {
                long held = releaseSlot(generation);
                onReleased.accept(held);
            });
        } finally {
            entry.lock.unlock();
        }
    }

    /**
     * Called (through a state's drain callback) when a generation becomes inactive with no
     * in-flight work left: a retired current generation drops the whole entry, a draining old
     * generation drops just itself. Runs under the entry lock (reentrant).
     */
    private void onStateDrained(String functionName, Entry entry, FunctionGeneration generation) {
        entry.lock.lock();
        try {
            if (entries.get(functionName) != entry) {
                return;
            }
            if (generation.equals(entry.generation)) {
                if (fullyDrained(entry)) {
                    entries.remove(functionName, entry);
                }
            } else {
                entry.draining.remove(generation);
            }
        } finally {
            entry.lock.unlock();
        }
    }

    private boolean fullyDrained(Entry entry) {
        if (entry.state != null && entry.state.inFlight() > 0) {
            return false;
        }
        for (FunctionCapacityState draining : entry.draining.values()) {
            if (draining.inFlight() > 0) {
                return false;
            }
        }
        return true;
    }

    private static final class Entry {
        private final ReentrantLock lock = new ReentrantLock();
        private FunctionCapacityState state;
        private final Map<FunctionCapacityState, Integer> leased = new IdentityHashMap<>();
        private FunctionGeneration generation;
        private boolean lifecycleOwned;
        private final Map<FunctionGeneration, FunctionCapacityState> draining = new LinkedHashMap<>();
    }
}
