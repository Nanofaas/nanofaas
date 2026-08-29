package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;
import it.unimib.datai.nanofaas.controlplane.service.ScalingMetricsSource;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsSource;

/** Async provider plus the temporary Task 5 compatibility bridge. */
public class AsyncQueueWorkloadMetricsSource implements WorkloadMetricsSource, ScalingMetricsSource {
    private final QueueManager queueManager;

    public AsyncQueueWorkloadMetricsSource(QueueManager queueManager) { this.queueManager = queueManager; }
    public int queueDepth(String functionName) { FunctionQueueState s = queueManager.get(functionName); return s == null ? 0 : s.queued(); }
    public int inFlight(String functionName) { FunctionQueueState s = queueManager.get(functionName); return s == null ? 0 : s.inFlight(); }
    public int effectiveConcurrency(String functionName) { FunctionQueueState s = queueManager.get(functionName); return s == null ? 0 : s.effectiveConcurrency(); }
    public int dispatchableBacklog(String functionName) { FunctionQueueState s = queueManager.get(functionName); return s == null ? 0 : s.dispatchableBacklog(); }
    @Override public void setEffectiveConcurrency(String functionName, int value) { queueManager.setEffectiveConcurrency(functionName, value); }
    @Override public void updateConcurrencyController(String functionName, ConcurrencyControlMode mode, int targetInFlightPerPod) {
        queueManager.updateConcurrencyController(functionName, mode, targetInFlightPerPod);
    }
}
