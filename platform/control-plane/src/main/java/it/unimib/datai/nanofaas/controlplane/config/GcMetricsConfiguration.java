package it.unimib.datai.nanofaas.controlplane.config;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * Garbage collection counters that survive being compiled to a native image.
 *
 * <p>Micrometer's own {@code JvmGcMetrics} listens for GC <em>notifications</em>, and SubstrateVM
 * emits none — it logs "GC notifications will not be available because no GarbageCollectorMXBean of
 * the JVM provides any" and then publishes nothing but a flat {@code jvm_gc_overhead} of zero. That
 * silence is the opposite of the truth it is reporting: a native build measured here lost half its
 * throughput to collection pauses reaching 1.94 seconds, while its metrics said the GC was idle.</p>
 *
 * <p>The MXBeans themselves are present and answer {@code getCollectionCount()} and
 * {@code getCollectionTime()} on both HotSpot and SubstrateVM, so polling them works everywhere.
 * That yields how often collection happened and how long it took in total — enough for a GC
 * overhead ratio and a mean pause. It does not yield the maximum pause, which needs the
 * notifications; the tail still has to be read from the latency histogram, where callers feel it.</p>
 *
 * <p>Registered on both builds rather than only the native one: a diagnostic that exists in one
 * configuration and not the other cannot be used to compare them, which is the whole reason this
 * was missed.</p>
 */
@Configuration(proxyBeanMethods = false)
class GcMetricsConfiguration {

    @Bean
    MeterBinder gcCollectionMetrics() {
        return registry -> {
            List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
            for (GarbageCollectorMXBean collector : collectors) {
                String name = collector.getName();
                FunctionCounter.builder(
                                "jvm_gc_collection_count", collector, bean -> value(bean.getCollectionCount()))
                        .tag("gc", name)
                        .description("Collections performed, polled from the MXBean")
                        .register(registry);
                FunctionCounter.builder(
                                "jvm_gc_collection_time",
                                collector,
                                bean -> value(bean.getCollectionTime()) / 1000.0)
                        .tag("gc", name)
                        .baseUnit("seconds")
                        .description("Total time spent collecting, polled from the MXBean")
                        .register(registry);
            }
        };
    }

    /**
     * The MXBean contract returns -1 when a collector cannot report a figure, and a negative counter
     * would read as a reset to every consumer downstream.
     */
    private static double value(long reported) {
        return reported < 0 ? 0.0 : (double) reported;
    }

    @Bean
    MeterBinder gcOverheadMetric() {
        return registry -> registry.gauge(
                "jvm_gc_time_fraction",
                List.of(),
                ManagementFactory.getGarbageCollectorMXBeans(),
                collectors -> {
                    double collectedMs = collectors.stream()
                            .mapToDouble(bean -> value(bean.getCollectionTime()))
                            .sum();
                    // Uptime, not time since this bean was built: collection time accumulates from
                    // VM start, and dividing it by the age of a bean created later reported a
                    // process spending 367% of its life collecting.
                    double upMs = ManagementFactory.getRuntimeMXBean().getUptime();
                    // The share of wall-clock time spent collecting. Comparable across builds in a
                    // way that a raw total is not, since the runs differ in length.
                    return upMs <= 0 ? 0.0 : Math.min(1.0, collectedMs / upMs);
                });
    }
}
