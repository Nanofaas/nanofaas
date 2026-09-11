package it.unimib.datai.nanofaas.modules.syncqueue;

import it.unimib.datai.nanofaas.controlplane.capacity.DispatchCapacity;
import it.unimib.datai.nanofaas.controlplane.capacity.DispatchOwnership;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.QueuedDispatchCapacity;
import it.unimib.datai.nanofaas.controlplane.service.InvocationEnqueuer;
import it.unimib.datai.nanofaas.controlplane.service.RetryScheduler;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueGateway;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectedException;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadDiagnostics;
import java.util.function.Consumer;

public final class SyncQueueInvocationEnqueuer implements RetryScheduler, QueuedDispatchCapacity {
    private final DispatchCapacity capacityRegistry;
    private final WorkloadDiagnostics diagnostics;
    private final Consumer<String> slotReleaseListener;
    private final SyncQueueGateway gateway;

    public SyncQueueInvocationEnqueuer(DispatchCapacity capacityRegistry) {
        this(capacityRegistry, null, ignored -> { });
    }

    public SyncQueueInvocationEnqueuer(DispatchCapacity capacityRegistry,
                                       WorkloadDiagnostics diagnostics) {
        this(capacityRegistry, diagnostics, ignored -> { });
    }

    public SyncQueueInvocationEnqueuer(DispatchCapacity capacityRegistry,
                                       WorkloadDiagnostics diagnostics,
                                       Consumer<String> slotReleaseListener) {
        this(capacityRegistry, diagnostics, slotReleaseListener, SyncQueueGateway.noOp());
    }

    public SyncQueueInvocationEnqueuer(DispatchCapacity capacityRegistry,
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

    @Override
    public boolean hasAvailableSlot(String functionName) {
        var state = capacityRegistry.state(functionName);
        return state != null && state.canDispatch();
    }

    @Override
    public DispatchOwnership tryAcquireLease(InvocationTask task) {
        String name = task.functionName();
        var generation = capacityRegistry.activeGeneration(name);
        return capacityRegistry.tryAcquireLease(generation, held -> {
            try {
                if (diagnostics != null && held >= 0 && generation.equals(capacityRegistry.activeGeneration(name))) diagnostics.recordDispatchSlotHold(name, held);
            } finally {
                slotReleaseListener.accept(name);
            }
        });
    }

}
