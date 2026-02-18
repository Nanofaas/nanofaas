package it.unimib.datai.nanofaas.controlplane.sync;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Map;
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
    private final AtomicInteger globalDepth = new AtomicInteger();
    private final Timer globalWaitTimer;

    public SyncQueueMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("sync_queue_depth", globalDepth, AtomicInteger::get).register(registry);
        this.globalWaitTimer = Timer.builder("sync_queue_wait_seconds").register(registry);
    }

    public void registerFunction(String functionName) {
        getOrCreateDepth(functionName);
    }

    public void admitted(String functionName) {
        counter(admittedCounters, "sync_queue_admitted_total", functionName).increment();
        globalDepth.incrementAndGet();
        getOrCreateDepth(functionName).incrementAndGet();
    }

    private AtomicInteger getOrCreateDepth(String functionName) {
        return perFunctionDepth.computeIfAbsent(functionName, name -> {
            AtomicInteger depth = new AtomicInteger();
            Gauge.builder("sync_queue_depth", depth, AtomicInteger::get)
                    .tag("function", name)
                    .register(registry);
            return depth;
        });
    }

    public void dequeued(String functionName) {
        globalDepth.decrementAndGet();
        AtomicInteger depth = perFunctionDepth.get(functionName);
        if (depth != null) {
            depth.decrementAndGet();
        }
    }

    public void rejected(String functionName) {
        counter(rejectedCounters, "sync_queue_rejected_total", functionName).increment();
    }

    public void timedOut(String functionName) {
        counter(timedOutCounters, "sync_queue_timedout_total", functionName).increment();
    }

    public void recordWait(String functionName, long waitMillis) {
        globalWaitTimer.record(waitMillis, TimeUnit.MILLISECONDS);
        waitTimer(functionName).record(waitMillis, TimeUnit.MILLISECONDS);
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
}
