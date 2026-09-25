package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.capacity.DispatchOwnership;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Real generation-bound ownership for fixtures which drive completion without a scheduler. */
final class TestDispatchOwnership {
    private final FunctionCapacityRegistry registry = new FunctionCapacityRegistry();
    private final ConcurrentHashMap<String, DispatchOwnership> attempts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> releases = new ConcurrentHashMap<>();

    InvocationTask acquire(InvocationTask task) {
        return task.withDispatchLease(attempts.computeIfAbsent(task.executionId() + ":" + task.attempt(), ignored -> {
            registry.register(task.functionName(), task.functionSpec().concurrency());
            var lease = registry.tryAcquireLease(registry.activeGeneration(task.functionName()),
                    held -> releases.computeIfAbsent(task.functionName(), key -> new AtomicInteger()).incrementAndGet());
            if (lease == null) throw new AssertionError("Fixture exhausted actual dispatch capacity");
            return lease;
        }));
    }

    void attach(ExecutionRecord executionRecord) { executionRecord.attachDispatchLease(acquire(executionRecord.task()).dispatchLease()); }
    int releases(String name) { return releases.getOrDefault(name, new AtomicInteger()).get(); }
    int releases() { return releases.values().stream().mapToInt(AtomicInteger::get).sum(); }
}
