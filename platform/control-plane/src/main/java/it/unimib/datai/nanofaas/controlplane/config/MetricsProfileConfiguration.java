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
    /**
     * Timers that get percentile histograms when a run asks for advanced.
     *
     * Separate from what basic denies, because the two costs are different. The
     * meter itself is 43ns per record; the histogram is buckets, and for a timer
     * tagged by function that is one series per bucket per function, which is
     * what a production registry cannot afford. Keeping a timer in basic without
     * its histogram still gives count and sum, which is a mean - enough for an
     * SLI, and nearly free.
     *
     * `scheduler_switch_duration` is here for a reason the function timers do
     * not share, and it does not pay their cost: it is registered with no
     * function tag (SchedulerConfiguration's switch observer) and there is one
     * engine per process, so its histogram is a fixed bucket set rather than one
     * scaled by the number of functions. It was added on 2026-09-22 because the
     * pause budget the campaign freezes - maxSwitchPauseP99Ms, 100 ms - names a
     * p99, and a p99 has no series without buckets. Basic still keeps the timer
     * and its `_max`, which is what the pause is read against today.
     *
     * Its cost, since every other choice here states one: the default bucket set
     * is 69 `le` classes - measured on 2026-09-22 by scraping a registry that
     * carries this filter, not estimated - so the timer goes from 3 series to 72
     * per non-basic process. Paid once, and paid flat: it is the same 72 whether
     * the platform serves one function or a thousand, which is the whole reason
     * this timer can afford what the four above cannot.
     */
    private static final Set<String> HISTOGRAM_TIMERS = Set.of(
            "function_latency_ms",
            "function_init_duration_ms",
            "function_queue_wait_ms",
            "function_e2e_latency_ms",
            "scheduler_switch_duration"
    );

    /**
     * Of those, the ones a production deployment does not need.
     *
     * queue_wait is a decomposition of e2e_latency and only interesting when
     * something is wrong; init_duration fires once per cold start, which
     * function_cold_start_total already counts.
     *
     * The other two are not an observability choice at all. ConcurrencyGovernor
     * reads function_latency_ms and function_e2e_latency_ms out of the registry
     * and drives its Vegas signal from count() and totalTime() - so denying them
     * does not make the platform quieter, it makes the governor steer on zero,
     * silently. Until 2026-08-23 basic denied both, which is the profile every
     * production deployment gets by default.
     *
     * MetricsProfileConfigurationTest holds the list of meters a control loop
     * reads, so this coupling fails a test instead of failing in production.
     *
     * They also happen to be the D of RED, and their measured cost is 2.00
     * records per dispatch, 0.0105% of a two-core budget at 2,430 requests a
     * second.
     */
    private static final Set<String> ADVANCED_ONLY_TIMERS = Set.of(
            "function_init_duration_ms",
            "function_queue_wait_ms"
    );

    private static final Set<String> ADVANCED_METRICS = Set.of(
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
            "function_dispatch_slot_hold_",
            "scheduler_visit_duration",
            "scheduler_idle_duration"
    );

    @Bean
    MetricsProfile metricsProfile(@Value("${nanofaas.metrics.profile:basic}") String configuredProfile) {
        try {
            return MetricsProfile.valueOf(configuredProfile.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "nanofaas.metrics.profile must be 'basic', 'advanced', or 'soak'", exception);
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
                if (profile != MetricsProfile.BASIC && HISTOGRAM_TIMERS.contains(id.getName())) {
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
        if (ADVANCED_ONLY_TIMERS.contains(name) || ADVANCED_METRICS.contains(name)) {
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
        return function != null && !function.isEmpty();
    }

    enum MetricsProfile {
        BASIC,
        ADVANCED,
        SOAK
    }
}
