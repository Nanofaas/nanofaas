package it.unimib.datai.nanofaas.controlplane.scaling;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import it.unimib.datai.nanofaas.common.model.ScalingMetric;
import it.unimib.datai.nanofaas.controlplane.queue.FunctionQueueState;
import it.unimib.datai.nanofaas.controlplane.queue.QueueManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ScalingMetricsReader {
    private static final Logger log = LoggerFactory.getLogger(ScalingMetricsReader.class);

    private final QueueManager queueManager;
    private final MeterRegistry meterRegistry;

    public ScalingMetricsReader(QueueManager queueManager, MeterRegistry meterRegistry) {
        this.queueManager = queueManager;
        this.meterRegistry = meterRegistry;
    }

    public double readMetric(String functionName, ScalingMetric metric) {
        return switch (metric.type()) {
            case "queue_depth" -> readQueueDepth(functionName);
            case "in_flight" -> readInFlight(functionName);
            case "rps" -> readRps(functionName);
            default -> {
                log.warn("Unknown metric type '{}' for function {}, returning 0", metric.type(), functionName);
                yield 0.0;
            }
        };
    }

    private double readQueueDepth(String functionName) {
        FunctionQueueState state = queueManager.get(functionName);
        return state != null ? state.queued() : 0;
    }

    private double readInFlight(String functionName) {
        FunctionQueueState state = queueManager.get(functionName);
        return state != null ? state.inFlight() : 0;
    }

    private double readRps(String functionName) {
        Counter counter = meterRegistry.find("function_dispatched_total")
                .tag("function", functionName)
                .counter();
        // Return total count; the scaler should track the rate over its poll interval
        return counter != null ? counter.count() : 0;
    }
}
