package it.unimib.datai.nanofaas.workloadmetrics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class FunctionCapacityRegistryTest {
    @Test
    void capacityIsBoundedAndReleaseReturnsHoldDuration() {
        AtomicLong clock = new AtomicLong(10);
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry(clock::get);
        registry.register("echo", 2);

        assertThat(registry.configuredConcurrency("echo")).isEqualTo(2);
        assertThat(registry.inFlight("echo")).isZero();
        assertThat(registry.tryAcquireSlot("echo")).isTrue();
        assertThat(registry.tryAcquireSlot("echo")).isTrue();
        assertThat(registry.tryAcquireSlot("echo")).isFalse();

        clock.set(25);
        assertThat(registry.releaseSlotAndGetHoldNanos("echo")).isEqualTo(15);
        assertThat(registry.inFlight("echo")).isEqualTo(1);
        registry.setEffectiveConcurrency("echo", 1);
        assertThat(registry.tryAcquireSlot("echo")).isFalse();
        assertThat(registry.releaseSlotAndGetHoldNanos("echo")).isEqualTo(15);
        assertThat(registry.releaseSlotAndGetHoldNanos("echo")).isEqualTo(-1);
    }

    @Test
    void configuredConcurrencyAndRemovalAreSafe() {
        FunctionCapacityRegistry registry = new FunctionCapacityRegistry();
        registry.register("echo", 6);
        registry.setEffectiveConcurrency("echo", 4);
        registry.register("echo", 3);
        assertThat(registry.configuredConcurrency("echo")).isEqualTo(3);
        assertThat(registry.effectiveConcurrency("echo")).isEqualTo(3);

        registry.remove("echo");
        assertThat(registry.inFlight("echo")).isZero();
        assertThat(registry.tryAcquireSlot("echo")).isFalse();
    }
}
