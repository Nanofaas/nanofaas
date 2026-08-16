package it.unimib.datai.nanofaas.controlplane.config;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

import static org.assertj.core.api.Assertions.assertThat;

class GcMetricsConfigurationTest {

    private final GcMetricsConfiguration configuration = new GcMetricsConfiguration();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void publishesOneSeriesPerCollectorWithoutRelyingOnNotifications() {
        // The point of this binder: SubstrateVM has the MXBeans but emits no GC notifications, so
        // Micrometer's own binder reports an idle collector on a build that lost half its
        // throughput to collection pauses.
        MeterBinder binder = configuration.gcCollectionMetrics();

        binder.bindTo(registry);

        int collectors = ManagementFactory.getGarbageCollectorMXBeans().size();
        assertThat(registry.find("jvm_gc_collection_count").functionCounters()).hasSize(collectors);
        assertThat(registry.find("jvm_gc_collection_time").functionCounters()).hasSize(collectors);
    }

    @Test
    void namesEveryCollectorSoTheYoungAndFullPassesCanBeToldApart() {
        configuration.gcCollectionMetrics().bindTo(registry);

        assertThat(registry.find("jvm_gc_collection_count").functionCounters())
                .allSatisfy(counter -> assertThat(counter.getId().getTag("gc")).isNotBlank());
    }

    @Test
    void reportsCollectionsAndTimeAsNonNegativeTotals() {
        configuration.gcCollectionMetrics().bindTo(registry);
        System.gc();

        assertThat(registry.find("jvm_gc_collection_count").functionCounters())
                .allSatisfy(counter -> assertThat(counter.count()).isNotNegative());
        assertThat(registry.find("jvm_gc_collection_time").functionCounters())
                .extracting(FunctionCounter::count)
                .allSatisfy(seconds -> assertThat(seconds).isNotNegative());
    }

    @Test
    void publishesTheShareOfWallClockSpentCollecting() {
        // A raw total cannot be compared between runs of different lengths, and comparing a JVM
        // build against a native one is the reason this exists.
        configuration.gcOverheadMetric().bindTo(registry);

        Gauge fraction = registry.find("jvm_gc_time_fraction").gauge();
        assertThat(fraction).isNotNull();
        assertThat(fraction.value()).isBetween(0.0, 1.0);
    }
}
