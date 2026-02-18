package it.unimib.datai.nanofaas.modules.autoscaler;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;
import it.unimib.datai.nanofaas.common.model.ScalingMetric;
import it.unimib.datai.nanofaas.controlplane.service.ScalingMetricsSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ScalingMetricsReader {
    private static final Logger log = LoggerFactory.getLogger(ScalingMetricsReader.class);

    private final ScalingMetricsSource scalingMetricsSource;
    private final MeterRegistry meterRegistry;
    private final java.util.Map<String, Counter> dispatchCounters = new java.util.concurrent.ConcurrentHashMap<>();

    public ScalingMetricsReader(ScalingMetricsSource scalingMetricsSource, MeterRegistry meterRegistry) {
        this.scalingMetricsSource = scalingMetricsSource;
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
        return scalingMetricsSource.queueDepth(functionName);
    }

    private double readInFlight(String functionName) {
        return scalingMetricsSource.inFlight(functionName);
    }

    public double queueDepth(String functionName) {
        return readQueueDepth(functionName);
    }

    public double inFlight(String functionName) {
        return readInFlight(functionName);
    }

    public void setEffectiveConcurrency(String functionName, int effectiveConcurrency) {
        scalingMetricsSource.setEffectiveConcurrency(functionName, effectiveConcurrency);
    }

    public void updateConcurrencyControllerState(String functionName,
                                                 ConcurrencyControlMode mode,
                                                 int targetInFlightPerPod) {
        scalingMetricsSource.updateConcurrencyController(functionName, mode, targetInFlightPerPod);
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
