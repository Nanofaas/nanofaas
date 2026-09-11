package it.unimib.datai.nanofaas.modules.autoscaler;

import it.unimib.datai.nanofaas.controlplane.service.InvocationObservations;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.common.model.ScalingMetric;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ScalingMetricsReader {
    private static final Logger log = LoggerFactory.getLogger(ScalingMetricsReader.class);

    private final WorkloadMetricsSource scalingMetricsSource;
    private final InvocationObservations observations;
    private final Map<String, CounterSample> lastDispatchSamples = new ConcurrentHashMap<>();

    public ScalingMetricsReader(WorkloadMetricsSource scalingMetricsSource, InvocationObservations observations) {
        this.scalingMetricsSource = scalingMetricsSource;
        this.observations = observations;
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

    void removeFunctionState(String functionName) {
        lastDispatchSamples.remove(functionName);
    }

    private double readRps(String functionName) {
        var sample = observations.snapshot(functionName);
        if (sample.generation() == null) {
            lastDispatchSamples.remove(functionName);
            return 0;
        }
        CounterSample current = new CounterSample(sample.generation(), sample.dispatched(), System.currentTimeMillis());
        CounterSample previous = lastDispatchSamples.put(functionName, current);
        if (previous == null || !previous.generation().equals(current.generation())) {
            return 0.0;
        }

        double deltaCount = current.count() - previous.count();
        long deltaMs = current.epochMs() - previous.epochMs();
        if (deltaCount <= 0.0 || deltaMs <= 0L) {
            return 0.0;
        }
        return deltaCount / (deltaMs / 1000.0);
    }

    private record CounterSample(FunctionGeneration generation, double count, long epochMs) {
    }
}
