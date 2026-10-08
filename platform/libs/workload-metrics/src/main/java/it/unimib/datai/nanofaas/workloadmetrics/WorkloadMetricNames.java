package it.unimib.datai.nanofaas.workloadmetrics;

public final class WorkloadMetricNames {
    public static final String QUEUE_DEPTH = "function_queue_depth";
    public static final String IN_FLIGHT = "function_inFlight";
    public static final String EFFECTIVE_CONCURRENCY = "function_effective_concurrency";
    public static final String DISPATCHABLE_BACKLOG = "function_dispatchable_backlog";

    private WorkloadMetricNames() {}
}
