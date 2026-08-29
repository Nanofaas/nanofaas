package it.unimib.datai.nanofaas.workloadmetrics;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

public final class FunctionCapacityRegistry implements WorkloadCapacityController {
    private final LongSupplier nanoTime;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public FunctionCapacityRegistry() { this(System::nanoTime); }

    FunctionCapacityRegistry(LongSupplier nanoTime) { this.nanoTime = nanoTime; }

    public FunctionCapacityState register(String functionName, int configuredConcurrency) {
        return register(functionName, configuredConcurrency, false);
    }

    public FunctionCapacityState register(String functionName, int configuredConcurrency,
                                          boolean replaceRetiredGeneration) {
        Entry entry = entries.computeIfAbsent(functionName, ignored -> new Entry());
        entry.lock.lock();
        try {
            if (entries.get(functionName) != entry) {
                return register(functionName, configuredConcurrency, replaceRetiredGeneration);
            }
            FunctionCapacityState state = entry.state;
            if (state == null || !state.isActive()) {
                if (state != null && state.inFlight() > 0) {
                    if (!replaceRetiredGeneration) {
                        throw new IllegalStateException("Cannot re-register function with active slots: " + functionName);
                    }
                    Entry replacement = new Entry();
                    entries.replace(functionName, entry, replacement);
                    return register(functionName, configuredConcurrency, false);
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
        entry.lock.lock();
        try {
            FunctionCapacityState state = current(functionName, entry);
            if (state != null) state.setEffectiveConcurrency(concurrency);
        } finally {
            entry.lock.unlock();
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
