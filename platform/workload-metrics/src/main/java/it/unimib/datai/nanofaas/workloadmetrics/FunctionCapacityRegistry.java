package it.unimib.datai.nanofaas.workloadmetrics;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

public final class FunctionCapacityRegistry implements WorkloadCapacityController {
    private final LongSupplier nanoTime;
    private final ConcurrentHashMap<String, FunctionCapacityState> states = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, FunctionCapacityState> retiredStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> lifecycleLocks = new ConcurrentHashMap<>();

    public FunctionCapacityRegistry() { this(System::nanoTime); }

    FunctionCapacityRegistry(LongSupplier nanoTime) { this.nanoTime = nanoTime; }

    public void register(String functionName, int configuredConcurrency) {
        withLock(functionName, () -> {
            FunctionCapacityState retired = retiredStates.get(functionName);
            if (retired != null) {
                if (retired.inFlight() > 0) {
                    throw new IllegalStateException("Cannot re-register function with active slots: " + functionName);
                }
                retiredStates.remove(functionName, retired);
            }
            FunctionCapacityState state = states.get(functionName);
            if (state == null) {
                states.put(functionName, new FunctionCapacityState(configuredConcurrency, nanoTime));
            } else {
                state.concurrency(configuredConcurrency);
            }
            return null;
        });
    }

    public void remove(String functionName) {
        withLock(functionName, () -> {
            FunctionCapacityState state = states.remove(functionName);
            if (state != null) {
                state.deactivate();
                if (state.inFlight() > 0) retiredStates.put(functionName, state);
            }
            return null;
        });
    }

    FunctionCapacityState state(String functionName) {
        return withLock(functionName, () -> states.get(functionName));
    }

    public boolean tryAcquireSlot(String functionName) {
        return withLock(functionName, () -> {
            FunctionCapacityState state = states.get(functionName);
            return state != null && state.tryAcquireSlot();
        });
    }

    public long releaseSlotAndGetHoldNanos(String functionName) {
        return withLock(functionName, () -> {
            FunctionCapacityState state = states.get(functionName);
            if (state != null) return state.releaseSlotAndGetHoldNanos();
            state = retiredStates.get(functionName);
            if (state == null) return -1L;
            long holdNanos = state.releaseSlotAndGetHoldNanos();
            if (state.inFlight() == 0) retiredStates.remove(functionName, state);
            return holdNanos;
        });
    }

    public int configuredConcurrency(String functionName) {
        return withLock(functionName, () -> {
            FunctionCapacityState state = states.get(functionName);
            return state == null ? 0 : state.configuredConcurrency();
        });
    }

    public int effectiveConcurrency(String functionName) {
        return withLock(functionName, () -> {
            FunctionCapacityState state = states.get(functionName);
            return state == null ? 0 : state.effectiveConcurrency();
        });
    }

    public int inFlight(String functionName) {
        return withLock(functionName, () -> {
            FunctionCapacityState state = states.get(functionName);
            return state == null ? 0 : state.inFlight();
        });
    }

    @Override
    public void setEffectiveConcurrency(String functionName, int concurrency) {
        withLock(functionName, () -> {
            FunctionCapacityState state = states.get(functionName);
            if (state != null) state.setEffectiveConcurrency(concurrency);
            return null;
        });
    }

    private <T> T withLock(String functionName, java.util.function.Supplier<T> operation) {
        ReentrantLock lock = lifecycleLocks.computeIfAbsent(functionName, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return operation.get();
        } finally {
            lock.unlock();
        }
    }
}
