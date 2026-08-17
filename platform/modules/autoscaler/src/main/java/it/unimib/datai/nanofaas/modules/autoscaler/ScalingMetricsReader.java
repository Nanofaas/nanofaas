package it.unimib.datai.nanofaas.modules.autoscaler;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import it.unimib.datai.nanofaas.common.model.ScalingMetric;
import it.unimib.datai.nanofaas.controlplane.service.ScalingMetricsSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScalingMetricsReader {
    private static final Logger log = LoggerFactory.getLogger(ScalingMetricsReader.class);

    private final ScalingMetricsSource scalingMetricsSource;
    private final MeterRegistry meterRegistry;
    private final Map<String, Counter> dispatchCounters = new ConcurrentHashMap<>();
    private final Map<String, CounterSample> lastDispatchSamples = new ConcurrentHashMap<>();
    private final AtomicBoolean blindSourceWarningLogged = new AtomicBoolean();

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
        warnOnceIfSourceIsBlind(functionName);
        return scalingMetricsSource.queueDepth(functionName);
    }

    private double readInFlight(String functionName) {
        warnOnceIfSourceIsBlind(functionName);
        return scalingMetricsSource.inFlight(functionName);
    }

    /**
     * The autoscaler is useful without a queue module — {@code rps} is read from a meter here,
     * not from the source — so a missing source is not fatal. It is fatal to {@code queue_depth}
     * and {@code in_flight}, which the no-op answers with 0 forever: the function looks idle and
     * never scales. Warn once rather than on every tick; the condition is fixed at startup.
     */
    private void warnOnceIfSourceIsBlind(String functionName) {
        if (!scalingMetricsSource.enabled() && blindSourceWarningLogged.compareAndSet(false, true)) {
            log.warn("Scaling function {} on queue state, but no module supplies it "
                            + "(async-queue is not loaded): queue_depth and in_flight will read 0 "
                            + "and the function will never scale. Use the rps metric, or add async-queue.",
                    functionName);
        }
    }

    public double queueDepth(String functionName) {
        return readQueueDepth(functionName);
    }

    public double inFlight(String functionName) {
        return readInFlight(functionName);
    }

    void removeFunctionState(String functionName) {
        dispatchCounters.remove(functionName);
        lastDispatchSamples.remove(functionName);
    }

    private double readRps(String functionName) {
        Counter counter = dispatchCounters.computeIfAbsent(functionName, fn ->
                Counter.builder("function_dispatch_total")
                        .tag("function", fn)
                        .register(meterRegistry));
        CounterSample current = new CounterSample(counter.count(), System.currentTimeMillis());
        CounterSample previous = lastDispatchSamples.put(functionName, current);
        if (previous == null) {
            return 0.0;
        }

        double deltaCount = current.count() - previous.count();
        long deltaMs = current.epochMs() - previous.epochMs();
        if (deltaCount <= 0.0 || deltaMs <= 0L) {
            return 0.0;
        }
        return deltaCount / (deltaMs / 1000.0);
    }

    private record CounterSample(double count, long epochMs) {
    }
}
