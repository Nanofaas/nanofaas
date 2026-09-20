package it.unimib.datai.nanofaas.controlplane.capacity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvocationCapacityPropertiesTest {

    @Test
    void calibratedDefaultsAreFiniteAndInternallyConsistent() {
        InvocationCapacityProperties properties = new InvocationCapacityProperties();

        properties.validate();

        assertThat(properties.getIngressBodyBytes()).isEqualTo(1L << 20);
        assertThat(properties.getExecutionsGlobal()).isEqualTo(4_096);
        assertThat(properties.getExecutionsPerFunction()).isEqualTo(512);
        assertThat(properties.getCanonicalInputBytesGlobal()).isEqualTo(128L << 20);
        assertThat(properties.getCanonicalInputBytesPerFunction()).isEqualTo(32L << 20);
        assertThat(properties.getPhysicalInputCopyBytesGlobal()).isEqualTo(64L << 20);
        assertThat(properties.getPhysicalInputCopyBytesPerFunction()).isEqualTo(16L << 20);
        assertThat(properties.getWaitersGlobal()).isEqualTo(8_192);
        assertThat(properties.getWaitersPerFunction()).isEqualTo(1_024);
    }

    @Test
    void invalidAndInconsistentValuesFailValidation() {
        InvocationCapacityProperties properties = new InvocationCapacityProperties();
        properties.setExecutionsGlobal(4);
        properties.setExecutionsPerFunction(5);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("executions-per-function")
                .hasMessageContaining("executions-global");

        properties = new InvocationCapacityProperties();
        properties.setIngressBodyBytes((long) Integer.MAX_VALUE + 1);
        InvocationCapacityProperties overflowing = properties;
        assertThatThrownBy(overflowing::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ingress-body-bytes");

        properties = new InvocationCapacityProperties();
        properties.setWaitersGlobal(0);
        InvocationCapacityProperties zero = properties;
        assertThatThrownBy(zero::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }
}
