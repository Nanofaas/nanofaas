package it.unimib.datai.nanofaas.controlplane.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

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
        // Cold starts moved into basic on 2026-08-23. On a FaaS they are the first
        // thing anyone asks when latency moves, they fire once per 250,000
        // dispatches at the rate measured here, and the counter costs 4.86ns. This
        // line used to assert the opposite.
        assertThat(registry.find("function_cold_start_total").counter()).isNotNull();
        assertThat(registry.find("function_concurrency_controller_mode").gauge()).isNull();
    }

    @Test
    void everyMeterAControlLoopReadsSurvivesBasic() {
        // Some meters are not observability, they are inputs. Denying one of these
        // does not make the platform quieter - it makes a control loop steer on
        // zero, and a NoopTimer answers count() and totalTime() without complaint.
        //
        // Until 2026-08-23 basic denied the first two, and basic is the profile a
        // deployment gets when nobody says otherwise.
        record ControlInput(String meter, String reader) {}
        var inputs = new ControlInput[]{
                // Read inside this JVM.
                new ControlInput("function_latency_ms", "ConcurrencyGovernor, Vegas signal"),
                new ControlInput("function_e2e_latency_ms", "ConcurrencyGovernor, Vegas signal"),
                new ControlInput("function_dispatch_total", "autoscaler, ScalingMetricsReader"),
                // Read outside it, by Kubernetes. The HPA specs this platform builds
                // (KubernetesMetricsTranslator) name nanofaas_in_flight,
                // nanofaas_rps and nanofaas_queue_depth, which prometheus-adapter
                // maps from these three series - see the rules in
                // deploy/helm/nanofaas/values.yaml. Deny one and the HPA stops
                // scaling, with nothing in this process to say why.
                new ControlInput("function_inFlight", "HPA via nanofaas_in_flight"),
                new ControlInput("function_queue_depth", "HPA via nanofaas_queue_depth"),
        };

        SimpleMeterRegistry basic = registryFor(MetricsProfileConfiguration.MetricsProfile.BASIC);
        for (ControlInput input : inputs) {
            if (input.meter().endsWith("_total")) {
                Counter.builder(input.meter()).tag("function", "echo").register(basic);
            } else if (!input.meter().endsWith("_ms")) {
                Gauge.builder(input.meter(), () -> 1).tag("function", "echo").register(basic);
            } else {
                io.micrometer.core.instrument.Timer.builder(input.meter())
                        .tag("function", "echo").register(basic);
            }
        }

        for (ControlInput input : inputs) {
            assertThat(basic.find(input.meter()).meter())
                    .describedAs("%s is read by %s and must survive basic",
                            input.meter(), input.reader())
                    .isNotNull();
        }
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
    void basicDropsSharedDiagnosticCountersAndAdvancedKeepsThem() {
        SimpleMeterRegistry basic = registryFor(MetricsProfileConfiguration.MetricsProfile.BASIC);
        SimpleMeterRegistry advanced = registryFor(MetricsProfileConfiguration.MetricsProfile.ADVANCED);
        String[] names = {
                "function_dispatch_slot_hold_seconds",
                "function_dispatch_slot_hold_events",
                "function_scheduler_slot_blocked",
        };

        for (String name : names) {
            Counter.builder(name).tag("function", "echo").register(basic);
            Counter.builder(name).tag("function", "echo").register(advanced);
            assertThat(basic.find(name).counter()).describedAs(name + " must not survive basic").isNull();
            assertThat(advanced.find(name).counter()).describedAs(name + " must survive advanced").isNotNull();
        }
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
    void nonBasicPublishesTheSwitchDurationHistogramAndBasicDoesNot() {
        // The campaign freezes maxSwitchPauseP99Ms - 100 ms - against
        // scheduler_switch_duration, and a p99 has no series to be read from
        // without buckets. The timer carries no function tag and there is one
        // engine, so this histogram is a fixed bucket set rather than one scaled
        // by the number of functions, which is why it can be on at all.
        for (MetricsProfileConfiguration.MetricsProfile profile
                : new MetricsProfileConfiguration.MetricsProfile[]{
                        MetricsProfileConfiguration.MetricsProfile.ADVANCED,
                        MetricsProfileConfiguration.MetricsProfile.SOAK}) {
            PrometheusMeterRegistry registry = prometheusFor(profile);
            Timer.builder("scheduler_switch_duration").register(registry)
                    .record(1, TimeUnit.MILLISECONDS);

            String scrape = registry.scrape();
            assertThat(scrape)
                    .describedAs("under %s the p99 budget has no series behind it", profile)
                    .contains("scheduler_switch_duration_seconds_bucket{le=\"")
                    .contains("scheduler_switch_duration_seconds_bucket{le=\"+Inf\"");
        }

        PrometheusMeterRegistry basic = prometheusFor(
                MetricsProfileConfiguration.MetricsProfile.BASIC);
        Timer.builder("scheduler_switch_duration").register(basic)
                .record(1, TimeUnit.MILLISECONDS);

        // Basic keeps the timer and the maximum the soak's pause budget reads
        // today - what it does not get is the histogram, which is the narrowing.
        assertThat(basic.scrape())
                .describedAs("basic must not pay for buckets")
                .doesNotContain("scheduler_switch_duration_seconds_bucket{le=\"");
        assertThat(basic.find("scheduler_switch_duration").timer()).isNotNull();
        assertThat(basic.scrape()).contains("scheduler_switch_duration_seconds_max");
    }

    @Test
    void soakParsesCaseInsensitivelyAndUsesAdvancedHistogramConfiguration() {
        assertThat(configuration.metricsProfile("soak"))
                .isEqualTo(MetricsProfileConfiguration.MetricsProfile.SOAK);
        assertThat(configuration.metricsProfile("SoAk"))
                .isEqualTo(MetricsProfileConfiguration.MetricsProfile.SOAK);

        Meter.Id timerId = new Meter.Id(
                "function_latency_ms", Tags.of("function", "echo"), null, null, Meter.Type.TIMER);
        DistributionStatisticConfig advanced = configuration
                .metricsProfileFilter(MetricsProfileConfiguration.MetricsProfile.ADVANCED)
                .configure(timerId, DistributionStatisticConfig.DEFAULT);
        DistributionStatisticConfig soak = configuration
                .metricsProfileFilter(MetricsProfileConfiguration.MetricsProfile.SOAK)
                .configure(timerId, DistributionStatisticConfig.DEFAULT);

        assertThat(soak.isPercentileHistogram()).isEqualTo(advanced.isPercentileHistogram());
    }

    @Test
    void publishesActiveProfileAndRejectsUnknownValues() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MetricsProfileConfiguration.MetricsProfile profile = configuration.metricsProfile("AdVaNcEd");

        configuration.metricsProfileInfo(registry, profile);

        assertThat(registry.find("nanofaas_metrics_profile_info").tag("profile", "advanced").gauge())
                .extracting(Gauge::value)
                .isEqualTo(1.0);

        SimpleMeterRegistry soakRegistry = new SimpleMeterRegistry();
        configuration.metricsProfileInfo(
                soakRegistry, configuration.metricsProfile("soak"));
        assertThat(soakRegistry.find("nanofaas_metrics_profile_info").tag("profile", "soak").gauge())
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

    private PrometheusMeterRegistry prometheusFor(
            MetricsProfileConfiguration.MetricsProfile profile) {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        registry.config().meterFilter(configuration.metricsProfileFilter(profile));
        return registry;
    }
}
