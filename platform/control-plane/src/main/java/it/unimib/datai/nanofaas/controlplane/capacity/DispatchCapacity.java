package it.unimib.datai.nanofaas.controlplane.capacity;

import java.util.function.Consumer;
import java.util.function.LongConsumer;

/** Capacity authority reused by optional queue consumers; no name-based release. */
public interface DispatchCapacity {
    CapacityView register(String functionName, int concurrency);
    void remove(String functionName);
    CapacityView state(String functionName);
    FunctionGeneration activeGeneration(String functionName);
    /** Exact identity still tracked, including retirement/drain; never an active-admission guard. */
    boolean retainsGeneration(FunctionGeneration generation);
    DispatchOwnership tryAcquireLease(FunctionGeneration generation, LongConsumer onReleased);
    int configuredConcurrency(String functionName);
    int effectiveConcurrency(String functionName);
    int inFlight(String functionName);
    void setEffectiveConcurrency(String functionName, int concurrency);
    void addCapacityListener(Consumer<String> listener);
}
