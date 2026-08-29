package it.unimib.datai.nanofaas.workloadmetrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

public final class FunctionCapacityRegistry implements WorkloadCapacityController {
    private final LongSupplier nanoTime;
    private final Map<String, FunctionCapacityState> states = new ConcurrentHashMap<>();

    public FunctionCapacityRegistry() { this(System::nanoTime); }

    FunctionCapacityRegistry(LongSupplier nanoTime) { this.nanoTime = nanoTime; }

    public FunctionCapacityState register(String functionName, int configuredConcurrency) {
        return states.compute(functionName, (name, state) -> {
            if (state == null) return new FunctionCapacityState(configuredConcurrency, nanoTime);
            state.concurrency(configuredConcurrency);
            return state;
        });
    }

    public void remove(String functionName) { states.remove(functionName); }

    public FunctionCapacityState state(String functionName) { return states.get(functionName); }

    public boolean tryAcquireSlot(String functionName) {
        FunctionCapacityState state = states.get(functionName);
        return state != null && state.tryAcquireSlot();
    }

    public long releaseSlotAndGetHoldNanos(String functionName) {
        FunctionCapacityState state = states.get(functionName);
        return state == null ? -1 : state.releaseSlotAndGetHoldNanos();
    }

    public int configuredConcurrency(String functionName) {
        FunctionCapacityState state = states.get(functionName);
        return state == null ? 0 : state.configuredConcurrency();
    }

    public int effectiveConcurrency(String functionName) {
        FunctionCapacityState state = states.get(functionName);
        return state == null ? 0 : state.effectiveConcurrency();
    }

    public int inFlight(String functionName) {
        FunctionCapacityState state = states.get(functionName);
        return state == null ? 0 : state.inFlight();
    }

    @Override
    public void setEffectiveConcurrency(String functionName, int concurrency) {
        FunctionCapacityState state = states.get(functionName);
        if (state != null) state.setEffectiveConcurrency(concurrency);
    }
}
