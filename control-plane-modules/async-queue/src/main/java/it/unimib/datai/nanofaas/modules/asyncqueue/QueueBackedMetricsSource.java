package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;
import it.unimib.datai.nanofaas.controlplane.service.ScalingMetricsSource;

public class QueueBackedMetricsSource implements ScalingMetricsSource {
    private final QueueManager queueManager;

    public QueueBackedMetricsSource(QueueManager queueManager) {
        this.queueManager = queueManager;
    }

    @Override
    public int queueDepth(String functionName) {
        FunctionQueueState state = queueManager.get(functionName);
        return state != null ? state.queued() : 0;
    }

    @Override
    public int inFlight(String functionName) {
        FunctionQueueState state = queueManager.get(functionName);
        return state != null ? state.inFlight() : 0;
    }

    @Override
    public void setEffectiveConcurrency(String functionName, int value) {
        queueManager.setEffectiveConcurrency(functionName, value);
    }

    @Override
    public void updateConcurrencyController(String functionName,
                                            ConcurrencyControlMode mode,
                                            int targetInFlightPerPod) {
        queueManager.updateConcurrencyController(functionName, mode, targetInFlightPerPod);
    }
}
