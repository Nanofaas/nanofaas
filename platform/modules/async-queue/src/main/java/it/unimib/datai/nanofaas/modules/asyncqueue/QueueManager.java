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
import it.unimib.datai.nanofaas.workloadmetrics.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadDiagnostics;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsBinder;

public class QueueManager {
    private static final String FUNCTION_TAG = "function";
    private final Map<String, FunctionQueueState> queues = new ConcurrentHashMap<>();
    private final Map<String, LifecycleLock> lifecycleLocks = new ConcurrentHashMap<>();
    private final Map<String, List<Meter.Id>> meterIds = new ConcurrentHashMap<>();
    private final Map<String, DiagnosticMeters> diagnosticMeters = new ConcurrentHashMap<>();
    private final MeterRegistry meterRegistry;
    private final QueueConcurrencyControlMetrics concurrencyMetrics;
    private final FunctionCapacityRegistry capacityRegistry;
    private final WorkloadDiagnostics workloadDiagnostics;
    private final WorkloadMetricsBinder workloadMetricsBinder;
    private WorkSignaler workSignaler;

    public QueueManager(MeterRegistry meterRegistry) {
        this(meterRegistry, new WorkloadDiagnostics(meterRegistry));
    }

    QueueManager(MeterRegistry meterRegistry, WorkloadDiagnostics workloadDiagnostics) {
        this(meterRegistry, workloadDiagnostics, new FunctionCapacityRegistry());
    }

    QueueManager(MeterRegistry meterRegistry, WorkloadDiagnostics workloadDiagnostics,
                 FunctionCapacityRegistry capacityRegistry) {
        this.meterRegistry = meterRegistry;
        this.concurrencyMetrics = new QueueConcurrencyControlMetrics(meterRegistry);
        this.workloadDiagnostics = workloadDiagnostics;
        this.capacityRegistry = capacityRegistry;
        this.workloadMetricsBinder = new WorkloadMetricsBinder(
                meterRegistry, new AsyncQueueWorkloadMetricsSource(this));
    }

    WorkloadMetricsBinder workloadMetricsBinder() { return workloadMetricsBinder; }

    public void setWorkSignaler(WorkSignaler workSignaler) {
        this.workSignaler = workSignaler;
    }

    private void notifyWork(String functionName) {
        if (workSignaler != null) {
            workSignaler.signalWork(functionName);
        }
    }

    private LifecycleLock acquireLifecycleLock(String functionName) {
        synchronized (lifecycleLocks) {
            LifecycleLock lock = lifecycleLocks.computeIfAbsent(functionName, ignored -> new LifecycleLock());
            lock.users++;
            return lock;
        }
    }

    private void releaseLifecycleLock(String functionName, LifecycleLock lock) {
        synchronized (lifecycleLocks) {
            if (--lock.users == 0 && !queues.containsKey(functionName)) {
                lifecycleLocks.remove(functionName, lock);
            }
        }
    }

    int lifecycleLockCount() {
        return lifecycleLocks.size();
    }

    public FunctionQueueState getOrCreate(FunctionSpec spec) {
        LifecycleLock lifecycleLock = acquireLifecycleLock(spec.name());
        try {
            synchronized (lifecycleLock) {
            return queues.compute(spec.name(), (name, existing) -> {
            if (existing == null) {
                FunctionQueueState state = new FunctionQueueState(
                        name,
                        spec.queueSize(),
                        capacityRegistry.register(name, spec.concurrency())
                );
                List<Meter.Id> ids = new ArrayList<>();
                for (it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind kind :
                        it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind.values()) {
                    ids.add(Gauge.builder("function_queue_depth_by_path", () -> state.queued(kind))
                            .tag(FUNCTION_TAG, name).tag("path", kind.tag())
                            .register(meterRegistry).getId());
                }
                Timer wakeupDelay = Timer.builder("function_scheduler_wakeup_delay")
                        .tag(FUNCTION_TAG, name)
                        .register(meterRegistry);
                Timer pollDelay = Timer.builder("function_scheduler_poll_delay")
                        .tag(FUNCTION_TAG, name)
                        .register(meterRegistry);
                Timer activationBookkeepingDuration = Timer.builder(
                                "function_scheduler_activation_bookkeeping_duration")
                        .tag(FUNCTION_TAG, name)
                        .register(meterRegistry);
                Timer signalEnqueueDuration = Timer.builder("function_scheduler_signal_enqueue_duration")
                        .tag(FUNCTION_TAG, name)
                        .register(meterRegistry);
                Counter batchLimit = Counter.builder("function_scheduler_batch_limit")
                        .tag(FUNCTION_TAG, name)
                        .register(meterRegistry);
                Counter signalCoalesced = Counter.builder("function_scheduler_signal_coalesced")
                        .tag(FUNCTION_TAG, name)
                        .register(meterRegistry);
                ids.add(wakeupDelay.getId());
                ids.add(pollDelay.getId());
                ids.add(activationBookkeepingDuration.getId());
                ids.add(signalEnqueueDuration.getId());
                ids.add(batchLimit.getId());
                ids.add(signalCoalesced.getId());
                workloadDiagnostics.registerFunction(name);
                workloadMetricsBinder.registerFunction(name);
                diagnosticMeters.put(
                        name,
                        new DiagnosticMeters(wakeupDelay, pollDelay, activationBookkeepingDuration,
                                signalEnqueueDuration, batchLimit, signalCoalesced)
                );
                concurrencyMetrics.ensureRegistered(
                        name,
                        resolveMode(spec),
                        resolveTargetInFlightPerPod(spec)
                );
                meterIds.put(name, ids);
                return state;
            }
            capacityRegistry.register(name, spec.concurrency());
            return existing;
            });
            }
        } finally {
            releaseLifecycleLock(spec.name(), lifecycleLock);
        }
    }

    public FunctionQueueState get(String functionName) {
        return queues.get(functionName);
    }

    public List<InvocationTask> remove(String name) {
        LifecycleLock lifecycleLock = acquireLifecycleLock(name);
        try {
            synchronized (lifecycleLock) {
            FunctionQueueState removed = queues.remove(name);
            concurrencyMetrics.remove(name);
            diagnosticMeters.remove(name);
            capacityRegistry.remove(name);
            workloadDiagnostics.removeFunction(name);
            workloadMetricsBinder.removeFunction(name);
            List<Meter.Id> ids = meterIds.remove(name);
            if (ids != null) {
                ids.forEach(meterRegistry::remove);
            }
            return removed == null ? List.of() : removed.closeAndDrainQueued();
            }
        } finally {
            releaseLifecycleLock(name, lifecycleLock);
        }
    }

    private static final class LifecycleLock {
        private int users;
    }

    public void forEachQueue(java.util.function.Consumer<FunctionQueueState> action) {
        queues.values().forEach(action);
    }

    public boolean enqueue(InvocationTask task) {
        FunctionQueueState state = queues.get(task.functionName());
        if (state == null) {
            return false;
        }
        long started = System.nanoTime();
        boolean success = state.offer(task);
        workloadDiagnostics.recordQueueOfferDuration(task.functionName(), System.nanoTime() - started);
        if (success && state.canDispatch()) {
            notifyWork(task.functionName());
        }
        return success;
    }

    void recordQueuePollDuration(String functionName, long durationNanos) {
        workloadDiagnostics.recordQueuePollDuration(functionName, durationNanos);
    }

    void recordSchedulerWakeupDelay(String functionName, long delayNanos) {
        DiagnosticMeters meters = diagnosticMeters.get(functionName);
        if (meters != null) {
            meters.wakeupDelay().record(delayNanos, TimeUnit.NANOSECONDS);
        }
    }

    void recordSchedulerPollDelay(String functionName, long delayNanos) {
        DiagnosticMeters meters = diagnosticMeters.get(functionName);
        if (meters != null) {
            meters.pollDelay().record(delayNanos, TimeUnit.NANOSECONDS);
        }
    }

    void recordSchedulerActivationBookkeepingDuration(String functionName, long durationNanos) {
        DiagnosticMeters meters = diagnosticMeters.get(functionName);
        if (meters != null) {
            meters.activationBookkeepingDuration().record(durationNanos, TimeUnit.NANOSECONDS);
        }
    }

    void recordSchedulerSignalEnqueueDuration(String functionName, long durationNanos) {
        DiagnosticMeters meters = diagnosticMeters.get(functionName);
        if (meters != null) {
            meters.signalEnqueueDuration().record(durationNanos, TimeUnit.NANOSECONDS);
        }
    }

    void recordSchedulerBatchLimit(String functionName) {
        DiagnosticMeters meters = diagnosticMeters.get(functionName);
        if (meters != null) {
            meters.batchLimit().increment();
        }
    }

    void recordSchedulerDispatchSubmitDuration(String functionName, long durationNanos) {
        workloadDiagnostics.recordDispatchSubmitDuration(functionName, durationNanos);
    }

    void recordSchedulerVisitDuration(long durationNanos) {
        workloadDiagnostics.recordSchedulerVisitDuration(durationNanos);
    }

    void recordSchedulerIdleDuration(long durationNanos) {
        workloadDiagnostics.recordSchedulerIdleDuration(durationNanos);
    }

    void recordSchedulerSlotBlocked(String functionName) {
        workloadDiagnostics.recordSchedulerSlotBlocked(functionName);
    }

    void recordSchedulerSignalCoalesced(String functionName) {
        DiagnosticMeters meters = diagnosticMeters.get(functionName);
        if (meters != null) {
            meters.signalCoalesced().increment();
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

    public boolean isQueueFull(String functionName) {
        FunctionQueueState state = queues.get(functionName);
        // Unknown function: let `enqueue` return false and the caller decide.
        return state != null && !state.hasQueueCapacity();
    }

    public boolean hasAvailableSlot(String functionName) {
        FunctionQueueState state = queues.get(functionName);
        return state != null && state.canDispatch();
    }

    public void setEffectiveConcurrency(String functionName, int effectiveConcurrency) {
        capacityRegistry.setEffectiveConcurrency(functionName, effectiveConcurrency);
    }

    public void updateConcurrencyController(String functionName,
                                            ConcurrencyControlMode mode,
                                            int targetInFlightPerPod) {
        concurrencyMetrics.ensureRegistered(functionName, mode, targetInFlightPerPod);
        concurrencyMetrics.update(functionName, mode, targetInFlightPerPod);
    }

    public void releaseSlot(String functionName) {
        long holdNanos = capacityRegistry.releaseSlotAndGetHoldNanos(functionName);
        if (holdNanos >= 0) {
            workloadDiagnostics.recordDispatchSlotHold(functionName, holdNanos);
        }
        FunctionQueueState state = queues.get(functionName);
        if (state != null && state.queued() > 0 && state.canDispatch()) {
            notifyWork(functionName);
        }
    }

    void releaseSlot(String functionName, FunctionQueueState expectedState) {
        long holdNanos = expectedState.releaseSlotAndGetHoldNanos();
        FunctionQueueState currentState = queues.computeIfPresent(functionName, (name, current) -> {
            if (current != expectedState) {
                return current;
            }
            if (holdNanos >= 0) workloadDiagnostics.recordDispatchSlotHold(name, holdNanos);
            return current;
        });
        if (currentState == expectedState
                && expectedState.queued() > 0
                && queues.get(functionName) == expectedState) {
            notifyWork(functionName);
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
            Timer wakeupDelay,
            Timer pollDelay,
            Timer activationBookkeepingDuration,
            Timer signalEnqueueDuration,
            Counter batchLimit,
            Counter signalCoalesced
    ) {}
}
