package it.unimib.datai.nanofaas.workloadmetrics;

public interface WorkloadCapacityController {
    void setEffectiveConcurrency(String functionName, int concurrency);
}
