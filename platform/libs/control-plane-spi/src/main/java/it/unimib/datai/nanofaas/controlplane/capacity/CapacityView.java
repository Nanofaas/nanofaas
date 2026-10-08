package it.unimib.datai.nanofaas.controlplane.capacity;

/** Read-only view of one existing generation, including its physical drain. */
public interface CapacityView {
    FunctionGeneration generation();
    int inFlight();
    int configuredConcurrency();
    int effectiveConcurrency();
    boolean canDispatch();
}
