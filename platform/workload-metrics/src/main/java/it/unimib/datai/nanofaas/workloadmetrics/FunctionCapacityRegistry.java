package it.unimib.datai.nanofaas.workloadmetrics;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.function.LongSupplier;

public final class FunctionCapacityRegistry implements WorkloadCapacityController {
    private static final Logger log = LoggerFactory.getLogger(FunctionCapacityRegistry.class);
    private final LongSupplier nanoTime;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Set<Consumer<String>> capacityListeners = ConcurrentHashMap.newKeySet();

    public FunctionCapacityRegistry() { this(System::nanoTime); }

    FunctionCapacityRegistry(LongSupplier nanoTime) { this.nanoTime = nanoTime; }

    /**
     * Registers a listener invoked (after the registry lock is released) when a function
     * transitions to having at least one free dispatch slot. A queue scheduler subscribes so a
     * concurrency-limit increase that opens capacity - which arrives through
     * {@link WorkloadCapacityController#setEffectiveConcurrency} from the governor, not through
     * any dispatch completion - can wake a worker parked on that function. Optional: a registry
     * with no listeners behaves exactly as before.
     */
    public void addCapacityListener(Consumer<String> listener) {
        capacityListeners.add(listener);
    }

    public FunctionCapacityState register(String functionName, int configuredConcurrency) {
        Entry entry = entries.computeIfAbsent(functionName, ignored -> new Entry());
        entry.lock.lock();
        try {
            if (entries.get(functionName) != entry) {
                return register(functionName, configuredConcurrency);
            }
            FunctionCapacityState state = entry.state;
            if (state == null || !state.isActive()) {
                if (state != null && state.inFlight() > 0) {
                    // Delete-then-recreate under load: the old slots are still held by this
                    // function, and releases are keyed by name only, so they have to land on
                    // the same state. Reuse it rather than refusing the registration.
                    state.reactivate(configuredConcurrency);
                    return state;
                }
                state = new FunctionCapacityState(configuredConcurrency, nanoTime,
                        () -> removeDrained(functionName, entry));
                entry.state = state;
            } else {
                state.concurrency(configuredConcurrency);
            }
            return state;
        } finally {
            entry.lock.unlock();
        }
    }

    public void remove(String functionName) {
        for (;;) {
            Entry entry = entries.get(functionName);
            if (entry == null) return;
            entry.lock.lock();
            try {
                if (entries.get(functionName) != entry) continue;
                FunctionCapacityState state = entry.state;
                if (state == null) {
                    entries.remove(functionName, entry);
                } else {
                    state.deactivate();
                    if (state.inFlight() == 0) entries.remove(functionName, entry);
                }
                return;
            } finally {
                entry.lock.unlock();
            }
        }
    }

    public FunctionCapacityState state(String functionName) {
        Entry entry = entries.get(functionName);
        if (entry == null) return null;
        entry.lock.lock();
        try {
            return entries.get(functionName) == entry && entry.state != null && entry.state.isActive()
                    ? entry.state : null;
        } finally {
            entry.lock.unlock();
        }
    }

    public boolean hasGeneration(String functionName) {
        return entries.containsKey(functionName);
    }

    public boolean tryAcquireSlot(String functionName) {
        Entry entry = entries.get(functionName);
        if (entry == null) return false;
        entry.lock.lock();
        try {
            FunctionCapacityState state = current(functionName, entry);
            return state != null && state.tryAcquireSlot();
        } finally {
            entry.lock.unlock();
        }
    }

    public long releaseSlotAndGetHoldNanos(String functionName) {
        Entry entry = entries.get(functionName);
        if (entry == null) return -1L;
        entry.lock.lock();
        try {
            FunctionCapacityState state = currentOrRetired(functionName, entry);
            return state == null ? -1L : state.releaseSlotAndGetHoldNanos();
        } finally {
            entry.lock.unlock();
        }
    }

    public int configuredConcurrency(String functionName) {
        FunctionCapacityState state = activeState(functionName);
        return state == null ? 0 : state.configuredConcurrency();
    }

    public int effectiveConcurrency(String functionName) {
        FunctionCapacityState state = activeState(functionName);
        return state == null ? 0 : state.effectiveConcurrency();
    }

    public int inFlight(String functionName) {
        FunctionCapacityState state = activeState(functionName);
        return state == null ? 0 : state.inFlight();
    }

    @Override
    public void setEffectiveConcurrency(String functionName, int concurrency) {
        Entry entry = entries.get(functionName);
        if (entry == null) return;
        boolean opened;
        entry.lock.lock();
        try {
            FunctionCapacityState state = current(functionName, entry);
            if (state == null) return;
            boolean wasDispatchable = state.canDispatch();
            state.setEffectiveConcurrency(concurrency);
            opened = !wasDispatchable && state.canDispatch();
        } finally {
            entry.lock.unlock();
        }
        // Fire outside the entry lock: listeners may take other locks (e.g. a queue monitor),
        // and the scheduler's scan path acquires this registry lock while holding the queue
        // monitor, so notifying under the registry lock would invert that order.
        if (opened) {
            for (Consumer<String> listener : capacityListeners) {
                try {
                    listener.accept(functionName);
                } catch (RuntimeException ex) {
                    // This runs inside the concurrency governor's setEffectiveConcurrency loop.
                    // One misbehaving subscriber must not abort the loop and leave the remaining
                    // functions without their capacity update.
                    log.warn("Capacity listener failed for function {}", functionName, ex);
                }
            }
        }
    }

    int entryCount() { return entries.size(); }

    private FunctionCapacityState activeState(String functionName) {
        Entry entry = entries.get(functionName);
        if (entry == null) return null;
        entry.lock.lock();
        try {
            return current(functionName, entry);
        } finally {
            entry.lock.unlock();
        }
    }

    private FunctionCapacityState current(String functionName, Entry entry) {
        FunctionCapacityState state = entry.state;
        return entries.get(functionName) == entry && state != null && state.isActive() ? state : null;
    }

    private FunctionCapacityState currentOrRetired(String functionName, Entry entry) {
        return entries.get(functionName) == entry ? entry.state : null;
    }

    private void removeDrained(String functionName, Entry entry) {
        entry.lock.lock();
        try {
            if (entries.get(functionName) == entry && entry.state != null
                    && !entry.state.isActive() && entry.state.inFlight() == 0) {
                entries.remove(functionName, entry);
            }
        } finally {
            entry.lock.unlock();
        }
    }

    private static final class Entry {
        private final ReentrantLock lock = new ReentrantLock();
        private FunctionCapacityState state;
    }
}
