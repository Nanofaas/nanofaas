package it.unimib.datai.nanofaas.controlplane.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class MetricsProfileConfigurationTest {
    private final MetricsProfileConfiguration configuration = new MetricsProfileConfiguration();

    @Test
    void basicKeepsOperationalMetricsAndDropsAdvancedMetrics() {
        SimpleMeterRegistry registry = registryFor(MetricsProfileConfiguration.MetricsProfile.BASIC);

        Counter.builder("function_dispatch_total").tag("function", "echo").register(registry);
        Counter.builder("function_cold_start_total").tag("function", "echo").register(registry);
        Gauge.builder("function_concurrency_controller_mode", () -> 1).register(registry);

        assertThat(registry.find("function_dispatch_total").counter()).isNotNull();
        assertThat(registry.find("function_cold_start_total").counter()).isNull();
        assertThat(registry.find("function_concurrency_controller_mode").gauge()).isNull();
    }

    @Test
    void basicDropsTheDispatchPathInstrumentationAndAdvancedKeepsIt() {
        // These fire 15.74 times per dispatch, so leaving them outside this class -
        // where they were until 2026-08-23 - meant every production deployment on
        // the default profile paid for probes nobody was reading.
        String[] diagnostic = {
                "function_scheduler_wakeup_delay",
                "function_scheduler_dispatch_submit_duration",
                "function_queue_offer_duration",
                "function_queue_poll_duration",
                "function_dispatch_slot_hold_duration",
                "scheduler_visit_duration",
                "scheduler_idle_duration",
        };

        SimpleMeterRegistry basic = registryFor(MetricsProfileConfiguration.MetricsProfile.BASIC);
        SimpleMeterRegistry advanced = registryFor(MetricsProfileConfiguration.MetricsProfile.ADVANCED);
        for (String name : diagnostic) {
            io.micrometer.core.instrument.Timer.builder(name).tag("function", "echo").register(basic);
            io.micrometer.core.instrument.Timer.builder(name).tag("function", "echo").register(advanced);
        }

        for (String name : diagnostic) {
            assertThat(basic.find(name).timer()).describedAs(name + " must not survive basic").isNull();
            assertThat(advanced.find(name).timer()).describedAs(name + " must survive advanced").isNotNull();
        }

        // And the operational counters keep surviving basic, which is what makes
        // this a narrowing of the profile rather than a second switch beside it.
        Counter.builder("function_dispatch_total").tag("function", "echo").register(basic);
        assertThat(basic.find("function_dispatch_total").counter()).isNotNull();
    }

    @Test
    void basicKeepsGlobalSyncQueueDepthAndDropsPerFunctionSeries() {
        SimpleMeterRegistry registry = registryFor(MetricsProfileConfiguration.MetricsProfile.BASIC);

        Gauge.builder("sync_queue_depth", () -> 1).tag("function", "").register(registry);
        Gauge.builder("sync_queue_depth", () -> 1).tag("function", "echo").register(registry);

        assertThat(registry.find("sync_queue_depth").tag("function", "").gauge()).isNotNull();
        assertThat(registry.find("sync_queue_depth").tag("function", "echo").gauge()).isNull();
    }

    @Test
    void advancedKeepsDetailedMetricsAndConfiguresFunctionTimerHistograms() {
        SimpleMeterRegistry registry = registryFor(MetricsProfileConfiguration.MetricsProfile.ADVANCED);
        Counter.builder("function_cold_start_total").tag("function", "echo").register(registry);

        Meter.Id timerId = new Meter.Id(
                "function_latency_ms", Tags.of("function", "echo"), null, null, Meter.Type.TIMER);
        DistributionStatisticConfig distribution = configuration
                .metricsProfileFilter(MetricsProfileConfiguration.MetricsProfile.ADVANCED)
                .configure(timerId, DistributionStatisticConfig.DEFAULT);

        assertThat(registry.find("function_cold_start_total").counter()).isNotNull();
        assertThat(distribution.isPercentileHistogram()).isTrue();
    }

    @Test
    void publishesActiveProfileAndRejectsUnknownValues() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MetricsProfileConfiguration.MetricsProfile profile = configuration.metricsProfile("AdVaNcEd");

        configuration.metricsProfileInfo(registry, profile);

        assertThat(registry.find("nanofaas_metrics_profile_info").tag("profile", "advanced").gauge())
                .extracting(Gauge::value)
                .isEqualTo(1.0);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> configuration.metricsProfile("verbose"))
                .withMessageContaining("basic")
                .withMessageContaining("advanced");
    }

    private SimpleMeterRegistry registryFor(MetricsProfileConfiguration.MetricsProfile profile) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeterFilter filter = configuration.metricsProfileFilter(profile);
        registry.config().meterFilter(filter);
        return registry;
    }
}
