package it.unimib.datai.nanofaas.modules.autoscaler;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ScalingMetric;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScalingMetricsReaderTest {

    @Test
    void configurationRunsAfterEitherQueueProvider() {
        assertThat(AutoscalerConfiguration.class.getAnnotation(AutoConfigureAfter.class).name())
                .containsExactlyInAnyOrder(
                        "it.unimib.datai.nanofaas.modules.asyncqueue.AsyncQueueConfiguration",
                        "it.unimib.datai.nanofaas.modules.syncqueue.SyncQueueConfiguration"
                );
    }

    @Mock
    private WorkloadMetricsSource scalingMetricsSource;

    @Mock
    private it.unimib.datai.nanofaas.controlplane.service.InvocationObservations observations;

    private ScalingMetricsReader reader;

    @BeforeEach
    void setUp() {
        reader = new ScalingMetricsReader(scalingMetricsSource, observations);
    }

    @Test
    void readMetric_queueDepth() {
        when(scalingMetricsSource.queueDepth("echo")).thenReturn(7);

        double value = reader.readMetric("echo", new ScalingMetric("queue_depth", "5", null));
        assertEquals(7.0, value);
    }

    @Test
    void readMetric_queueDepth_returnsZeroWhenQueueNotFound() {
        when(scalingMetricsSource.queueDepth("echo")).thenReturn(0);

        double value = reader.readMetric("echo", new ScalingMetric("queue_depth", "5", null));
        assertEquals(0.0, value);
    }

    @Test
    void readMetric_inFlight() {
        when(scalingMetricsSource.inFlight("echo")).thenReturn(3);

        double value = reader.readMetric("echo", new ScalingMetric("in_flight", "4", null));
        assertEquals(3.0, value);
    }

    @Test
    void readMetric_unknownType_returnsZero() {
        double value = reader.readMetric("echo", new ScalingMetric("unknown_metric", "10", null));
        assertEquals(0.0, value);
    }

    @Test
    void readMetric_rps_canBeUnitTestedWithAWorkloadSourceStub() {
        // RPS is read from a meter rather than WorkloadMetricsSource. This unit test uses a stub
        // only to isolate the reader; the autoscaler module itself requires a queue provider.
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ScalingMetricsReader r = new ScalingMetricsReader(new WorkloadMetricsSource() {
            public int queueDepth(String functionName) { return 0; }
            public int inFlight(String functionName) { return 0; }
            public int effectiveConcurrency(String functionName) { return 0; }
            public int dispatchableBacklog(String functionName) { return 0; }
        }, new it.unimib.datai.nanofaas.controlplane.service.Metrics(registry));

        assertEquals(0.0, r.readMetric("echo", new ScalingMetric("rps", "1", null)));
        assertEquals(0.0, r.readMetric("echo", new ScalingMetric("queue_depth", "5", null)));
    }

    @SuppressWarnings("java:S2925") // clock advancement: the per-interval metric window must advance before the second read
    @Test
    void readMetric_rps_usesPerIntervalDeltaInsteadOfCumulativeCounter() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var metrics = new it.unimib.datai.nanofaas.controlplane.service.Metrics(registry);
        metrics.registerFunction("echo");
        Counter counter = Counter.builder("function_dispatch_total").tag("function", "echo").register(registry);
        counter.increment(3.0);

        ScalingMetricsReader r = new ScalingMetricsReader(scalingMetricsSource, metrics);
        double first = r.readMetric("echo", new ScalingMetric("rps", "1", null));

        Thread.sleep(Duration.ofMillis(1100));
        counter.increment(2.0);

        double second = r.readMetric("echo", new ScalingMetric("rps", "1", null));

        assertEquals(0.0, first);
        assertThat(second).isBetween(1.5, 2.5);
    }
    @Test
    void rpsDoesNotRecreateRemovedMetersOrCompareDifferentGenerations() {
        var registry = new SimpleMeterRegistry();
        var metrics = new it.unimib.datai.nanofaas.controlplane.service.Metrics(registry);
        var reader = new ScalingMetricsReader(scalingMetricsSource, metrics);
        var rps = new ScalingMetric("rps", "1", null);
        metrics.registerFunction("echo");
        metrics.dispatch("echo");
        assertThat(reader.readMetric("echo", rps)).isZero();
        metrics.removeFunction("echo");
        reader.removeFunctionState("echo");
        assertThat(reader.readMetric("echo", rps)).isZero();
        assertThat(registry.getMeters()).isEmpty();
        metrics.registerFunction("echo");
        metrics.dispatch("echo");
        metrics.dispatch("echo");
        assertThat(reader.readMetric("echo", rps)).isZero();
        // Generation changes also reset the sample if a polling cycle races lifecycle cleanup.
        metrics.removeFunction("echo");
        metrics.registerFunction("echo");
        assertThat(reader.readMetric("echo", rps)).isZero();
    }
}
