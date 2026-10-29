package it.unimib.datai.nanofaas.modules.syncqueue.sync;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Map;
import java.util.EnumMap;
import java.util.Locale;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectReason;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import java.util.function.ToIntFunction;

public class SyncQueueMetrics {
    private static final String GLOBAL_FUNCTION_TAG = "";
    private static final String FUNCTION_TAG = "function";

    private final MeterRegistry registry;
    private final Map<String, Counter> rejectedCounters = new ConcurrentHashMap<>();
    private final Map<String, Map<SyncQueueRejectReason, Counter>> reasonCounters = new ConcurrentHashMap<>();
    private final Map<String, Meter.Id> capacityGaugeIds = new ConcurrentHashMap<>();
    private final Map<String, Counter> admittedCounters = new ConcurrentHashMap<>();
    private final Map<String, Meter.Id> perFunctionDepthGaugeIds = new ConcurrentHashMap<>();
    /** The currently registered functions, never a tombstone history of removals. */
    private final Set<String> registeredFunctions = ConcurrentHashMap.newKeySet();
    private final Object functionStateMonitor = new Object();
    /** Live depth readers: gauges call them directly, taking neither the engine gate nor
     * {@link #functionStateMonitor}. */
    private final IntSupplier totalDepthReader;
    private final ToIntFunction<String> functionDepthReader;

    public SyncQueueMetrics(MeterRegistry registry, IntSupplier totalDepthReader,
                            ToIntFunction<String> functionDepthReader) {
        this.registry = Objects.requireNonNull(registry);
        this.totalDepthReader = Objects.requireNonNull(totalDepthReader);
        this.functionDepthReader = Objects.requireNonNull(functionDepthReader);
        Gauge.builder("sync_queue_depth", this, metrics -> metrics.totalDepthReader.getAsInt())
                .tag(FUNCTION_TAG, GLOBAL_FUNCTION_TAG)
                .register(registry);
    }

    public void registerFunction(String functionName, int capacity) {
        synchronized (functionStateMonitor) {
            if (!registeredFunctions.add(functionName)) {
                return;
            }
            Gauge gauge = Gauge.builder("sync_queue_depth", this,
                            metrics -> metrics.functionDepthReader.applyAsInt(functionName))
                    .tag(FUNCTION_TAG, functionName)
                    .register(registry);
            perFunctionDepthGaugeIds.put(functionName, gauge.getId());
            capacityGaugeIds.put(functionName, Gauge.builder("sync_queue_capacity", () -> capacity)
                    .tag(FUNCTION_TAG, functionName).register(registry).getId());
            counter(admittedCounters, "sync_queue_admitted_total", functionName);
            counter(rejectedCounters, "sync_queue_rejected_total", functionName);
            var reasons = new EnumMap<SyncQueueRejectReason, Counter>(SyncQueueRejectReason.class);
            for (var reason : SyncQueueRejectReason.values()) {
                reasons.put(reason, Counter.builder("sync_queue_rejected_by_reason_total")
                        .tags(FUNCTION_TAG, functionName, "reason", reason.name().toLowerCase(Locale.ROOT)).register(registry));
            }
            reasonCounters.put(functionName, reasons);
        }
    }

    public void admitted(String functionName) {
        Counter admitted;
        synchronized (functionStateMonitor) {
            if (!registeredFunctions.contains(functionName)) {
                return;
            }
            admitted = counter(admittedCounters, "sync_queue_admitted_total", functionName);
        }
        admitted.increment();
    }

    public void rejected(String functionName, SyncQueueRejectReason reason) {
        Counter rejected;
        synchronized (functionStateMonitor) {
            if (!registeredFunctions.contains(functionName)) {
                return;
            }
            rejected = counter(rejectedCounters, "sync_queue_rejected_total", functionName);
            reasonCounters.get(functionName).get(reason).increment();
        }
        rejected.increment();
    }

    public void removeFunctionState(String functionName) {
        synchronized (functionStateMonitor) {
            registeredFunctions.remove(functionName);
            var reasons = reasonCounters.remove(functionName);
            if (reasons != null) reasons.values().forEach(this::remove);
            var capacityGauge = capacityGaugeIds.remove(functionName);
            if (capacityGauge != null) registry.remove(capacityGauge);
            Counter rejected = rejectedCounters.remove(functionName);
            Counter admitted = admittedCounters.remove(functionName);
            Meter.Id depthGaugeId = perFunctionDepthGaugeIds.remove(functionName);
            remove(rejected);
            remove(admitted);
            if (depthGaugeId != null) {
                registry.remove(depthGaugeId);
            }
        }
    }

    private Counter counter(Map<String, Counter> map, String name, String function) {
        return map.computeIfAbsent(function, key -> Counter.builder(name)
                .tag(FUNCTION_TAG, function)
                .register(registry));
    }

    private void remove(Meter meter) {
        if (meter != null) {
            registry.remove(meter.getId());
        }
    }
}
