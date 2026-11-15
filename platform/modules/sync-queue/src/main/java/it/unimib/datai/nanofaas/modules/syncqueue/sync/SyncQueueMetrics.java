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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import java.util.function.ToIntFunction;

public class SyncQueueMetrics {
    private static final String GLOBAL_FUNCTION_TAG = "";
    private static final String FUNCTION_TAG = "function";

    private final MeterRegistry registry;
    /** The currently registered functions and their meters, never a tombstone history of
     * removals. The admission hot path reads it lock-free; only registration and removal
     * serialize on {@link #lifecycleMonitor}. */
    private final Map<String, FunctionMeters> functions = new ConcurrentHashMap<>();
    private final Object lifecycleMonitor = new Object();
    /** Live depth readers: gauges call them directly, taking neither the engine gate nor
     * {@link #lifecycleMonitor}. */
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

    /** Idempotent; a repeated call (a spec update replays onRegister) only updates the capacity. */
    public void registerFunction(String functionName, int capacity) {
        synchronized (lifecycleMonitor) {
            FunctionMeters existing = functions.get(functionName);
            if (existing != null) {
                existing.capacity().set(capacity);
                return;
            }
            Meter.Id depthGauge = Gauge.builder("sync_queue_depth", this,
                            metrics -> metrics.functionDepthReader.applyAsInt(functionName))
                    .tag(FUNCTION_TAG, functionName)
                    .register(registry).getId();
            AtomicInteger capacityValue = new AtomicInteger(capacity);
            Meter.Id capacityGauge = Gauge.builder("sync_queue_capacity", capacityValue::get)
                    .tag(FUNCTION_TAG, functionName).register(registry).getId();
            var reasons = new EnumMap<SyncQueueRejectReason, Counter>(SyncQueueRejectReason.class);
            for (var reason : SyncQueueRejectReason.values()) {
                reasons.put(reason, Counter.builder("sync_queue_rejected_by_reason_total")
                        .tags(FUNCTION_TAG, functionName, "reason", reason.name().toLowerCase(Locale.ROOT)).register(registry));
            }
            functions.put(functionName, new FunctionMeters(
                    counter("sync_queue_admitted_total", functionName),
                    counter("sync_queue_rejected_total", functionName),
                    reasons, capacityValue, depthGauge, capacityGauge));
        }
    }

    /** Lock-free. An increment racing a removal lands on an already-removed counter, which is
     * harmless; a call after the removal finds nothing and re-creates nothing. */
    public void admitted(String functionName) {
        FunctionMeters meters = functions.get(functionName);
        if (meters != null) {
            meters.admitted().increment();
        }
    }

    public void rejected(String functionName, SyncQueueRejectReason reason) {
        FunctionMeters meters = functions.get(functionName);
        if (meters != null) {
            meters.rejected().increment();
            meters.rejectedByReason().get(reason).increment();
        }
    }

    public void removeFunctionState(String functionName) {
        synchronized (lifecycleMonitor) {
            FunctionMeters meters = functions.remove(functionName);
            if (meters == null) {
                return;
            }
            meters.rejectedByReason().values().forEach(counter -> registry.remove(counter.getId()));
            registry.remove(meters.capacityGauge());
            registry.remove(meters.rejected().getId());
            registry.remove(meters.admitted().getId());
            registry.remove(meters.depthGauge());
        }
    }

    private Counter counter(String name, String function) {
        return Counter.builder(name)
                .tag(FUNCTION_TAG, function)
                .register(registry);
    }

    private record FunctionMeters(Counter admitted, Counter rejected,
                                  Map<SyncQueueRejectReason, Counter> rejectedByReason,
                                  AtomicInteger capacity, Meter.Id depthGauge, Meter.Id capacityGauge) {
    }
}
