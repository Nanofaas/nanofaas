package it.unimib.datai.nanofaas.sdk.lite.metrics;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.core.metrics.Histogram;
import io.prometheus.metrics.model.registry.PrometheusRegistry;

public final class RuntimeMetrics {
    private final PrometheusRegistry registry;
    private final Counter invocationsTotal;
    private final Counter errorsTotal;
    private final Counter coldStarts;
    private final Histogram invocationDuration;
    private final Gauge inFlight;

    public RuntimeMetrics(String functionName) {
        this.registry = new PrometheusRegistry();

        this.invocationsTotal = Counter.builder()
                .name("nanofaas_invocations_total")
                .help("Total number of function invocations")
                .labelNames("function")
                .register(registry);

        this.errorsTotal = Counter.builder()
                .name("nanofaas_errors_total")
                .help("Total number of failed invocations")
                .labelNames("function")
                .register(registry);

        this.coldStarts = Counter.builder()
                .name("nanofaas_cold_starts_total")
                .help("Total number of cold starts")
                .labelNames("function")
                .register(registry);

        this.invocationDuration = Histogram.builder()
                .name("nanofaas_invocation_duration_seconds")
                .help("Invocation duration in seconds")
                .labelNames("function")
                .classicUpperBounds(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0)
                .register(registry);

        this.inFlight = Gauge.builder()
                .name("nanofaas_in_flight")
                .help("Number of invocations currently in progress")
                .labelNames("function")
                .register(registry);

        // Initialize labels so they appear even before the first invocation
        invocationsTotal.labelValues(functionName);
        errorsTotal.labelValues(functionName);
        coldStarts.labelValues(functionName);
    }

    public PrometheusRegistry getRegistry() {
        return registry;
    }

    public void recordInvocation(String function) {
        invocationsTotal.labelValues(function).inc();
    }

    public void recordError(String function) {
        errorsTotal.labelValues(function).inc();
    }

    public void recordColdStart(String function) {
        coldStarts.labelValues(function).inc();
    }

    public void observeDuration(String function, double seconds) {
        invocationDuration.labelValues(function).observe(seconds);
    }

    public void incInFlight(String function) {
        inFlight.labelValues(function).inc();
    }

    public void decInFlight(String function) {
        inFlight.labelValues(function).dec();
    }
}
