package it.unimib.datai.nanofaas.sdk.lite.metrics;

import io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeMetricsTest {

    @Test
    void countersIncrementCorrectly() throws IOException {
        RuntimeMetrics metrics = new RuntimeMetrics("my-fn");

        metrics.recordInvocation("my-fn");
        metrics.recordInvocation("my-fn");
        metrics.recordError("my-fn");
        metrics.recordColdStart("my-fn");
        metrics.observeDuration("my-fn", 0.05);
        metrics.incInFlight("my-fn");

        String output = scrape(metrics);

        assertTrue(output.contains("nanofaas_invocations_total"));
        assertTrue(output.contains("nanofaas_errors_total"));
        assertTrue(output.contains("nanofaas_cold_starts_total"));
        assertTrue(output.contains("nanofaas_invocation_duration_seconds"));
        assertTrue(output.contains("nanofaas_in_flight"));
    }

    @Test
    void registryIsDedicated() {
        RuntimeMetrics m1 = new RuntimeMetrics("fn-a");
        RuntimeMetrics m2 = new RuntimeMetrics("fn-b");
        assertNotSame(m1.getRegistry(), m2.getRegistry());
    }

    private String scrape(RuntimeMetrics metrics) throws IOException {
        PrometheusTextFormatWriter writer = new PrometheusTextFormatWriter(true);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writer.write(baos, metrics.getRegistry().scrape());
        return baos.toString();
    }
}
