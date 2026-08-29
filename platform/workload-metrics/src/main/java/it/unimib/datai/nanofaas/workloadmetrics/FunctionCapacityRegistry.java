package it.unimib.datai.nanofaas.workloadmetrics;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

public final class FunctionCapacityRegistry implements WorkloadCapacityController {
    private final LongSupplier nanoTime;
    private final Map<String, FunctionCapacityState> states = new HashMap<>();
    private final Map<String, FunctionCapacityState> retiredStates = new HashMap<>();

    public FunctionCapacityRegistry() { this(System::nanoTime); }

    FunctionCapacityRegistry(LongSupplier nanoTime) { this.nanoTime = nanoTime; }

    public synchronized FunctionCapacityState register(String functionName, int configuredConcurrency) {
        FunctionCapacityState retired = retiredStates.get(functionName);
        if (retired != null) {
            throw new IllegalStateException("Cannot re-register function with active slots: " + functionName);
        }
        FunctionCapacityState state = states.get(functionName);
        if (state == null) {
            state = new FunctionCapacityState(configuredConcurrency, nanoTime);
            states.put(functionName, state);
        } else {
            state.concurrency(configuredConcurrency);
        }
        return state;
    }

    public synchronized void remove(String functionName) {
        FunctionCapacityState state = states.remove(functionName);
        if (state != null && state.inFlight() > 0) retiredStates.put(functionName, state);
    }

    public synchronized FunctionCapacityState state(String functionName) { return states.get(functionName); }

    public synchronized boolean tryAcquireSlot(String functionName) {
        FunctionCapacityState state = states.get(functionName);
        return state != null && state.tryAcquireSlot();
    }

    public synchronized long releaseSlotAndGetHoldNanos(String functionName) {
        FunctionCapacityState state = states.get(functionName);
        if (state != null) return state.releaseSlotAndGetHoldNanos();
        state = retiredStates.get(functionName);
        if (state == null) return -1;
        long holdNanos = state.releaseSlotAndGetHoldNanos();
        if (state.inFlight() == 0) retiredStates.remove(functionName);
        return holdNanos;
    }

    public synchronized int configuredConcurrency(String functionName) {
        FunctionCapacityState state = states.get(functionName);
        return state == null ? 0 : state.configuredConcurrency();
    }

    public synchronized int effectiveConcurrency(String functionName) {
        FunctionCapacityState state = states.get(functionName);
        return state == null ? 0 : state.effectiveConcurrency();
    }

    public synchronized int inFlight(String functionName) {
        FunctionCapacityState state = states.get(functionName);
        return state == null ? 0 : state.inFlight();
    }

    @Override
    public synchronized void setEffectiveConcurrency(String functionName, int concurrency) {
        FunctionCapacityState state = states.get(functionName);
        if (state != null) state.setEffectiveConcurrency(concurrency);
    }
}
