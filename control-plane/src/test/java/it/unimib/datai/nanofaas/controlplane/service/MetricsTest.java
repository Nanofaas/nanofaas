package it.unimib.datai.nanofaas.controlplane.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsTest {
    private SimpleMeterRegistry registry;
    private Metrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new Metrics(registry);
    }

    @Test
    void coldStart_incrementsCounter() {
        metrics.coldStart("echo");
        metrics.coldStart("echo");

        Counter counter = registry.find("function_cold_start_total")
                .tag("function", "echo").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(2.0);
    }

    @Test
    void warmStart_incrementsCounter() {
        metrics.warmStart("echo");

        Counter counter = registry.find("function_warm_start_total")
                .tag("function", "echo").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void initDuration_registersTimer() {
        Timer timer = metrics.initDuration("echo");
        assertThat(timer).isNotNull();

        Timer found = registry.find("function_init_duration_ms")
                .tag("function", "echo").timer();
        assertThat(found).isSameAs(timer);
    }

    @Test
    void queueWait_registersTimer() {
        Timer timer = metrics.queueWait("echo");
        assertThat(timer).isNotNull();

        Timer found = registry.find("function_queue_wait_ms")
                .tag("function", "echo").timer();
        assertThat(found).isSameAs(timer);
    }

    @Test
    void e2eLatency_registersTimer() {
        Timer timer = metrics.e2eLatency("echo");
        assertThat(timer).isNotNull();

        Timer found = registry.find("function_e2e_latency_ms")
                .tag("function", "echo").timer();
        assertThat(found).isSameAs(timer);
    }
}
