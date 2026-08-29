package it.unimib.datai.nanofaas.workloadmetrics;

public interface WorkloadMetricsSource {
    int queueDepth(String functionName);
    int inFlight(String functionName);
    int effectiveConcurrency(String functionName);
    int dispatchableBacklog(String functionName);
}
