package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.workloadmetrics.WorkloadCapacityController;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsSource;

public interface RecordingWorkloadMetricsSource extends WorkloadMetricsSource, WorkloadCapacityController {
    void setEffectiveConcurrency(String functionName, int value);
    @Override default int effectiveConcurrency(String functionName) { return 0; }
    @Override default int dispatchableBacklog(String functionName) { return 0; }
}
