package it.unimib.datai.nanofaas.controlplane.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
class MetricsProfileConfiguration {
    private static final Set<String> FUNCTION_TIMERS = Set.of(
            "function_latency_ms",
            "function_init_duration_ms",
            "function_queue_wait_ms",
            "function_e2e_latency_ms"
    );
    private static final Set<String> ADVANCED_METRICS = Set.of(
            "function_cold_start_total",
            "function_warm_start_total",
            "function_effective_concurrency",
            "function_target_inflight_per_pod",
            "function_concurrency_controller_mode",
            "gateway_service_target_load",
            "controlplane_runtime_config_revision",
            "controlplane_runtime_config_updates_total",
            "controlplane_runtime_config_apply_duration_seconds"
    );

    /**
     * The dispatch-path instrumentation, off unless a run asks for it.
     *
     * These fire 15.74 times per dispatch - measured from the counters of the
     * 2026-08-23 A/B, not counted by hand - which at 43ns a record is 0.079% of a
     * two-core budget at 2,430 requests a second. Small, and small is not the same
     * as free: they were also invisible to this class, so a production deployment
     * on the default `basic` profile paid for all of them while nobody was reading.
     *
     * Prefixes rather than names because each family carries a function tag and
     * several members, and a list of exact names is a list that falls behind.
     *
     * Denying the meter recovers the record() but not the two nanoTime calls the
     * call sites make themselves, so about 46% of the cost. Chasing the rest would
     * mean a guard at ten hot-path call sites for 0.04% of a budget, which is a
     * worse trade than the one it fixes.
     */
    private static final Set<String> DIAGNOSTIC_PREFIXES = Set.of(
            "function_scheduler_",
            "function_queue_offer_duration",
            "function_queue_poll_duration",
            "function_dispatch_slot_hold_duration",
            "scheduler_visit_duration",
            "scheduler_idle_duration"
    );

    @Bean
    MetricsProfile metricsProfile(@Value("${nanofaas.metrics.profile:basic}") String configuredProfile) {
        try {
            return MetricsProfile.valueOf(configuredProfile.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "nanofaas.metrics.profile must be 'basic' or 'advanced'", exception);
        }
    }

    @Bean
    MeterFilter metricsProfileFilter(MetricsProfile profile) {
        return new MeterFilter() {
            @Override
            public MeterFilterReply accept(Meter.Id id) {
                return profile == MetricsProfile.BASIC && isAdvanced(id)
                        ? MeterFilterReply.DENY
                        : MeterFilterReply.NEUTRAL;
            }

            @Override
            public DistributionStatisticConfig configure(
                    Meter.Id id, DistributionStatisticConfig config) {
                if (profile == MetricsProfile.ADVANCED && FUNCTION_TIMERS.contains(id.getName())) {
                    return DistributionStatisticConfig.builder()
                            .percentilesHistogram(true)
                            .build()
                            .merge(config);
                }
                return config;
            }
        };
    }

    @Bean
    Gauge metricsProfileInfo(MeterRegistry registry, MetricsProfile profile) {
        return Gauge.builder("nanofaas_metrics_profile_info", () -> 1)
                .tag("profile", profile.name().toLowerCase(Locale.ROOT))
                .register(registry);
    }

    private static boolean isAdvanced(Meter.Id id) {
        String name = id.getName();
        if (FUNCTION_TIMERS.contains(name) || ADVANCED_METRICS.contains(name)) {
            return true;
        }
        for (String prefix : DIAGNOSTIC_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        if (!name.startsWith("sync_queue_")) {
            return false;
        }
        String function = id.getTag("function");
        return name.equals("sync_queue_wait_seconds") || function != null && !function.isEmpty();
    }

    enum MetricsProfile {
        BASIC,
        ADVANCED
    }
}
