package it.unimib.datai.nanofaas.controlplane.config;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    void doesNotPublishZeroCountersForAnUnavailableCollector() {
        GarbageCollectorMXBean collector = mock(GarbageCollectorMXBean.class);
        when(collector.getName()).thenReturn("G1");
        when(collector.getCollectionCount()).thenReturn(-1L);
        when(collector.getCollectionTime()).thenReturn(-1L);

        try (MockedStatic<ManagementFactory> managementFactory = mockStatic(ManagementFactory.class)) {
            managementFactory.when(ManagementFactory::getGarbageCollectorMXBeans).thenReturn(List.of(collector));

            new GcMetricsConfiguration().gcCollectionMetrics().bindTo(registry);
        }

        assertThat(registry.find("jvm_gc_collection_count").functionCounters()).isEmpty();
        assertThat(registry.find("jvm_gc_collection_time").functionCounters()).isEmpty();
    }

    @Test
    void marksGcCollectionMetricsUnavailableWhenThereAreNoUsableMxBeans() {
        try (MockedStatic<ManagementFactory> managementFactory = mockStatic(ManagementFactory.class)) {
            managementFactory.when(ManagementFactory::getGarbageCollectorMXBeans).thenReturn(List.of());

            new GcMetricsConfiguration().gcMetricsSource().bindTo(registry);
        }

        assertThat(registry.find("nanofaas_gc_metrics_source").gauges())
                .singleElement()
                .satisfies(gauge -> assertThat(gauge.getId().getTag("source")).isEqualTo("unavailable"));
    }

    @Test
    void startsAndClosesOneJfrFallbackWhenMxBeansAreAbsent() {
        GcMetricsConfiguration configuration;
        try (MockedStatic<ManagementFactory> managementFactory = mockStatic(ManagementFactory.class)) {
            managementFactory.when(ManagementFactory::getGarbageCollectorMXBeans).thenReturn(List.of());
            configuration = new GcMetricsConfiguration();
        }

        try (MockedConstruction<RecordingStream> streams = mockConstruction(RecordingStream.class)) {
            MeterBinder binder = configuration.jfrVmOperationMetrics();
            binder.bindTo(registry);
            binder.bindTo(registry);
            configuration.closeJfrStream();

            assertThat(streams.constructed()).singleElement().satisfies(stream -> {
                verify(stream, times(1)).startAsync();
                verify(stream, times(1)).close();
            });
        }
    }

    @Test
    void measuresGcFractionSinceMetricsObservationStarted() {
        GarbageCollectorMXBean collector = mock(GarbageCollectorMXBean.class);
        RuntimeMXBean runtime = mock(RuntimeMXBean.class);
        when(collector.getCollectionTime()).thenReturn(100L, 100L, 300L);
        when(runtime.getUptime()).thenReturn(1_000L, 2_000L);

        try (MockedStatic<ManagementFactory> managementFactory = mockStatic(ManagementFactory.class)) {
            managementFactory.when(ManagementFactory::getGarbageCollectorMXBeans).thenReturn(List.of(collector));
            managementFactory.when(ManagementFactory::getRuntimeMXBean).thenReturn(runtime);

            new GcMetricsConfiguration().gcOverheadMetric().bindTo(registry);
            assertThat(registry.find("jvm_gc_time_fraction").gauge().value()).isEqualTo(0.2);
        }
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
