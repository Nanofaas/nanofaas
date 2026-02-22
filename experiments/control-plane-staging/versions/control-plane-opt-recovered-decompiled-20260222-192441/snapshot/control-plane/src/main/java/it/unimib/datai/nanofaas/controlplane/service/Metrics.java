package it.unimib.datai.nanofaas.controlplane.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class Metrics {
    private final MeterRegistry registry;
    private final Map<String, Counter> enqueueCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> dispatchCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> successCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> errorCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> retryCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> timeoutCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> rejectedCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> coldStartCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> warmStartCounters = new ConcurrentHashMap<>();
    private final Map<String, Timer> latencyTimers = new ConcurrentHashMap<>();
    private final Map<String, Timer> initDurationTimers = new ConcurrentHashMap<>();
    private final Map<String, Timer> queueWaitTimers = new ConcurrentHashMap<>();
    private final Map<String, Timer> e2eLatencyTimers = new ConcurrentHashMap<>();

    public Metrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void enqueue(String function) {
        counter(enqueueCounters, "function_enqueue_total", function).increment();
    }

    public void dispatch(String function) {
        counter(dispatchCounters, "function_dispatch_total", function).increment();
    }

    public void success(String function) {
        counter(successCounters, "function_success_total", function).increment();
    }

    public void error(String function) {
        counter(errorCounters, "function_error_total", function).increment();
    }

    public void retry(String function) {
        counter(retryCounters, "function_retry_total", function).increment();
    }

    public void timeout(String function) {
        counter(timeoutCounters, "function_timeout_total", function).increment();
    }

    public void queueRejected(String function) {
        counter(rejectedCounters, "function_queue_rejected_total", function).increment();
    }

    public void coldStart(String function) {
        counter(coldStartCounters, "function_cold_start_total", function).increment();
    }

    public void warmStart(String function) {
        counter(warmStartCounters, "function_warm_start_total", function).increment();
    }

    public Timer latency(String function) {
        return latencyTimers.computeIfAbsent(function, name -> Timer.builder("function_latency_ms")
                .tag("function", name)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry));
    }

    public Timer initDuration(String function) {
        return initDurationTimers.computeIfAbsent(function, name -> Timer.builder("function_init_duration_ms")
                .tag("function", name)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry));
    }

    public Timer queueWait(String function) {
        return queueWaitTimers.computeIfAbsent(function, name -> Timer.builder("function_queue_wait_ms")
                .tag("function", name)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry));
    }

    public Timer e2eLatency(String function) {
        return e2eLatencyTimers.computeIfAbsent(function, name -> Timer.builder("function_e2e_latency_ms")
                .tag("function", name)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry));
    }

    private Counter counter(Map<String, Counter> map, String name, String function) {
        return map.computeIfAbsent(function, key -> Counter.builder(name)
                .tag("function", function)
                .register(registry));
    }
}
