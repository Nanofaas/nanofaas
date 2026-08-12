package it.unimib.datai.nanofaas.modules.k8s.dispatch;

import io.fabric8.kubernetes.api.model.autoscaling.v2.MetricSpec;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingMetric;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every external metric the translator can emit must be servable.
 *
 * A MetricSpec that names a metric no adapter rule produces is accepted by the
 * API server and then silently never satisfied: the HPA reports the metric as
 * unavailable and leaves the replica count alone. The translator's own unit
 * test cannot see this, because it stops at the MetricSpec.
 *
 * The metric names are asked of the translator itself (not re-derived here)
 * so a change to the translator's naming rule is caught rather than mirrored.
 */
class AdapterRuleCoverageTest {

    /** Types a user may register; mirrors FunctionSpecResolver.SUPPORTED_INTERNAL_SCALING_METRICS. */
    private static final Set<String> SUPPORTED_TYPES = Set.of("queue_depth", "in_flight", "rps");

    private final KubernetesMetricsTranslator translator = new KubernetesMetricsTranslator();

    @Test
    void everySupportedScalingMetricHasAnAdapterRule() throws Exception {
        Path values = repositoryRoot().resolve("deploy/helm/nanofaas/values.yaml");
        assertTrue(Files.isRegularFile(values), "chart values not found at " + values);
        String chart = Files.readString(values);

        Set<String> missing = new TreeSet<>();
        for (String type : SUPPORTED_TYPES) {
            String metricName = externalMetricName(type);
            if (!chart.contains("as: " + metricName)) {
                missing.add(metricName);
            }
        }

        assertEquals(
                Set.of(),
                missing,
                "these metrics are registrable and translated but no adapter rule serves them: " + missing);
    }

    private String externalMetricName(String type) {
        ScalingConfig config = new ScalingConfig(ScalingStrategy.HPA, 1, 10,
                List.of(new ScalingMetric(type, "5", null)));
        List<MetricSpec> specs = translator.toMetricSpecs(config, functionSpec());
        assertEquals(1, specs.size(), "translator produced no MetricSpec for type " + type);
        return specs.get(0).getExternal().getMetric().getName();
    }

    private FunctionSpec functionSpec() {
        return new FunctionSpec(
                "echo", "nanofaas/java-warm-echo:0.5.0",
                List.of(), Map.of(),
                null, 30000, 4, 100, 3,
                null, ExecutionMode.DEPLOYMENT, RuntimeMode.HTTP, null, null
        );
    }

    private static Path repositoryRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.isDirectory(path.resolve("deploy/helm/nanofaas"))) {
            path = path.getParent();
        }
        if (path == null) {
            throw new IllegalStateException("repository root not found from " + Path.of("").toAbsolutePath());
        }
        return path;
    }
}
