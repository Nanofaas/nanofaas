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

final class ConcurrencyControlMetrics {
    private final MeterRegistry registry;
    private final Object legacySink;
    private final Map<String, AtomicInteger> targetValues = new ConcurrentHashMap<>();
    private final Map<String, Map<ConcurrencyControlMode, AtomicInteger>> modeValues = new ConcurrentHashMap<>();
    private final Map<String, List<Meter.Id>> meterIds = new ConcurrentHashMap<>();

    ConcurrencyControlMetrics(MeterRegistry registry) {
        this(registry, null);
    }

    ConcurrencyControlMetrics(MeterRegistry registry, Object legacySink) {
        this.registry = registry;
        this.legacySink = legacySink;
    }

    void ensureRegistered(String functionName, ConcurrencyControlMode mode, int targetInFlightPerPod) {
        if (meterIds.containsKey(functionName)) {
            update(functionName, mode, targetInFlightPerPod);
            return;
        }

        List<Meter.Id> ids = new ArrayList<>();
        AtomicInteger target = new AtomicInteger(Math.max(0, targetInFlightPerPod));
        targetValues.put(functionName, target);
        ids.add(Gauge.builder("function_target_inflight_per_pod", target, AtomicInteger::get)
                .tag("function", functionName)
                .register(registry).getId());

        Map<ConcurrencyControlMode, AtomicInteger> byMode = new EnumMap<>(ConcurrencyControlMode.class);
        for (ConcurrencyControlMode candidate : ConcurrencyControlMode.values()) {
            AtomicInteger flag = new AtomicInteger(candidate == mode ? 1 : 0);
            byMode.put(candidate, flag);
            ids.add(Gauge.builder("function_concurrency_controller_mode", flag, AtomicInteger::get)
                    .tag("function", functionName)
                    .tag("mode", candidate.name())
                    .register(registry).getId());
        }
        modeValues.put(functionName, byMode);
        meterIds.put(functionName, ids);
    }

    void update(String functionName, ConcurrencyControlMode mode, int targetInFlightPerPod) {
        targetValues.computeIfAbsent(functionName, ignored -> new AtomicInteger(0))
                .set(Math.max(0, targetInFlightPerPod));
        Map<ConcurrencyControlMode, AtomicInteger> byMode = modeValues.get(functionName);
        if (byMode == null) {
            notifyLegacySink(functionName, mode, targetInFlightPerPod);
            return;
        }
        for (Map.Entry<ConcurrencyControlMode, AtomicInteger> entry : byMode.entrySet()) {
            entry.getValue().set(entry.getKey() == mode ? 1 : 0);
        }
        notifyLegacySink(functionName, mode, targetInFlightPerPod);
    }

    private void notifyLegacySink(String functionName, ConcurrencyControlMode mode, int target) {
        if (legacySink == null) return;
        try {
            var method = legacySink.getClass().getDeclaredMethod(
                    "updateConcurrencyController", String.class, ConcurrencyControlMode.class, int.class);
            method.setAccessible(true);
            method.invoke(legacySink, functionName, mode, target);
        } catch (ReflectiveOperationException ignored) {
            // Compatibility sink exists only in pre-migration tests.
        }
    }

    void remove(String functionName) {
        targetValues.remove(functionName);
        modeValues.remove(functionName);
        List<Meter.Id> ids = meterIds.remove(functionName);
        if (ids != null) {
            ids.forEach(registry::remove);
        }
    }
}
