package it.unimib.datai.nanofaas.modules.syncqueue;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.service.InvocationEnqueuer;
import it.unimib.datai.nanofaas.workloadmetrics.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadDiagnostics;

import java.util.function.Consumer;

public final class SyncQueueInvocationEnqueuer implements InvocationEnqueuer {
    private final FunctionCapacityRegistry capacityRegistry;
    private final WorkloadDiagnostics diagnostics;
    private final Consumer<String> slotReleaseListener;

    public SyncQueueInvocationEnqueuer(FunctionCapacityRegistry capacityRegistry) {
        this(capacityRegistry, null, ignored -> { });
    }

    public SyncQueueInvocationEnqueuer(FunctionCapacityRegistry capacityRegistry,
                                       WorkloadDiagnostics diagnostics) {
        this(capacityRegistry, diagnostics, ignored -> { });
    }

    public SyncQueueInvocationEnqueuer(FunctionCapacityRegistry capacityRegistry,
                                       WorkloadDiagnostics diagnostics,
                                       Consumer<String> slotReleaseListener) {
        this.capacityRegistry = capacityRegistry;
        this.diagnostics = diagnostics;
        this.slotReleaseListener = slotReleaseListener;
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
        slotReleaseListener.accept(functionName);
    }
}
