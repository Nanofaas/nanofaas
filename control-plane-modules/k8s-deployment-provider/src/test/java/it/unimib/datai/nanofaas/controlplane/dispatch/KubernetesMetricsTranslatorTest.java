package it.unimib.datai.nanofaas.controlplane.dispatch;

import io.fabric8.kubernetes.api.model.autoscaling.v2.MetricSpec;
import it.unimib.datai.nanofaas.common.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KubernetesMetricsTranslatorTest {

    private KubernetesMetricsTranslator translator;

    @BeforeEach
    void setUp() {
        translator = new KubernetesMetricsTranslator();
    }

    private FunctionSpec spec() {
        return new FunctionSpec(
                "echo", "nanofaas/function-runtime:0.5.0",
                List.of(), Map.of(),
                null, 30000, 4, 100, 3,
                null, ExecutionMode.DEPLOYMENT, RuntimeMode.HTTP, null, null
        );
    }

    @Test
    void toMetricSpecs_cpuTranslation() {
        ScalingConfig config = new ScalingConfig(ScalingStrategy.HPA, 1, 10,
                List.of(new ScalingMetric("cpu", "80", null)));

        List<MetricSpec> specs = translator.toMetricSpecs(config, spec());

        assertEquals(1, specs.size());
        assertEquals("Resource", specs.get(0).getType());
        assertEquals("cpu", specs.get(0).getResource().getName());
        assertEquals("Utilization", specs.get(0).getResource().getTarget().getType());
        assertEquals(80, specs.get(0).getResource().getTarget().getAverageUtilization());
    }

    @Test
    void toMetricSpecs_memoryTranslation() {
        ScalingConfig config = new ScalingConfig(ScalingStrategy.HPA, 1, 10,
                List.of(new ScalingMetric("memory", "70", null)));

        List<MetricSpec> specs = translator.toMetricSpecs(config, spec());

        assertEquals(1, specs.size());
        assertEquals("Resource", specs.get(0).getType());
        assertEquals("memory", specs.get(0).getResource().getName());
        assertEquals(70, specs.get(0).getResource().getTarget().getAverageUtilization());
    }

    @Test
    void toMetricSpecs_externalQueueDepthTranslation() {
        ScalingConfig config = new ScalingConfig(ScalingStrategy.HPA, 1, 10,
                List.of(new ScalingMetric("queue_depth", "5", null)));

        List<MetricSpec> specs = translator.toMetricSpecs(config, spec());

        assertEquals(1, specs.size());
        assertEquals("External", specs.get(0).getType());
        assertEquals("nanofaas_queue_depth", specs.get(0).getExternal().getMetric().getName());
        assertEquals("echo", specs.get(0).getExternal().getMetric().getSelector().getMatchLabels().get("function"));
        assertEquals("Value", specs.get(0).getExternal().getTarget().getType());
        assertEquals("5", specs.get(0).getExternal().getTarget().getValue().toString());
    }

    @Test
    void toMetricSpecs_externalRpsTranslation() {
        ScalingConfig config = new ScalingConfig(ScalingStrategy.HPA, 1, 10,
                List.of(new ScalingMetric("rps", "100", null)));

        List<MetricSpec> specs = translator.toMetricSpecs(config, spec());

        assertEquals(1, specs.size());
        assertEquals("External", specs.get(0).getType());
        assertEquals("nanofaas_rps", specs.get(0).getExternal().getMetric().getName());
    }

    @Test
    void toMetricSpecs_prometheusTranslation() {
        ScalingConfig config = new ScalingConfig(ScalingStrategy.HPA, 1, 10,
                List.of(new ScalingMetric("prometheus", "10", null)));

        List<MetricSpec> specs = translator.toMetricSpecs(config, spec());

        assertEquals(1, specs.size());
        assertEquals("External", specs.get(0).getType());
        assertEquals("nanofaas_custom_echo", specs.get(0).getExternal().getMetric().getName());
    }

    @Test
    void toMetricSpecs_unknownTypeIsSkipped() {
        ScalingConfig config = new ScalingConfig(ScalingStrategy.HPA, 1, 10,
                List.of(new ScalingMetric("unknown_type", "5", null)));

        List<MetricSpec> specs = translator.toMetricSpecs(config, spec());

        assertTrue(specs.isEmpty());
    }

    @Test
    void toMetricSpecs_nullMetricsReturnsEmpty() {
        ScalingConfig config = new ScalingConfig(ScalingStrategy.HPA, 1, 10, null);

        List<MetricSpec> specs = translator.toMetricSpecs(config, spec());

        assertTrue(specs.isEmpty());
    }

    @Test
    void toMetricSpecs_multipleMetrics() {
        ScalingConfig config = new ScalingConfig(ScalingStrategy.HPA, 1, 10,
                List.of(
                        new ScalingMetric("cpu", "80", null),
                        new ScalingMetric("queue_depth", "5", null)
                ));

        List<MetricSpec> specs = translator.toMetricSpecs(config, spec());

        assertEquals(2, specs.size());
        assertEquals("Resource", specs.get(0).getType());
        assertEquals("External", specs.get(1).getType());
    }

    @Test
    void toMetricSpecs_invalidTargetDefaultsTo50() {
        ScalingConfig config = new ScalingConfig(ScalingStrategy.HPA, 1, 10,
                List.of(new ScalingMetric("cpu", "not-a-number", null)));

        List<MetricSpec> specs = translator.toMetricSpecs(config, spec());

        assertEquals(1, specs.size());
        assertEquals(50, specs.get(0).getResource().getTarget().getAverageUtilization());
    }
}
