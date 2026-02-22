package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;

enum NoOpScalingMetricsSource implements ScalingMetricsSource {
    INSTANCE;

    @Override
    public int queueDepth(String functionName) {
        return 0;
    }

    @Override
    public int inFlight(String functionName) {
        return 0;
    }

    @Override
    public void setEffectiveConcurrency(String functionName, int value) {
        // no-op
    }

    @Override
    public void updateConcurrencyController(
            String functionName,
            ConcurrencyControlMode mode,
            int targetInFlightPerPod
    ) {
        // no-op
    }
}
