package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class QueueManager {
    private static final String FUNCTION_TAG = "function";
    private final Map<String, FunctionQueueState> queues = new ConcurrentHashMap<>();
    private final Map<String, List<Meter.Id>> meterIds = new ConcurrentHashMap<>();
    private final Map<String, DiagnosticMeters> diagnosticMeters = new ConcurrentHashMap<>();
    private final MeterRegistry meterRegistry;
    private final QueueConcurrencyControlMetrics concurrencyMetrics;
    private WorkSignaler workSignaler;

    public QueueManager(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.concurrencyMetrics = new QueueConcurrencyControlMetrics(meterRegistry);
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
                        .tag(FUNCTION_TAG, name)
                        .register(meterRegistry).getId());
                ids.add(Gauge.builder("function_inFlight", state::inFlight)
                        .tag(FUNCTION_TAG, name)
                        .register(meterRegistry).getId());
                ids.add(Gauge.builder("function_effective_concurrency", state::effectiveConcurrency)
                        .tag(FUNCTION_TAG, name)
                        .register(meterRegistry).getId());
                ids.add(Gauge.builder("function_dispatchable_backlog", state::dispatchableBacklog)
                        .tag(FUNCTION_TAG, name)
                        .register(meterRegistry).getId());
                Timer offerDuration = Timer.builder("function_queue_offer_duration")
                        .tag(FUNCTION_TAG, name)
                        .register(meterRegistry);
                Timer pollDuration = Timer.builder("function_queue_poll_duration")
                        .tag(FUNCTION_TAG, name)
                        .register(meterRegistry);
                Timer wakeupDelay = Timer.builder("function_scheduler_wakeup_delay")
                        .tag(FUNCTION_TAG, name)
                        .register(meterRegistry);
                Timer slotHoldDuration = Timer.builder("function_dispatch_slot_hold_duration")
                        .tag(FUNCTION_TAG, name)
                        .register(meterRegistry);
                Counter batchLimit = Counter.builder("function_scheduler_batch_limit")
                        .tag(FUNCTION_TAG, name)
                        .register(meterRegistry);
                ids.add(offerDuration.getId());
                ids.add(pollDuration.getId());
                ids.add(wakeupDelay.getId());
                ids.add(slotHoldDuration.getId());
                ids.add(batchLimit.getId());
                diagnosticMeters.put(
                        name,
                        new DiagnosticMeters(offerDuration, pollDuration, wakeupDelay, slotHoldDuration, batchLimit)
                );
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

    public List<InvocationTask> remove(String name) {
        FunctionQueueState removed = queues.remove(name);
        concurrencyMetrics.remove(name);
        diagnosticMeters.remove(name);
        List<Meter.Id> ids = meterIds.remove(name);
        if (ids != null) {
            ids.forEach(meterRegistry::remove);
        }
        return removed == null ? List.of() : removed.closeAndDrainQueued();
    }

    public void forEachQueue(java.util.function.Consumer<FunctionQueueState> action) {
        queues.values().forEach(action);
    }

    public boolean enqueue(InvocationTask task) {
        FunctionQueueState state = queues.get(task.functionName());
        if (state == null) {
            return false;
        }
        DiagnosticMeters meters = diagnosticMeters.get(task.functionName());
        long started = System.nanoTime();
        boolean success = state.offer(task);
        if (meters != null) {
            meters.offerDuration().record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
        if (success) {
            notifyWork(task.functionName());
        }
        return success;
    }

    void recordQueuePollDuration(String functionName, long durationNanos) {
        DiagnosticMeters meters = diagnosticMeters.get(functionName);
        if (meters != null) {
            meters.pollDuration().record(durationNanos, TimeUnit.NANOSECONDS);
        }
    }

    void recordSchedulerWakeupDelay(String functionName, long delayNanos) {
        DiagnosticMeters meters = diagnosticMeters.get(functionName);
        if (meters != null) {
            meters.wakeupDelay().record(delayNanos, TimeUnit.NANOSECONDS);
        }
    }

    void recordSchedulerBatchLimit(String functionName) {
        DiagnosticMeters meters = diagnosticMeters.get(functionName);
        if (meters != null) {
            meters.batchLimit().increment();
        }
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

    public boolean hasAvailableSlot(String functionName) {
        FunctionQueueState state = queues.get(functionName);
        return state != null && state.inFlight() < state.effectiveConcurrency();
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
            long holdNanos = state.releaseSlotAndGetHoldNanos();
            DiagnosticMeters meters = diagnosticMeters.get(functionName);
            if (holdNanos >= 0 && meters != null) {
                meters.slotHoldDuration().record(holdNanos, TimeUnit.NANOSECONDS);
            }
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

    private record DiagnosticMeters(
            Timer offerDuration,
            Timer pollDuration,
            Timer wakeupDelay,
            Timer slotHoldDuration,
            Counter batchLimit
    ) { }
}
