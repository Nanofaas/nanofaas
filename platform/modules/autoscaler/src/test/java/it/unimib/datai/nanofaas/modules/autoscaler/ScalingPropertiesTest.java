package it.unimib.datai.nanofaas.modules.autoscaler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScalingPropertiesTest {

    @Test
    void pollIntervalMsOrDefault_nullReturnsDefault() {
        ScalingProperties props = new ScalingProperties(null, null, null);
        assertEquals(5000, props.pollIntervalMsOrDefault());
    }

    @Test
    void pollIntervalMsOrDefault_zeroReturnsDefault() {
        ScalingProperties props = new ScalingProperties(0L, null, null);
        assertEquals(5000, props.pollIntervalMsOrDefault());
    }

    @Test
    void pollIntervalMsOrDefault_positiveReturnsValue() {
        ScalingProperties props = new ScalingProperties(10000L, null, null);
        assertEquals(10000, props.pollIntervalMsOrDefault());
    }

    @Test
    void defaultMinReplicasOrDefault_nullReturnsDefault() {
        ScalingProperties props = new ScalingProperties(null, null, null);
        assertEquals(1, props.defaultMinReplicasOrDefault());
    }

    @Test
    void defaultMinReplicasOrDefault_zeroReturnsDefault() {
        ScalingProperties props = new ScalingProperties(null, 0, null);
        assertEquals(1, props.defaultMinReplicasOrDefault());
    }

    @Test
    void defaultMinReplicasOrDefault_positiveReturnsValue() {
        ScalingProperties props = new ScalingProperties(null, 3, null);
        assertEquals(3, props.defaultMinReplicasOrDefault());
    }

    @Test
    void defaultMaxReplicasOrDefault_nullReturnsDefault() {
        ScalingProperties props = new ScalingProperties(null, null, null);
        assertEquals(10, props.defaultMaxReplicasOrDefault());
    }

    @Test
    void defaultMaxReplicasOrDefault_zeroReturnsDefault() {
        ScalingProperties props = new ScalingProperties(null, null, 0);
        assertEquals(10, props.defaultMaxReplicasOrDefault());
    }

    @Test
    void defaultMaxReplicasOrDefault_positiveReturnsValue() {
        ScalingProperties props = new ScalingProperties(null, null, 20);
        assertEquals(20, props.defaultMaxReplicasOrDefault());
    }

    @Test
    void defaultTargetInFlightPerPodOrDefault_returns2WhenUnset() {
        ScalingProperties props = new ScalingProperties(null, null, null);
        assertEquals(2, props.defaultTargetInFlightPerPodOrDefault());
    }

    @Test
    void concurrencyCooldownDefaults_areApplied() {
        ScalingProperties props = new ScalingProperties(null, null, null);
        assertEquals(30000L, props.concurrencyUpscaleCooldownMsOrDefault());
        assertEquals(60000L, props.concurrencyDownscaleCooldownMsOrDefault());
    }

    @Test
    void concurrencyLoadThresholdDefaults_areApplied() {
        ScalingProperties props = new ScalingProperties(null, null, null);
        assertEquals(0.85, props.concurrencyHighLoadThresholdOrDefault());
        assertEquals(0.35, props.concurrencyLowLoadThresholdOrDefault());
    }
}
