package it.unimib.datai.mcfaas.controlplane.queue;

import it.unimib.datai.mcfaas.common.model.FunctionSpec;
import it.unimib.datai.mcfaas.controlplane.scheduler.InvocationTask;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class QueueManager {
    private final Map<String, FunctionQueueState> queues = new ConcurrentHashMap<>();
    private final MeterRegistry meterRegistry;

    public QueueManager(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public FunctionQueueState getOrCreate(FunctionSpec spec) {
        return queues.compute(spec.name(), (name, existing) -> {
            if (existing == null) {
                FunctionQueueState state = new FunctionQueueState(
                        name,
                        spec.queueSize(),
                        spec.concurrency()
                );
                Gauge.builder("function_queue_depth", state::queued)
                        .tag("function", name)
                        .register(meterRegistry);
                Gauge.builder("function_inFlight", state::inFlight)
                        .tag("function", name)
                        .register(meterRegistry);
                return state;
            }
            existing.concurrency(spec.concurrency());
            return existing;
        });
    }

    public FunctionQueueState get(String functionName) {
        return queues.get(functionName);
    }

    public void remove(String name) {
        queues.remove(name);
    }

    public void forEachQueue(java.util.function.Consumer<FunctionQueueState> action) {
        queues.values().forEach(action);
    }

    public boolean enqueue(InvocationTask task) {
        FunctionQueueState state = queues.get(task.functionName());
        if (state == null) {
            return false;
        }
        return state.offer(task);
    }

    public void incrementInFlight(String functionName) {
        FunctionQueueState state = queues.get(functionName);
        if (state != null) {
            state.incrementInFlight();
        }
    }

    public void decrementInFlight(String functionName) {
        FunctionQueueState state = queues.get(functionName);
        if (state != null) {
            state.decrementInFlight();
        }
    }

    public boolean tryAcquireSlot(String functionName) {
        FunctionQueueState state = queues.get(functionName);
        return state != null && state.tryAcquireSlot();
    }

    public void releaseSlot(String functionName) {
        FunctionQueueState state = queues.get(functionName);
        if (state != null) {
            state.releaseSlot();
        }
    }
}
