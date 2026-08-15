package it.unimib.datai.nanofaas.modules.autoscaler;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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


    /**
     * The record has no {@code @ConstructorBinding}: Boot infers constructor binding for a
     * single-constructor record. A regression here would be silent — the defaults would simply
     * apply and the configured values would be ignored.
     */
    @Test
    void propertiesBindFromConfiguration() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesOnly.class)
                .withPropertyValues(
                        "nanofaas.scaling.poll-interval-ms=250",
                        "nanofaas.scaling.default-min-replicas=2",
                        "nanofaas.scaling.default-max-replicas=20")
                .run(context -> {
                    ScalingProperties props = context.getBean(ScalingProperties.class);
                    assertEquals(250L, props.pollIntervalMsOrDefault());
                    assertEquals(2, props.defaultMinReplicasOrDefault());
                    assertEquals(20, props.defaultMaxReplicasOrDefault());
                });
    }

    @EnableConfigurationProperties(ScalingProperties.class)
    static class PropertiesOnly {
    }
}
