package it.unimib.datai.nanofaas.modules.syncqueue.sync;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Map;
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

    public void registerFunction(String functionName) {
        synchronized (functionStateMonitor) {
            if (!registeredFunctions.add(functionName)) {
                return;
            }
            Gauge gauge = Gauge.builder("sync_queue_depth", this,
                            metrics -> metrics.functionDepthReader.applyAsInt(functionName))
                    .tag(FUNCTION_TAG, functionName)
                    .register(registry);
            perFunctionDepthGaugeIds.put(functionName, gauge.getId());
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

    public void rejected(String functionName) {
        Counter rejected;
        synchronized (functionStateMonitor) {
            if (!registeredFunctions.contains(functionName)) {
                return;
            }
            rejected = counter(rejectedCounters, "sync_queue_rejected_total", functionName);
        }
        rejected.increment();
    }

    public void removeFunctionState(String functionName) {
        synchronized (functionStateMonitor) {
            registeredFunctions.remove(functionName);
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
