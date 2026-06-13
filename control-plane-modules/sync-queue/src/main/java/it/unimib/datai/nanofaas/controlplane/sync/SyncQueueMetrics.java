package it.unimib.datai.nanofaas.controlplane.sync;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class SyncQueueMetrics {
    private final MeterRegistry registry;
    private final Map<String, Counter> rejectedCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> timedOutCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> admittedCounters = new ConcurrentHashMap<>();
    private final Map<String, Timer> waitTimers = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> perFunctionDepth = new ConcurrentHashMap<>();
    private final Map<String, Meter.Id> perFunctionDepthGaugeIds = new ConcurrentHashMap<>();
    private final Set<String> removedFunctions = ConcurrentHashMap.newKeySet();
    private final Object functionStateMonitor = new Object();
    private final AtomicInteger globalDepth = new AtomicInteger();
    private final Timer globalWaitTimer;

    public SyncQueueMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("sync_queue_depth", globalDepth, AtomicInteger::get).register(registry);
        this.globalWaitTimer = Timer.builder("sync_queue_wait_seconds").register(registry);
    }

    public void registerFunction(String functionName) {
        synchronized (functionStateMonitor) {
            removedFunctions.remove(functionName);
            getOrCreateDepth(functionName);
        }
    }

    public void admitted(String functionName) {
        Counter admitted;
        AtomicInteger depth;
        synchronized (functionStateMonitor) {
            if (removedFunctions.contains(functionName)) {
                return;
            }
            admitted = counter(admittedCounters, "sync_queue_admitted_total", functionName);
            depth = getOrCreateDepth(functionName);
        }
        admitted.increment();
        globalDepth.incrementAndGet();
        depth.incrementAndGet();
    }

    private AtomicInteger getOrCreateDepth(String functionName) {
        return perFunctionDepth.computeIfAbsent(functionName, name -> {
            AtomicInteger depth = new AtomicInteger();
            Gauge gauge = Gauge.builder("sync_queue_depth", depth, AtomicInteger::get)
                    .tag("function", name)
                    .register(registry);
            perFunctionDepthGaugeIds.put(name, gauge.getId());
            return depth;
        });
    }

    public void dequeued(String functionName) {
        globalDepth.decrementAndGet();
        AtomicInteger depth;
        synchronized (functionStateMonitor) {
            depth = perFunctionDepth.get(functionName);
        }
        if (depth != null) {
            depth.decrementAndGet();
        }
    }

    public void rejected(String functionName) {
        Counter rejected;
        synchronized (functionStateMonitor) {
            if (removedFunctions.contains(functionName)) {
                return;
            }
            rejected = counter(rejectedCounters, "sync_queue_rejected_total", functionName);
        }
        rejected.increment();
    }

    public void timedOut(String functionName) {
        Counter timedOut;
        synchronized (functionStateMonitor) {
            if (removedFunctions.contains(functionName)) {
                return;
            }
            timedOut = counter(timedOutCounters, "sync_queue_timedout_total", functionName);
        }
        timedOut.increment();
    }

    public void recordWait(String functionName, long waitMillis) {
        Timer waitTimer;
        synchronized (functionStateMonitor) {
            if (removedFunctions.contains(functionName)) {
                return;
            }
            waitTimer = waitTimer(functionName);
        }
        globalWaitTimer.record(waitMillis, TimeUnit.MILLISECONDS);
        waitTimer.record(waitMillis, TimeUnit.MILLISECONDS);
    }

    public void removeFunctionState(String functionName) {
        synchronized (functionStateMonitor) {
            removedFunctions.add(functionName);
            Counter rejected = rejectedCounters.remove(functionName);
            Counter timedOut = timedOutCounters.remove(functionName);
            Counter admitted = admittedCounters.remove(functionName);
            Timer waitTimer = waitTimers.remove(functionName);
            perFunctionDepth.remove(functionName);
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
                .tag("function", function)
                .register(registry));
    }

    private Timer waitTimer(String function) {
        return waitTimers.computeIfAbsent(function, key -> Timer.builder("sync_queue_wait_seconds")
                .tag("function", function)
                .register(registry));
    }

    private void remove(Meter meter) {
        if (meter != null) {
            registry.remove(meter.getId());
        }
    }
}
