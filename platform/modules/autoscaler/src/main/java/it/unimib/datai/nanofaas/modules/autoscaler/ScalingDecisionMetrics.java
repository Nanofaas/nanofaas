package it.unimib.datai.nanofaas.modules.autoscaler;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Publishes what the internal autoscaler decided, so a run can be explained afterwards.
 *
 * <p>The replica counts a run records say where the deployment ended up; they say nothing
 * about why. For the Kubernetes HPA path that gap is filled by kube-state-metrics, which
 * republishes the HPA object's status. The internal scaler has no such object, so a run
 * driven by it had no record of its reasoning at all — and diagnosing one meant inferring
 * decisions from replica counts after the fact.
 *
 * <p>Unlike the HPA, this can publish the recommendation <em>before</em> the clamp:
 * Kubernetes reports only the clamped value, so "it wanted 6" and "it wanted 40" look
 * identical there. Here they do not.
 */
public class ScalingDecisionMetrics {

    private final MeterRegistry registry;
    private final Map<String, Values> byFunction = new ConcurrentHashMap<>();

    public ScalingDecisionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Record a decision, registering the function's gauges on first sight. */
    public void recordDecision(String functionName, ScalingDecision decision) {
        Values values = byFunction.computeIfAbsent(functionName, this::register);
        // Ratios are continuous; the gauges are integers, so the ratio is scaled by 1000
        // rather than rounded away — at target 100 and 365 req/s it is 3.65, which would
        // otherwise be reported as 3 or 4.
        values.ratioMilli.set((int) Math.round(decision.maxRatio() * 1000));
        values.recommended.set(decision.recommendedReplicas());
        values.desired.set(decision.desiredReplicas());
        values.limited.set(decision.limited() ? 1 : 0);
    }

    public void remove(String functionName) {
        Values values = byFunction.remove(functionName);
        if (values != null) {
            values.meterIds.forEach(registry::remove);
        }
    }

    private Values register(String functionName) {
        List<Meter.Id> ids = new ArrayList<>();
        AtomicInteger recommended = new AtomicInteger();
        AtomicInteger desired = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();
        AtomicInteger ratioMilli = new AtomicInteger();

        ids.add(gauge("function_scaling_recommended_replicas", recommended, functionName));
        ids.add(gauge("function_scaling_desired_replicas", desired, functionName));
        ids.add(gauge("function_scaling_limited", limited, functionName));
        ids.add(gauge("function_scaling_ratio_milli", ratioMilli, functionName));

        return new Values(recommended, desired, limited, ratioMilli, ids);
    }

    private Meter.Id gauge(String name, AtomicInteger value, String functionName) {
        return Gauge.builder(name, value, AtomicInteger::get)
                .tag("function", functionName)
                .register(registry)
                .getId();
    }

    private record Values(AtomicInteger recommended,
                          AtomicInteger desired,
                          AtomicInteger limited,
                          AtomicInteger ratioMilli,
                          List<Meter.Id> meterIds) {
    }
}
