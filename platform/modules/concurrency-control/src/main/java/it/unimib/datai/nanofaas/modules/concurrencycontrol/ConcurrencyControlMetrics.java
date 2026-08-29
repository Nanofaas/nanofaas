package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Publishes what the concurrency controller decided: the per-replica target it is aiming at, and
 * which mode produced it.
 *
 * <p>Registration happens on the first {@link #update} for a function rather than through a
 * separate priming call. A priming call is a call somebody has to remember to make, and the one
 * this class used to have was left behind by a refactor: the gauges stopped reaching the registry
 * while every controller kept reporting into it.
 */
final class ConcurrencyControlMetrics {
    private final MeterRegistry registry;
    private final Map<String, Values> byFunction = new ConcurrentHashMap<>();

    ConcurrencyControlMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Record a decision, registering the function's gauges on first sight. */
    void update(String functionName, ConcurrencyControlMode mode, int targetInFlightPerPod) {
        Values values = byFunction.computeIfAbsent(functionName, this::register);
        values.target().set(Math.max(0, targetInFlightPerPod));
        values.byMode().forEach((candidate, flag) -> flag.set(candidate == mode ? 1 : 0));
    }

    void remove(String functionName) {
        Values values = byFunction.remove(functionName);
        if (values != null) {
            values.meterIds().forEach(registry::remove);
        }
    }

    private Values register(String functionName) {
        List<Meter.Id> ids = new ArrayList<>();
        AtomicInteger target = new AtomicInteger();
        ids.add(Gauge.builder("function_target_inflight_per_pod", target, AtomicInteger::get)
                .tag("function", functionName)
                .register(registry).getId());

        Map<ConcurrencyControlMode, AtomicInteger> byMode = new EnumMap<>(ConcurrencyControlMode.class);
        for (ConcurrencyControlMode candidate : ConcurrencyControlMode.values()) {
            AtomicInteger flag = new AtomicInteger();
            byMode.put(candidate, flag);
            ids.add(Gauge.builder("function_concurrency_controller_mode", flag, AtomicInteger::get)
                    .tag("function", functionName)
                    .tag("mode", candidate.name())
                    .register(registry).getId());
        }
        return new Values(target, byMode, ids);
    }

    private record Values(AtomicInteger target,
                          Map<ConcurrencyControlMode, AtomicInteger> byMode,
                          List<Meter.Id> meterIds) { }
}
