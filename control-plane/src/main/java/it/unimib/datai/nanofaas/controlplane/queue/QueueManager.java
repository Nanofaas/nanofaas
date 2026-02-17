package it.unimib.datai.nanofaas.controlplane.queue;

import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.WorkSignaler;
import it.unimib.datai.nanofaas.controlplane.service.ConcurrencyControlMetrics;
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
    private final ConcurrencyControlMetrics concurrencyMetrics;
    private WorkSignaler workSignaler;

    public QueueManager(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.concurrencyMetrics = new ConcurrencyControlMetrics(meterRegistry);
    }

    public void setWorkSignaler(WorkSignaler workSignaler) {
        this.workSignaler = workSignaler;
    }

    private void notifyWork(String functionName) {
        if (workSignaler != null) {
            workSignaler.signalWork(functionName);
        }
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
                ids.add(Gauge.builder("function_effective_concurrency", state::effectiveConcurrency)
                        .tag("function", name)
                        .register(meterRegistry).getId());
                concurrencyMetrics.ensureRegistered(
                        name,
                        resolveMode(spec),
                        resolveTargetInFlightPerPod(spec)
                );
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
        concurrencyMetrics.remove(name);
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
        boolean success = state.offer(task);
        if (success) {
            notifyWork(task.functionName());
        }
        return success;
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

    public void setEffectiveConcurrency(String functionName, int effectiveConcurrency) {
        FunctionQueueState state = queues.get(functionName);
        if (state != null) {
            state.setEffectiveConcurrency(effectiveConcurrency);
        }
    }

    public void updateConcurrencyController(String functionName,
                                            ConcurrencyControlMode mode,
                                            int targetInFlightPerPod) {
        concurrencyMetrics.ensureRegistered(functionName, mode, targetInFlightPerPod);
        concurrencyMetrics.update(functionName, mode, targetInFlightPerPod);
    }

    public void releaseSlot(String functionName) {
        FunctionQueueState state = queues.get(functionName);
        if (state != null) {
            state.releaseSlot();
            if (state.queued() > 0) {
                notifyWork(functionName);
            }
        }
    }

    private static ConcurrencyControlMode resolveMode(FunctionSpec spec) {
        if (spec.scalingConfig() == null
                || spec.scalingConfig().concurrencyControl() == null
                || spec.scalingConfig().concurrencyControl().mode() == null) {
            return ConcurrencyControlMode.FIXED;
        }
        return spec.scalingConfig().concurrencyControl().mode();
    }

    private static int resolveTargetInFlightPerPod(FunctionSpec spec) {
        if (spec.scalingConfig() == null || spec.scalingConfig().concurrencyControl() == null) {
            return 0;
        }
        Integer target = spec.scalingConfig().concurrencyControl().targetInFlightPerPod();
        return target == null ? 0 : Math.max(0, target);
    }
}
