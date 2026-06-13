package it.unimib.datai.nanofaas.controlplane.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@Component
public class Metrics {
    private final MeterRegistry registry;
    private final Map<String, FunctionMeters> meters = new ConcurrentHashMap<>();
    private final Set<String> removedFunctions = ConcurrentHashMap.newKeySet();
    private final FunctionTimers removedFunctionTimers;
    private final Object functionStateMonitor = new Object();

    public Metrics(MeterRegistry registry) {
        this.registry = registry;
        MeterRegistry removedRegistry = new SimpleMeterRegistry();
        this.removedFunctionTimers = new FunctionTimers(
                Timer.builder("removed_function_latency_ms").register(removedRegistry),
                Timer.builder("removed_function_init_duration_ms").register(removedRegistry),
                Timer.builder("removed_function_queue_wait_ms").register(removedRegistry),
                Timer.builder("removed_function_e2e_latency_ms").register(removedRegistry)
        );
    }

    public void enqueue(String function) {
        FunctionMeters meters = metersOrNull(function);
        if (meters != null) {
            meters.enqueue().increment();
        }
    }

    public void dispatch(String function) {
        FunctionMeters meters = metersOrNull(function);
        if (meters != null) {
            meters.dispatch().increment();
        }
    }

    public void success(String function) {
        FunctionMeters meters = metersOrNull(function);
        if (meters != null) {
            meters.success().increment();
        }
    }

    public void error(String function) {
        FunctionMeters meters = metersOrNull(function);
        if (meters != null) {
            meters.error().increment();
        }
    }

    public void retry(String function) {
        FunctionMeters meters = metersOrNull(function);
        if (meters != null) {
            meters.retry().increment();
        }
    }

    public void timeout(String function) {
        FunctionMeters meters = metersOrNull(function);
        if (meters != null) {
            meters.timeout().increment();
        }
    }

    public void queueRejected(String function) {
        FunctionMeters meters = metersOrNull(function);
        if (meters != null) {
            meters.queueRejected().increment();
        }
    }

    public void coldStart(String function) {
        FunctionMeters meters = metersOrNull(function);
        if (meters != null) {
            meters.coldStart().increment();
        }
    }

    public void warmStart(String function) {
        FunctionMeters meters = metersOrNull(function);
        if (meters != null) {
            meters.warmStart().increment();
        }
    }

    public Timer latency(String function) {
        return timers(function).latency();
    }

    public Timer initDuration(String function) {
        return timers(function).initDuration();
    }

    public Timer queueWait(String function) {
        return timers(function).queueWait();
    }

    public Timer e2eLatency(String function) {
        return timers(function).e2eLatency();
    }

    FunctionTimers timers(String function) {
        FunctionMeters meters = metersOrNull(function);
        if (meters == null) {
            return removedFunctionTimers;
        }
        return meters.timers();
    }

    public void registerFunction(String function) {
        synchronized (functionStateMonitor) {
            removedFunctions.remove(function);
        }
    }

    public void removeFunction(String function) {
        synchronized (functionStateMonitor) {
            removedFunctions.add(function);
            FunctionMeters removed = meters.remove(function);
            if (removed != null) {
                removed.meterIds().forEach(registry::remove);
            }
        }
    }

    private FunctionMeters metersOrNull(String function) {
        synchronized (functionStateMonitor) {
            if (removedFunctions.contains(function)) {
                return null;
            }
            return meters.computeIfAbsent(function, this::registerMeters);
        }
    }

    private FunctionMeters registerMeters(String function) {
        Counter enqueue = counter("function_enqueue_total", function);
        Counter dispatch = counter("function_dispatch_total", function);
        Counter success = counter("function_success_total", function);
        Counter error = counter("function_error_total", function);
        Counter retry = counter("function_retry_total", function);
        Counter timeout = counter("function_timeout_total", function);
        Counter queueRejected = counter("function_queue_rejected_total", function);
        Counter coldStart = counter("function_cold_start_total", function);
        Counter warmStart = counter("function_warm_start_total", function);
        Timer latency = timer("function_latency_ms", function);
        Timer initDuration = timer("function_init_duration_ms", function);
        Timer queueWait = timer("function_queue_wait_ms", function);
        Timer e2eLatency = timer("function_e2e_latency_ms", function);
        return new FunctionMeters(
                enqueue,
                dispatch,
                success,
                error,
                retry,
                timeout,
                queueRejected,
                coldStart,
                warmStart,
                new FunctionTimers(latency, initDuration, queueWait, e2eLatency),
                List.of(
                        enqueue.getId(),
                        dispatch.getId(),
                        success.getId(),
                        error.getId(),
                        retry.getId(),
                        timeout.getId(),
                        queueRejected.getId(),
                        coldStart.getId(),
                        warmStart.getId(),
                        latency.getId(),
                        initDuration.getId(),
                        queueWait.getId(),
                        e2eLatency.getId()
                )
        );
    }

    private Counter counter(String name, String function) {
        return Counter.builder(name).tag("function", function).register(registry);
    }

    private Timer timer(String name, String function) {
        return Timer.builder(name)
                .tag("function", function)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    record FunctionMeters(Counter enqueue, Counter dispatch, Counter success, Counter error,
                          Counter retry, Counter timeout, Counter queueRejected,
                          Counter coldStart, Counter warmStart, FunctionTimers timers,
                          List<Meter.Id> meterIds) {
    }

    record FunctionTimers(Timer latency, Timer initDuration, Timer queueWait, Timer e2eLatency) {
    }
}
