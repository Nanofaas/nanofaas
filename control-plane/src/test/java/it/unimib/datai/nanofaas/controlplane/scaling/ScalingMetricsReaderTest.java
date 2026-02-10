package it.unimib.datai.nanofaas.controlplane.scaling;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ScalingMetric;
import it.unimib.datai.nanofaas.controlplane.queue.FunctionQueueState;
import it.unimib.datai.nanofaas.controlplane.queue.QueueManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScalingMetricsReaderTest {

    @Mock
    private QueueManager queueManager;

    @Mock
    private MeterRegistry meterRegistry;

    @Mock
    private FunctionQueueState queueState;

    private ScalingMetricsReader reader;

    @BeforeEach
    void setUp() {
        reader = new ScalingMetricsReader(queueManager, meterRegistry);
    }

    @Test
    void readMetric_queueDepth() {
        when(queueManager.get("echo")).thenReturn(queueState);
        when(queueState.queued()).thenReturn(7);

        double value = reader.readMetric("echo", new ScalingMetric("queue_depth", "5", null));
        assertEquals(7.0, value);
    }

    @Test
    void readMetric_queueDepth_returnsZeroWhenQueueNotFound() {
        when(queueManager.get("echo")).thenReturn(null);

        double value = reader.readMetric("echo", new ScalingMetric("queue_depth", "5", null));
        assertEquals(0.0, value);
    }

    @Test
    void readMetric_inFlight() {
        when(queueManager.get("echo")).thenReturn(queueState);
        when(queueState.inFlight()).thenReturn(3);

        double value = reader.readMetric("echo", new ScalingMetric("in_flight", "4", null));
        assertEquals(3.0, value);
    }

    @Test
    void readMetric_unknownType_returnsZero() {
        double value = reader.readMetric("echo", new ScalingMetric("unknown_metric", "10", null));
        assertEquals(0.0, value);
    }

    @Test
    void readMetric_rps_readsDispatchCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // Counter is stored as a double; using increment(3) is fine.
        Counter.builder("function_dispatch_total").tag("function", "echo").register(registry).increment(3.0);

        ScalingMetricsReader r = new ScalingMetricsReader(queueManager, registry);
        double value = r.readMetric("echo", new ScalingMetric("rps", "1", null));
        assertEquals(3.0, value);
    }
}
