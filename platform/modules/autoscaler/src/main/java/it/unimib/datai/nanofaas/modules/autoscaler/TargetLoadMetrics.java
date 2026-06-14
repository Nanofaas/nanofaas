package it.unimib.datai.nanofaas.modules.autoscaler;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingMetric;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exposes per-function scaling targets in an OpenFaaS-compatible shape.
 *
 * <p>Metric: {@code gateway_service_target_load{function,scaling_type}}</p>
 *
 * <p>{@code scaling_type} is one of: rps, queue, capacity, cpu.</p>
 */
public class TargetLoadMetrics {
    private final MeterRegistry registry;

    // function -> scaling_type -> target value (atomic backing Gauge)
    private final Map<String, Map<String, AtomicInteger>> targets = new ConcurrentHashMap<>();
    // function -> all meters registered for that function (for cleanup)
    private final Map<String, List<Meter.Id>> meterIds = new ConcurrentHashMap<>();

    public TargetLoadMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void update(FunctionSpec spec) {
        if (spec == null || spec.name() == null) {
            return;
        }
        ScalingConfig scaling = spec.scalingConfig();
        if (scaling == null || scaling.metrics() == null) {
            return;
        }

        String function = spec.name();

        for (ScalingMetric metric : scaling.metrics()) {
            if (metric == null || metric.type() == null) {
                continue;
            }
            String scalingType = mapScalingType(metric.type());
            if (scalingType == null) {
                continue;
            }

            int target = parseTarget(metric.target(), 0);
            AtomicInteger value = targets
                    .computeIfAbsent(function, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(scalingType, st -> registerGauge(function, st));

            value.set(target);
        }
    }

    public void remove(String functionName) {
        if (functionName == null) {
            return;
        }
        targets.remove(functionName);
        List<Meter.Id> ids = meterIds.remove(functionName);
        if (ids != null) {
            ids.forEach(registry::remove);
        }
    }

    private AtomicInteger registerGauge(String function, String scalingType) {
        AtomicInteger value = new AtomicInteger();
        Meter.Id id = Gauge.builder("gateway_service_target_load", value, AtomicInteger::get)
                .tag("function", function)
                .tag("scaling_type", scalingType)
                .register(registry)
                .getId();
        meterIds.computeIfAbsent(function, k -> new CopyOnWriteArrayList<>()).add(id);
        return value;
    }

    private static String mapScalingType(String metricType) {
        return switch (metricType) {
            case "queue_depth" -> "queue";
            case "in_flight" -> "capacity";
            case "rps" -> "rps";
            case "cpu" -> "cpu";
            default -> null;
        };
    }

    private static int parseTarget(String target, int defaultValue) {
        if (target == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(target);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }
}
