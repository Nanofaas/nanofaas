package it.unimib.datai.nanofaas.controlplane.scheduler;

import it.unimib.datai.nanofaas.controlplane.capacity.DispatchOwnership;

/** Scheduler capacity capability, independent of queue admission and HTTP mode. */
public interface QueuedDispatchCapacity {
    boolean hasAvailableSlot(String functionName);
    DispatchOwnership tryAcquireLease(InvocationTask task);
}
