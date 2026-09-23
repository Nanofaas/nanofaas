package it.unimib.datai.nanofaas.modules.syncqueue.sync;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.function.ToIntFunction;

public class SyncQueueMetrics {
    private static final String GLOBAL_FUNCTION_TAG = "";
    private static final String FUNCTION_TAG = "function";

    private final MeterRegistry registry;
    private final Map<String, Counter> rejectedCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> timedOutCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> admittedCounters = new ConcurrentHashMap<>();
    private final Map<String, Timer> waitTimers = new ConcurrentHashMap<>();
    private final Map<String, Meter.Id> perFunctionDepthGaugeIds = new ConcurrentHashMap<>();
    /** The currently registered functions, never a tombstone history of removals. */
    private final Set<String> registeredFunctions = ConcurrentHashMap.newKeySet();
    private final Object functionStateMonitor = new Object();
    private final Timer globalWaitTimer;
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
        this.globalWaitTimer = Timer.builder("sync_queue_wait_seconds")
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

    public void timedOut(String functionName) {
        Counter timedOut;
        synchronized (functionStateMonitor) {
            if (!registeredFunctions.contains(functionName)) {
                return;
            }
            timedOut = counter(timedOutCounters, "sync_queue_timedout_total", functionName);
        }
        timedOut.increment();
    }

    public void recordWait(String functionName, long waitMillis) {
        Timer waitTimer;
        synchronized (functionStateMonitor) {
            if (!registeredFunctions.contains(functionName)) {
                return;
            }
            waitTimer = waitTimer(functionName);
        }
        globalWaitTimer.record(waitMillis, TimeUnit.MILLISECONDS);
        waitTimer.record(waitMillis, TimeUnit.MILLISECONDS);
    }

    public void removeFunctionState(String functionName) {
        synchronized (functionStateMonitor) {
            registeredFunctions.remove(functionName);
            Counter rejected = rejectedCounters.remove(functionName);
            Counter timedOut = timedOutCounters.remove(functionName);
            Counter admitted = admittedCounters.remove(functionName);
            Timer waitTimer = waitTimers.remove(functionName);
            Meter.Id depthGaugeId = perFunctionDepthGaugeIds.remove(functionName);
            remove(rejected);
            remove(timedOut);
            remove(admitted);
            remove(waitTimer);
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

    private Timer waitTimer(String function) {
        return waitTimers.computeIfAbsent(function, key -> Timer.builder("sync_queue_wait_seconds")
                .tag(FUNCTION_TAG, function)
                .register(registry));
    }

    private void remove(Meter meter) {
        if (meter != null) {
            registry.remove(meter.getId());
        }
    }
}
