package it.unimib.datai.nanofaas.modules.syncqueue;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.service.InvocationEnqueuer;
import it.unimib.datai.nanofaas.workloadmetrics.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadDiagnostics;

public final class SyncQueueInvocationEnqueuer implements InvocationEnqueuer {
    private final FunctionCapacityRegistry capacityRegistry;
    private final WorkloadDiagnostics diagnostics;

    public SyncQueueInvocationEnqueuer(FunctionCapacityRegistry capacityRegistry) {
        this(capacityRegistry, null);
    }

    public SyncQueueInvocationEnqueuer(FunctionCapacityRegistry capacityRegistry,
                                       WorkloadDiagnostics diagnostics) {
        this.capacityRegistry = capacityRegistry;
        this.diagnostics = diagnostics;
    }

    @Override
    public boolean enqueue(InvocationTask task) {
        return false;
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public boolean hasAvailableSlot(String functionName) {
        var state = capacityRegistry.state(functionName);
        return state != null && state.canDispatch();
    }

    @Override
    public boolean tryAcquireSlot(String functionName) {
        return capacityRegistry.tryAcquireSlot(functionName);
    }

    @Override
    public void releaseDispatchSlot(String functionName) {
        long holdNanos = capacityRegistry.releaseSlotAndGetHoldNanos(functionName);
        if (diagnostics != null && holdNanos >= 0) {
            diagnostics.recordDispatchSlotHold(functionName, holdNanos);
        }
    }
}
