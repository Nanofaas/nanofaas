package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
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
    // The scheduler is a single thread shared by every function, so its own time
    // carries no function tag: these two must sum to the loop's wall clock.
    private final Timer schedulerVisitDuration;
    private final Timer schedulerIdleDuration;
    private WorkSignaler workSignaler;

    public QueueManager(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.concurrencyMetrics = new QueueConcurrencyControlMetrics(meterRegistry);
        this.schedulerVisitDuration = Timer.builder("scheduler_visit_duration")
                .register(meterRegistry);
        this.schedulerIdleDuration = Timer.builder("scheduler_idle_duration")
                .register(meterRegistry);
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
                for (InvocationKind kind : InvocationKind.values()) {
                    ids.add(Gauge.builder("function_queue_depth_by_path", () -> state.queued(kind))
                            .tag(FUNCTION_TAG, name)
                            .tag("path", kind.tag())
                            .register(meterRegistry).getId());
                }
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
                Timer dispatchSubmitDuration = Timer.builder("function_scheduler_dispatch_submit_duration")
                        .tag(FUNCTION_TAG, name)
                        .register(meterRegistry);
                Counter slotHoldSeconds = Counter.builder("function_dispatch_slot_hold_seconds")
                        .baseUnit("seconds")
                        .tag(FUNCTION_TAG, name)
                        .register(meterRegistry);
                Counter slotHoldEvents = Counter.builder("function_dispatch_slot_hold_events")
                        .tag(FUNCTION_TAG, name)
                        .register(meterRegistry);
                Counter batchLimit = Counter.builder("function_scheduler_batch_limit")
                        .tag(FUNCTION_TAG, name)
                        .register(meterRegistry);
                Counter slotBlocked = Counter.builder("function_scheduler_slot_blocked")
                        .tag(FUNCTION_TAG, name)
                        .register(meterRegistry);
                Counter signalCoalesced = Counter.builder("function_scheduler_signal_coalesced")
                        .tag(FUNCTION_TAG, name)
                        .register(meterRegistry);
                ids.add(offerDuration.getId());
                ids.add(pollDuration.getId());
                ids.add(wakeupDelay.getId());
                ids.add(pollDelay.getId());
                ids.add(activationBookkeepingDuration.getId());
                ids.add(signalEnqueueDuration.getId());
                ids.add(dispatchSubmitDuration.getId());
                ids.add(slotHoldSeconds.getId());
                ids.add(slotHoldEvents.getId());
                ids.add(batchLimit.getId());
                ids.add(slotBlocked.getId());
                ids.add(signalCoalesced.getId());
                diagnosticMeters.put(
                        name,
                        new DiagnosticMeters(offerDuration, pollDuration, wakeupDelay, pollDelay,
                                activationBookkeepingDuration, signalEnqueueDuration, dispatchSubmitDuration,
                                slotHoldSeconds, slotHoldEvents, batchLimit, slotBlocked, signalCoalesced)
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
        if (success && state.canDispatch()) {
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
        DiagnosticMeters meters = diagnosticMeters.get(functionName);
        if (meters != null) {
            meters.dispatchSubmitDuration().record(durationNanos, TimeUnit.NANOSECONDS);
        }
    }

    void recordSchedulerVisitDuration(long durationNanos) {
        schedulerVisitDuration.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    void recordSchedulerIdleDuration(long durationNanos) {
        schedulerIdleDuration.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    void recordSchedulerSlotBlocked(String functionName) {
        DiagnosticMeters meters = diagnosticMeters.get(functionName);
        if (meters != null) {
            meters.slotBlocked().increment();
        }
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
            releaseSlot(functionName, state);
        }
    }

    void releaseSlot(String functionName, FunctionQueueState expectedState) {
        long holdNanos = expectedState.releaseSlotAndGetHoldNanos();
        FunctionQueueState currentState = queues.computeIfPresent(functionName, (name, current) -> {
            if (current != expectedState) {
                return current;
            }
            DiagnosticMeters meters = diagnosticMeters.get(name);
            if (holdNanos >= 0 && meters != null) {
                meters.recordSlotHold(holdNanos);
            }
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
            Timer offerDuration,
            Timer pollDuration,
            Timer wakeupDelay,
            Timer pollDelay,
            Timer activationBookkeepingDuration,
            Timer signalEnqueueDuration,
            Timer dispatchSubmitDuration,
            Counter slotHoldSeconds,
            Counter slotHoldEvents,
            Counter batchLimit,
            Counter slotBlocked,
            Counter signalCoalesced
    ) {
        void recordSlotHold(long nanos) {
            slotHoldSeconds.increment(nanos / 1_000_000_000.0);
            slotHoldEvents.increment();
        }
    }
}
