package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import it.unimib.datai.nanofaas.common.model.ConcurrencyControlConfig;
import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingMetric;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;

import java.util.List;

final class ConcurrencySpecs {
    private ConcurrencySpecs() {
    }

    static FunctionSpec spec(String name, int configuredConcurrency, ConcurrencyControlConfig control) {
        ScalingConfig scaling = new ScalingConfig(
                ScalingStrategy.INTERNAL,
                1,
                4,
                List.of(new ScalingMetric("queue_depth", "5", null)),
                control
        );
        return new FunctionSpec(
                name,
                "img:latest",
                null,
                null,
                null,
                30_000,
                configuredConcurrency,
                100,
                3,
                null,
                ExecutionMode.DEPLOYMENT,
                null,
                null,
                scaling
        );
    }

    static ConcurrencyControlConfig staticControl(int targetInFlightPerPod) {
        return new ConcurrencyControlConfig(
                ConcurrencyControlMode.STATIC_PER_POD,
                targetInFlightPerPod,
                null, null, null, null, null, null
        );
    }

    static ConcurrencyControlConfig adaptiveControl(int target) {
        return new ConcurrencyControlConfig(
                ConcurrencyControlMode.ADAPTIVE_PER_POD,
                target,
                1,
                6,
                1_000L,
                2_000L,
                0.5,
                0.15
        );
    }
}
