package it.unimib.datai.nanofaas.modules.syncqueue;

import it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueService;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsSource;

public final class SyncQueueWorkloadMetricsSource implements WorkloadMetricsSource {
    private final SyncQueueService queue;
    private final FunctionCapacityRegistry capacityRegistry;

    public SyncQueueWorkloadMetricsSource(SyncQueueService queue,
                                           FunctionCapacityRegistry capacityRegistry) {
        this.queue = queue;
        this.capacityRegistry = capacityRegistry;
    }

    @Override
    public int queueDepth(String functionName) {
        return queue.queuedItems(functionName);
    }

    @Override
    public int inFlight(String functionName) {
        return capacityRegistry.inFlight(functionName);
    }

    @Override
    public int effectiveConcurrency(String functionName) {
        return capacityRegistry.effectiveConcurrency(functionName);
    }

    @Override
    public int dispatchableBacklog(String functionName) {
        var state = capacityRegistry.state(functionName);
        return state != null && state.canDispatch() ? queue.queuedItems(functionName) : 0;
    }
}
