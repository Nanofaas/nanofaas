package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;

public interface ScalingMetricsSource {

    int queueDepth(String functionName);

    int inFlight(String functionName);

    void setEffectiveConcurrency(String functionName, int value);

    void updateConcurrencyController(
            String functionName,
            ConcurrencyControlMode mode,
            int targetInFlightPerPod
    );

    static ScalingMetricsSource noOp() {
        return NoOpScalingMetricsSource.INSTANCE;
    }
}
