package it.unimib.datai.nanofaas.workloadmetrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class WorkloadMetricsBinder {
    private static final String FUNCTION_TAG = "function";
    private final MeterRegistry registry;
    private final WorkloadMetricsSource source;
    private final Map<String, List<Meter.Id>> meters = new ConcurrentHashMap<>();

    public WorkloadMetricsBinder(MeterRegistry registry, WorkloadMetricsSource source) {
        this.registry = registry;
        this.source = source;
    }

    public void registerFunction(String functionName) {
        meters.computeIfAbsent(functionName, name -> List.of(
                gauge(WorkloadMetricNames.QUEUE_DEPTH, name, () -> source.queueDepth(name)),
                gauge(WorkloadMetricNames.IN_FLIGHT, name, () -> source.inFlight(name)),
                gauge(WorkloadMetricNames.EFFECTIVE_CONCURRENCY, name, () -> source.effectiveConcurrency(name)),
                gauge(WorkloadMetricNames.DISPATCHABLE_BACKLOG, name, () -> source.dispatchableBacklog(name))));
    }

    public void removeFunction(String functionName) {
        List<Meter.Id> ids = meters.remove(functionName);
        if (ids != null) {
            ids.forEach(registry::remove);
        }
    }

    private Meter.Id gauge(String name, String functionName, java.util.function.Supplier<Number> value) {
        return Gauge.builder(name, value)
                .tag(FUNCTION_TAG, functionName)
                .register(registry)
                .getId();
    }
}
