package it.unimib.datai.nanofaas.modules.autoscaler;

import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;

public final class ConcurrencyControlCoordinator {
    private final ScalingMetricsReader metricsReader;
    private final ScalingProperties properties;
    private final StaticPerPodConcurrencyController staticConcurrencyController;
    private final AdaptivePerPodConcurrencyController adaptiveConcurrencyController;

    public ConcurrencyControlCoordinator(ScalingMetricsReader metricsReader,
                                         ScalingProperties properties,
                                         StaticPerPodConcurrencyController staticConcurrencyController,
                                         AdaptivePerPodConcurrencyController adaptiveConcurrencyController) {
        this.metricsReader = metricsReader;
        this.properties = properties;
        this.staticConcurrencyController = staticConcurrencyController;
        this.adaptiveConcurrencyController = adaptiveConcurrencyController;
    }

    public void apply(FunctionSpec spec,
                      ScalingConfig scaling,
                      double loadRatio,
                      int effectiveReplicas,
                      boolean downscaleSignal,
                      int currentReplicas) {
        String functionName = spec.name();
        int configuredConcurrency = spec.concurrency();
        int effectiveConcurrency = configuredConcurrency;
        ConcurrencyControlMode controllerMode = ConcurrencyControlMode.FIXED;
        int targetInFlightPerPod = 0;

        if (scaling.concurrencyControl() != null) {
            ConcurrencyControlMode mode = scaling.concurrencyControl().mode();
            if (mode == ConcurrencyControlMode.STATIC_PER_POD) {
                controllerMode = ConcurrencyControlMode.STATIC_PER_POD;
                targetInFlightPerPod = scaling.concurrencyControl().targetInFlightPerPod() == null
                        ? 0
                        : scaling.concurrencyControl().targetInFlightPerPod();
                effectiveConcurrency = staticConcurrencyController.computeEffectiveConcurrency(spec, effectiveReplicas);
            } else if (mode == ConcurrencyControlMode.ADAPTIVE_PER_POD) {
                controllerMode = ConcurrencyControlMode.ADAPTIVE_PER_POD;
                boolean atMaxReplicas = currentReplicas >= scaling.maxReplicas();
                effectiveConcurrency = adaptiveConcurrencyController.computeEffectiveConcurrency(
                        spec,
                        effectiveReplicas,
                        loadRatio,
                        downscaleSignal,
                        atMaxReplicas,
                        InstantSource.nowEpochMs()
                );
                targetInFlightPerPod = adaptiveConcurrencyController.currentTargetInFlightPerPod(
                        functionName,
                        scaling.concurrencyControl().targetInFlightPerPod() == null
                                ? properties.defaultTargetInFlightPerPodOrDefault()
                                : scaling.concurrencyControl().targetInFlightPerPod()
                );
            }
        }

        metricsReader.setEffectiveConcurrency(functionName, effectiveConcurrency);
        metricsReader.updateConcurrencyControllerState(functionName, controllerMode, targetInFlightPerPod);
    }

    public void removeFunctionState(String functionName) {
        adaptiveConcurrencyController.removeFunctionState(functionName);
    }

    private static final class InstantSource {
        private static long nowEpochMs() {
            return System.currentTimeMillis();
        }
    }
}
