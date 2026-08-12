package it.unimib.datai.nanofaas.modules.k8s.dispatch;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
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
 */
class AdapterRuleCoverageTest {

    /** Types a user may register; mirrors FunctionSpecResolver.SUPPORTED_INTERNAL_SCALING_METRICS. */
    private static final Set<String> SUPPORTED_TYPES = Set.of("queue_depth", "in_flight", "rps");

    @Test
    void everySupportedScalingMetricHasAnAdapterRule() throws Exception {
        Path values = repositoryRoot().resolve("deploy/helm/nanofaas/values.yaml");
        assertTrue(Files.isRegularFile(values), "chart values not found at " + values);
        String chart = Files.readString(values);

        Set<String> missing = new TreeSet<>();
        for (String type : SUPPORTED_TYPES) {
            String metricName = "nanofaas_" + type;
            if (!chart.contains("as: " + metricName)) {
                missing.add(metricName);
            }
        }

        assertEquals(
                Set.of(),
                missing,
                "these metrics are registrable and translated but no adapter rule serves them: " + missing);
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
