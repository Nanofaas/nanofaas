package it.unimib.datai.nanofaas.modules.syncqueue;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.service.InvocationEnqueuer;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueGateway;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectedException;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadDiagnostics;

import java.util.function.Consumer;

public final class SyncQueueInvocationEnqueuer implements InvocationEnqueuer {
    private final FunctionCapacityRegistry capacityRegistry;
    private final WorkloadDiagnostics diagnostics;
    private final Consumer<String> slotReleaseListener;
    private final SyncQueueGateway gateway;

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
        this(capacityRegistry, diagnostics, slotReleaseListener, SyncQueueGateway.noOp());
    }

    public SyncQueueInvocationEnqueuer(FunctionCapacityRegistry capacityRegistry,
                                       WorkloadDiagnostics diagnostics,
                                       Consumer<String> slotReleaseListener,
                                       SyncQueueGateway gateway) {
        this.capacityRegistry = capacityRegistry;
        this.diagnostics = diagnostics;
        this.slotReleaseListener = slotReleaseListener;
        this.gateway = gateway;
    }

    @Override
    public boolean enqueue(InvocationTask task) {
        try {
            gateway.enqueueOrThrow(task);
            return true;
        } catch (SyncQueueRejectedException _) {
            return false;
        }
    }

    /**
     * False on purpose, and not the inverse of {@link #enqueue}: the async {@code :enqueue}
     * endpoint must keep answering 501 under this provider. The one caller of {@code enqueue}
     * that does not consult this flag is the retry path in {@code ExecutionCompletionHandler};
     * both admission sites are gated on it.
     */
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
    public it.unimib.datai.nanofaas.controlplane.capacity.DispatchLease tryAcquireLease(InvocationTask task) {
        String name = task.functionName();
        return capacityRegistry.tryAcquireLease(name, capacityRegistry.state(name), held -> {
            try {
                if (diagnostics != null && held >= 0) diagnostics.recordDispatchSlotHold(name, held);
            } finally {
                slotReleaseListener.accept(name);
            }
        });
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
