package it.unimib.datai.nanofaas.controlplane.config;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.binder.MeterBinder;
import jakarta.annotation.PreDestroy;
import jdk.jfr.consumer.RecordingStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
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
 * <p>Where usable MXBeans exist, polling their count and time yields collection totals without
 * notifications. Native G1 exposes no usable collector MXBean, so its JFR VM operations are
 * reported separately rather than fabricated as GC collection counters.</p>
 *
 * <p>Registered on both builds rather than only the native one: a diagnostic that exists in one
 * configuration and not the other cannot be used to compare them, which is the whole reason this
 * was missed.</p>
 */
@Configuration(proxyBeanMethods = false)
class GcMetricsConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(GcMetricsConfiguration.class);

    private final List<GarbageCollectorMXBean> collectors =
            List.copyOf(ManagementFactory.getGarbageCollectorMXBeans());
    private RecordingStream jfrStream;

    @Bean
    MeterBinder gcCollectionMetrics() {
        return registry -> {
            for (GarbageCollectorMXBean collector : usableCollectors()) {
                String name = collector.getName();
                long initialCount = collector.getCollectionCount();
                long initialTime = collector.getCollectionTime();
                FunctionCounter.builder(
                                "jvm_gc_collection_count",
                                collector,
                                bean -> bean.getCollectionCount() - initialCount)
                        .tag("gc", name)
                        .description("Collections performed, polled from the MXBean")
                        .register(registry);
                FunctionCounter.builder(
                                "jvm_gc_collection_time",
                                collector,
                                bean -> (bean.getCollectionTime() - initialTime) / 1000.0)
                        .tag("gc", name)
                        .baseUnit("seconds")
                        .description("Total time spent collecting, polled from the MXBean")
                        .register(registry);
            }
        };
    }

    @Bean
    MeterBinder gcMetricsSource() {
        return registry -> registry.gauge(
                "nanofaas_gc_metrics_source",
                List.of(Tag.of("source", usableCollectors().isEmpty() ? "unavailable" : "mxbean")),
                this,
                ignored -> 1.0);
    }

    @Bean
    MeterBinder gcOverheadMetric() {
        return registry -> {
            List<GarbageCollectorMXBean> usableCollectors = usableCollectors();
            if (usableCollectors.isEmpty()) {
                return;
            }
            long initialCollectedMs = collectionTime(usableCollectors);
            long startedAtMs = ManagementFactory.getRuntimeMXBean().getUptime();
            registry.gauge("jvm_gc_time_fraction", List.of(), this, ignored -> {
                long elapsedMs = ManagementFactory.getRuntimeMXBean().getUptime() - startedAtMs;
                if (elapsedMs <= 0) {
                    return 0.0;
                }
                return Math.min(1.0, (collectionTime(usableCollectors) - initialCollectedMs) / (double) elapsedMs);
            });
        };
    }

    @Bean
    MeterBinder jfrVmOperationMetrics() {
        return registry -> {
            if (!usableCollectors().isEmpty() || jfrStream != null) {
                return;
            }
            try {
                JfrGcMetrics metrics = new JfrGcMetrics(registry);
                RecordingStream stream = new RecordingStream();
                stream.enable("jdk.ExecuteVMOperation");
                stream.onEvent(
                        "jdk.ExecuteVMOperation",
                        event -> metrics.record(event.getString("operation"), event.getDuration()));
                stream.startAsync();
                jfrStream = stream;
            } catch (IllegalStateException | UnsupportedOperationException exception) {
                LOG.warn("JFR is unavailable; GC collection metrics will remain unavailable", exception);
            }
        };
    }

    @PreDestroy
    void closeJfrStream() {
        if (jfrStream != null) {
            jfrStream.close();
        }
    }

    private List<GarbageCollectorMXBean> usableCollectors() {
        List<GarbageCollectorMXBean> usable = new ArrayList<>();
        for (GarbageCollectorMXBean collector : collectors) {
            if (collector.getCollectionCount() >= 0 && collector.getCollectionTime() >= 0) {
                usable.add(collector);
            }
        }
        return usable;
    }

    private static long collectionTime(List<GarbageCollectorMXBean> collectors) {
        return collectors.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
    }
}
