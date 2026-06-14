package it.unimib.datai.nanofaas.modules.autoscaler;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingMetric;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TargetLoadMetricsBranchTest {

    @Test
    void update_ignoresInvalidInputsAndUnknownMetricType() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TargetLoadMetrics metrics = new TargetLoadMetrics(registry);

        metrics.update(null);
        metrics.update(new FunctionSpec(
                "fn", "img", null, Map.of(), null, 1000, 1, 10, 0, null,
                ExecutionMode.DEPLOYMENT, RuntimeMode.HTTP, null, null
        ));

        FunctionSpec invalidMetrics = new FunctionSpec(
                "fn",
                "img",
                List.of(),
                null,
                null,
                1000,
                1,
                10,
                0,
                null,
                ExecutionMode.DEPLOYMENT,
                RuntimeMode.HTTP,
                null,
                new ScalingConfig(
                        ScalingStrategy.INTERNAL,
                        1,
                        10,
                        new ArrayList<>(List.of(
                                new ScalingMetric(null, "7", null),
                                new ScalingMetric("unknown_metric", "9", null)
                        ))
                )
        );
        invalidMetrics.scalingConfig().metrics().add(0, null);
        metrics.update(invalidMetrics);

        assertThat(registry.find("gateway_service_target_load").gauges()).isEmpty();
    }

    @Test
    void update_invalidTargetFallsBackToZero_andSupportsCpuType() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TargetLoadMetrics metrics = new TargetLoadMetrics(registry);

        FunctionSpec spec = new FunctionSpec(
                "echo",
                "img",
                List.of(),
                null,
                null,
                1000,
                1,
                10,
                0,
                null,
                ExecutionMode.DEPLOYMENT,
                RuntimeMode.HTTP,
                null,
                new ScalingConfig(
                        ScalingStrategy.INTERNAL,
                        1,
                        10,
                        List.of(
                                new ScalingMetric("cpu", "abc", null)
                        )
                )
        );

        metrics.update(spec);

        Gauge cpu = registry.find("gateway_service_target_load")
                .tags("function", "echo", "scaling_type", "cpu")
                .gauge();
        assertThat(cpu).isNotNull();
        assertThat(cpu.value()).isEqualTo(0.0);
    }

    @Test
    void remove_nullFunctionName_isNoOp() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TargetLoadMetrics metrics = new TargetLoadMetrics(registry);

        metrics.remove(null);
    }
}
