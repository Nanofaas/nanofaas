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
    private final Map<String, FunctionMeters> meters = new ConcurrentHashMap<>();

    public Metrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void enqueue(String function) {
        meters(function).enqueue().increment();
    }

    public void dispatch(String function) {
        meters(function).dispatch().increment();
    }

    public void success(String function) {
        meters(function).success().increment();
    }

    public void error(String function) {
        meters(function).error().increment();
    }

    public void retry(String function) {
        meters(function).retry().increment();
    }

    public void timeout(String function) {
        meters(function).timeout().increment();
    }

    public void queueRejected(String function) {
        meters(function).queueRejected().increment();
    }

    public void coldStart(String function) {
        meters(function).coldStart().increment();
    }

    public void warmStart(String function) {
        meters(function).warmStart().increment();
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
        return meters(function).timers();
    }

    private FunctionMeters meters(String function) {
        return meters.computeIfAbsent(function, this::registerMeters);
    }

    private FunctionMeters registerMeters(String function) {
        return new FunctionMeters(
                counter("function_enqueue_total", function),
                counter("function_dispatch_total", function),
                counter("function_success_total", function),
                counter("function_error_total", function),
                counter("function_retry_total", function),
                counter("function_timeout_total", function),
                counter("function_queue_rejected_total", function),
                counter("function_cold_start_total", function),
                counter("function_warm_start_total", function),
                new FunctionTimers(
                        timer("function_latency_ms", function),
                        timer("function_init_duration_ms", function),
                        timer("function_queue_wait_ms", function),
                        timer("function_e2e_latency_ms", function)
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
                          Counter coldStart, Counter warmStart, FunctionTimers timers) {
    }

    record FunctionTimers(Timer latency, Timer initDuration, Timer queueWait, Timer e2eLatency) {
    }
}
