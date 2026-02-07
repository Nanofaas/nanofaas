package it.unimib.datai.nanofaas.controlplane.queue;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class QueueManager {
    private final Map<String, FunctionQueueState> queues = new ConcurrentHashMap<>();
    private final Map<String, List<Meter.Id>> meterIds = new ConcurrentHashMap<>();
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
                List<Meter.Id> ids = new ArrayList<>();
                ids.add(Gauge.builder("function_queue_depth", state::queued)
                        .tag("function", name)
                        .register(meterRegistry).getId());
                ids.add(Gauge.builder("function_inFlight", state::inFlight)
                        .tag("function", name)
                        .register(meterRegistry).getId());
                meterIds.put(name, ids);
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
        List<Meter.Id> ids = meterIds.remove(name);
        if (ids != null) {
            ids.forEach(meterRegistry::remove);
        }
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
