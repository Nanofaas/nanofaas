package it.unimib.datai.nanofaas.controlplane.scaling;

import it.unimib.datai.nanofaas.common.model.ConcurrencyControlConfig;
import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingMetric;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AdaptivePerPodConcurrencyControllerTest {

    private FunctionSpec spec(String name, int configuredConcurrency, int targetPerPod) {
        ConcurrencyControlConfig cc = new ConcurrencyControlConfig(
                ConcurrencyControlMode.ADAPTIVE_PER_POD,
                targetPerPod,
                1,
                6,
                1000L,
                2000L,
                0.8,
                0.3
        );
        ScalingConfig scaling = new ScalingConfig(
                ScalingStrategy.INTERNAL,
                1,
                4,
                List.of(new ScalingMetric("queue_depth", "5", null)),
                cc
        );
        return new FunctionSpec(
                name,
                "img:latest",
                List.of(),
                Map.of(),
                null,
                30_000,
                configuredConcurrency,
                100,
                3,
                "http://fn.default.svc.cluster.local:8080/invoke",
                ExecutionMode.DEPLOYMENT,
                RuntimeMode.HTTP,
                null,
                scaling
        );
    }

    @Test
    void highLoadAtMaxReplicas_decreasesTargetPerPod() {
        AdaptivePerPodConcurrencyController controller = new AdaptivePerPodConcurrencyController();
        FunctionSpec spec = spec("fn-a", 20, 4);

        int effective = controller.computeEffectiveConcurrency(
                spec,
                4,
                0.95,
                false,
                true,
                3_000L
        );

        assertThat(controller.currentTargetInFlightPerPod("fn-a", 4)).isEqualTo(3);
        assertThat(effective).isEqualTo(12);
    }

    @Test
    void lowLoad_afterRecentDownscale_doesNotIncreaseTarget() {
        AdaptivePerPodConcurrencyController controller = new AdaptivePerPodConcurrencyController();
        FunctionSpec spec = spec("fn-b", 20, 2);

        controller.computeEffectiveConcurrency(spec, 2, 0.2, true, false, 1_000L);
        int effective = controller.computeEffectiveConcurrency(spec, 2, 0.2, false, false, 2_000L);

        assertThat(controller.currentTargetInFlightPerPod("fn-b", 2)).isEqualTo(2);
        assertThat(effective).isEqualTo(4);
    }

    @Test
    void lowLoad_afterCooldown_increasesTarget() {
        AdaptivePerPodConcurrencyController controller = new AdaptivePerPodConcurrencyController();
        FunctionSpec spec = spec("fn-c", 20, 2);

        controller.computeEffectiveConcurrency(spec, 2, 0.2, true, false, 1_000L);
        controller.computeEffectiveConcurrency(spec, 2, 0.2, false, false, 2_000L);
        int effective = controller.computeEffectiveConcurrency(spec, 2, 0.2, false, false, 3_100L);

        assertThat(controller.currentTargetInFlightPerPod("fn-c", 2)).isEqualTo(3);
        assertThat(effective).isEqualTo(6);
    }

    @Test
    void state_isFunctionSpecific() {
        AdaptivePerPodConcurrencyController controller = new AdaptivePerPodConcurrencyController();
        FunctionSpec a = spec("fn-a", 20, 4);
        FunctionSpec b = spec("fn-b", 20, 4);

        controller.computeEffectiveConcurrency(a, 4, 0.95, false, true, 3_000L);
        controller.computeEffectiveConcurrency(b, 4, 0.2, false, false, 3_000L);

        assertThat(controller.currentTargetInFlightPerPod("fn-a", 4)).isEqualTo(3);
        assertThat(controller.currentTargetInFlightPerPod("fn-b", 4)).isEqualTo(5);
    }
}
