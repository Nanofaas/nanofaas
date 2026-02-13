package it.unimib.datai.nanofaas.controlplane.e2e;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class E2eApiSupportTest {

    @Test
    void poolFunctionSpec_containsExpectedDefaults() {
        Map<String, Object> spec = E2eApiSupport.poolFunctionSpec(
                "echo",
                "nanofaas/function-runtime:test",
                "http://function-runtime:8080/invoke",
                5000,
                2,
                20,
                3);

        assertThat(spec).containsEntry("name", "echo");
        assertThat(spec).containsEntry("image", "nanofaas/function-runtime:test");
        assertThat(spec).containsEntry("endpointUrl", "http://function-runtime:8080/invoke");
        assertThat(spec).containsEntry("executionMode", "POOL");
        assertThat(spec).containsEntry("timeoutMs", 5000);
        assertThat(spec).containsEntry("concurrency", 2);
        assertThat(spec).containsEntry("queueSize", 20);
        assertThat(spec).containsEntry("maxRetries", 3);
    }

    @Test
    void metricSum_filtersByMetricAndLabels() {
        String metrics = """
                # HELP function_cold_start_total Total cold starts
                # TYPE function_cold_start_total counter
                function_cold_start_total{function="echo"} 1.0
                function_cold_start_total{function="other"} 2.0
                function_warm_start_total{function="echo"} 3.0
                function_cold_start_total{function="echo",result="ok"} 4.0
                """;

        double sum = E2eApiSupport.metricSum(metrics, "function_cold_start_total", Map.of("function", "echo"));

        assertThat(sum).isEqualTo(5.0);
    }

    @Test
    void assertMetricSumAtLeast_failsWhenMetricMissing() {
        String metrics = """
                # TYPE function_warm_start_total counter
                function_warm_start_total{function="echo"} 1.0
                """;

        assertThatThrownBy(() -> E2eApiSupport.assertMetricSumAtLeast(
                metrics,
                "function_cold_start_total",
                Map.of("function", "echo"),
                1.0))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("expected metric function_cold_start_total");
    }

    @Test
    void assertMetricSumAtLeast_failsWhenBelowThreshold() {
        String metrics = """
                function_cold_start_total{function="echo"} 0.5
                """;

        assertThatThrownBy(() -> E2eApiSupport.assertMetricSumAtLeast(
                metrics,
                "function_cold_start_total",
                Map.of("function", "echo"),
                1.0))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("sum >= 1.0");
    }
}
