package it.unimib.datai.nanofaas.controlplane.config;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

final class JfrGcMetrics {

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Operation> operations = new ConcurrentHashMap<>();

    JfrGcMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void record(String operationName, Duration duration) {
        Operation operation = operations.computeIfAbsent(operationName, this::register);
        operation.count.incrementAndGet();
        operation.totalNanos.addAndGet(duration.toNanos());
    }

    private Operation register(String operationName) {
        Operation operation = new Operation();
        FunctionCounter.builder("nanofaas_jfr_gc_vm_operation_count", operation, value -> value.count.get())
                .tag("operation", operationName)
                .description("JFR VM operations; these are not GC collection counters")
                .register(registry);
        FunctionCounter.builder(
                        "nanofaas_jfr_gc_vm_operation_time",
                        operation,
                        value -> value.totalNanos.get() / 1_000_000_000.0)
                .tag("operation", operationName)
                .baseUnit("seconds")
                .description("JFR VM-operation duration; overlapping operations are not summed as GC time")
                .register(registry);
        return operation;
    }

    private static final class Operation {
        private final AtomicLong count = new AtomicLong();
        private final AtomicLong totalNanos = new AtomicLong();
    }
}
