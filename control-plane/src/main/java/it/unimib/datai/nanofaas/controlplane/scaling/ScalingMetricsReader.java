package it.unimib.datai.nanofaas.controlplane.scaling;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;
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
    private final java.util.Map<String, Counter> dispatchCounters = new java.util.concurrent.ConcurrentHashMap<>();

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

    public double queueDepth(String functionName) {
        return readQueueDepth(functionName);
    }

    public double inFlight(String functionName) {
        return readInFlight(functionName);
    }

    public void setEffectiveConcurrency(String functionName, int effectiveConcurrency) {
        queueManager.setEffectiveConcurrency(functionName, effectiveConcurrency);
    }

    public void updateConcurrencyControllerState(String functionName,
                                                 ConcurrencyControlMode mode,
                                                 int targetInFlightPerPod) {
        queueManager.updateConcurrencyController(functionName, mode, targetInFlightPerPod);
    }

    private double readRps(String functionName) {
        Counter counter = dispatchCounters.computeIfAbsent(functionName, fn ->
                Counter.builder("function_dispatch_total")
                        .tag("function", fn)
                        .register(meterRegistry));
        // Return cumulative count; scaler computes rate over its poll interval.
        return counter.count();
    }
}
