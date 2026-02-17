package it.unimib.datai.nanofaas.controlplane.scaling;

import it.unimib.datai.nanofaas.common.model.ConcurrencyControlConfig;
import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import org.springframework.stereotype.Component;

@Component
public class StaticPerPodConcurrencyController implements ConcurrencyController {

    @Override
    public int computeEffectiveConcurrency(FunctionSpec spec, int readyReplicas) {
        int configured = Math.max(1, spec.concurrency());
        ScalingConfig scaling = spec.scalingConfig();
        if (scaling == null) {
            return configured;
        }
        ConcurrencyControlConfig control = scaling.concurrencyControl();
        if (control == null || control.mode() != ConcurrencyControlMode.STATIC_PER_POD) {
            return configured;
        }
        int replicas = Math.max(1, readyReplicas);
        int targetPerPod = control.targetInFlightPerPod() == null ? 1 : Math.max(1, control.targetInFlightPerPod());
        long desired = (long) replicas * targetPerPod;
        if (desired > configured) {
            return configured;
        }
        return Math.max(1, (int) desired);
    }
}
