package it.unimib.datai.nanofaas.modules.autoscaler;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ScalingDecisionMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ScalingDecisionMetrics metrics = new ScalingDecisionMetrics(registry);

    private double gauge(String name) {
        return registry.get(name).tag("function", "echo").gauge().value();
    }

    @Test
    void aCappedDecisionIsDistinguishableFromAMetricThatStoppedRising() {
        // The case that could not be diagnosed before: the deployment sits at
        // maxReplicas, and the replica count alone cannot say whether the ratio
        // asked for exactly that or for far more.
        metrics.record("echo", new ScalingDecision(5, 18, 5, 5, 3.65, false));

        assertEquals(18.0, gauge("function_scaling_recommended_replicas"));
        assertEquals(5.0, gauge("function_scaling_desired_replicas"));
        assertEquals(1.0, gauge("function_scaling_limited"));
    }

    @Test
    void anUncappedDecisionReportsNoLimit() {
        metrics.record("echo", new ScalingDecision(1, 3, 3, 3, 3.0, false));

        assertEquals(3.0, gauge("function_scaling_recommended_replicas"));
        assertEquals(3.0, gauge("function_scaling_desired_replicas"));
        assertEquals(0.0, gauge("function_scaling_limited"));
    }

    @Test
    void theRatioSurvivesAsMilliUnitsRatherThanBeingRoundedAway() {
        // 3.65 is the ratio a 365 req/s load produces against a target of 100.
        // Rounded to an integer gauge it would read 4, which is a different number.
        metrics.record("echo", new ScalingDecision(1, 4, 4, 4, 3.65, false));

        assertEquals(3650.0, gauge("function_scaling_ratio_milli"));
    }

    @Test
    void recordingTwiceUpdatesInPlaceRatherThanRegisteringAgain() {
        metrics.record("echo", new ScalingDecision(1, 2, 2, 2, 2.0, false));
        metrics.record("echo", new ScalingDecision(2, 7, 5, 5, 3.5, false));

        assertEquals(1, registry.find("function_scaling_desired_replicas").gauges().size());
        assertEquals(5.0, gauge("function_scaling_desired_replicas"));
    }

    @Test
    void removingAFunctionTakesItsGaugesWithIt() {
        metrics.record("echo", new ScalingDecision(1, 2, 2, 2, 2.0, false));

        metrics.remove("echo");

        assertNull(registry.find("function_scaling_desired_replicas").gauge());
    }
}
