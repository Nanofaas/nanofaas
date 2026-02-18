package it.unimib.datai.nanofaas.modules.autoscaler;

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

class StaticPerPodConcurrencyControllerTest {

    private FunctionSpec spec(int concurrency, int targetPerPod) {
        ConcurrencyControlConfig cc = new ConcurrencyControlConfig(
                ConcurrencyControlMode.STATIC_PER_POD,
                targetPerPod,
                1,
                16,
                30_000L,
                60_000L,
                0.85,
                0.35
        );
        ScalingConfig scaling = new ScalingConfig(
                ScalingStrategy.INTERNAL,
                1,
                10,
                List.of(new ScalingMetric("queue_depth", "5", null)),
                cc
        );
        return new FunctionSpec(
                "fn",
                "img:latest",
                List.of(),
                Map.of(),
                null,
                30_000,
                concurrency,
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
    void compute_scalesWithReplicas() {
        StaticPerPodConcurrencyController controller = new StaticPerPodConcurrencyController();
        int effective = controller.computeEffectiveConcurrency(spec(10, 2), 3);
        assertThat(effective).isEqualTo(6);
    }

    @Test
    void compute_neverExceedsConfiguredConcurrency() {
        StaticPerPodConcurrencyController controller = new StaticPerPodConcurrencyController();
        int effective = controller.computeEffectiveConcurrency(spec(8, 3), 5);
        assertThat(effective).isEqualTo(8);
    }

    @Test
    void compute_enforcesMinimumOne() {
        StaticPerPodConcurrencyController controller = new StaticPerPodConcurrencyController();
        int effective = controller.computeEffectiveConcurrency(spec(6, 2), 0);
        assertThat(effective).isGreaterThanOrEqualTo(1);
        assertThat(effective).isLessThanOrEqualTo(6);
    }
}
