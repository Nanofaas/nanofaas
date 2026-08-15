package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import it.unimib.datai.nanofaas.common.model.ConcurrencyControlConfig;
import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.controlplane.service.ScalingMetricsSource;

/**
 * Decides the effective concurrency of one function and publishes it through the
 * {@link ScalingMetricsSource}, which is where the enforcing queue picks it up.
 */
public final class ConcurrencyControlCoordinator {
    private final ScalingMetricsSource metricsSource;
    private final ConcurrencyControlProperties properties;
    private final StaticPerPodConcurrencyController staticConcurrencyController;
    private final AdaptivePerPodConcurrencyController adaptiveConcurrencyController;

    public ConcurrencyControlCoordinator(ScalingMetricsSource metricsSource,
                                         ConcurrencyControlProperties properties,
                                         StaticPerPodConcurrencyController staticConcurrencyController,
                                         AdaptivePerPodConcurrencyController adaptiveConcurrencyController) {
        this.metricsSource = metricsSource;
        this.properties = properties;
        this.staticConcurrencyController = staticConcurrencyController;
        this.adaptiveConcurrencyController = adaptiveConcurrencyController;
    }

    public void apply(FunctionSpec spec,
                      int readyReplicas,
                      long latencyCount,
                      double latencyTotalMs,
                      long nowEpochMs) {
        String functionName = spec.name();
        int effectiveConcurrency = Math.max(1, spec.concurrency());
        ConcurrencyControlMode controllerMode = ConcurrencyControlMode.FIXED;
        int targetInFlightPerPod = 0;

        ScalingConfig scaling = spec.scalingConfig();
        ConcurrencyControlConfig control = scaling == null ? null : scaling.concurrencyControl();
        if (control != null && control.mode() == ConcurrencyControlMode.STATIC_PER_POD) {
            controllerMode = ConcurrencyControlMode.STATIC_PER_POD;
            targetInFlightPerPod = control.targetInFlightPerPod() == null ? 0 : control.targetInFlightPerPod();
            effectiveConcurrency = staticConcurrencyController.computeEffectiveConcurrency(spec, readyReplicas);
        } else if (control != null && control.mode() == ConcurrencyControlMode.ADAPTIVE_PER_POD) {
            controllerMode = ConcurrencyControlMode.ADAPTIVE_PER_POD;
            effectiveConcurrency = adaptiveConcurrencyController.computeEffectiveConcurrency(
                    spec,
                    readyReplicas,
                    latencyCount,
                    latencyTotalMs,
                    nowEpochMs
            );
            targetInFlightPerPod = adaptiveConcurrencyController.currentTargetInFlightPerPod(
                    functionName,
                    control.targetInFlightPerPod() == null
                            ? properties.defaultTargetInFlightPerPodOrDefault()
                            : control.targetInFlightPerPod()
            );
        }

        metricsSource.setEffectiveConcurrency(functionName, effectiveConcurrency);
        metricsSource.updateConcurrencyController(functionName, controllerMode, targetInFlightPerPod);
    }

    public void removeFunctionState(String functionName) {
        adaptiveConcurrencyController.removeFunctionState(functionName);
    }
}
