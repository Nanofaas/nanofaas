package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsSource;

public class AsyncQueueWorkloadMetricsSource implements WorkloadMetricsSource {
    private final QueueManager queueManager;

    public AsyncQueueWorkloadMetricsSource(QueueManager queueManager) { this.queueManager = queueManager; }
    public int queueDepth(String functionName) { FunctionQueueState s = queueManager.get(functionName); return s == null ? 0 : s.queued(); }
    public int inFlight(String functionName) { FunctionQueueState s = queueManager.get(functionName); return s == null ? 0 : s.inFlight(); }
    public int effectiveConcurrency(String functionName) { FunctionQueueState s = queueManager.get(functionName); return s == null ? 0 : s.effectiveConcurrency(); }
    public int dispatchableBacklog(String functionName) { FunctionQueueState s = queueManager.get(functionName); return s == null ? 0 : s.dispatchableBacklog(); }
}
