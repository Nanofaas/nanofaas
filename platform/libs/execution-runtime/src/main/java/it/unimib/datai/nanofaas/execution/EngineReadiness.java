package it.unimib.datai.nanofaas.execution;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;

/**
 * Whether a generation looks dispatchable right now, answered from already-prepared in-memory
 * observations only. The engine calls this from inside its gate, as the {@code runnable}
 * predicate of {@link it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingIndex#select},
 * so an implementation must not take another lock, call a provider or a mutable registry, wait,
 * or acquire capacity — it observes, it does not reserve (ADR 0002). The authoritative
 * acquisition is {@link EngineDispatch#tryAcquire}, outside the gate.
 */
@FunctionalInterface
public interface EngineReadiness {

    /** Local observation: does this generation currently look able to take a dispatch? */
    boolean runnable(FunctionGeneration generation);
}
