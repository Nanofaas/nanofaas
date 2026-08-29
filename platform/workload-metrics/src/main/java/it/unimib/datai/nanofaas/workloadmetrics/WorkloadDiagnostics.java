package it.unimib.datai.nanofaas.workloadmetrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class WorkloadDiagnostics {
    private final MeterRegistry registry;
    private final Timer schedulerVisitDuration;
    private final Timer schedulerIdleDuration;
    private final Map<String, List<Meter.Id>> meters = new ConcurrentHashMap<>();
    private final Map<String, FunctionMeters> functions = new ConcurrentHashMap<>();

    public WorkloadDiagnostics(MeterRegistry registry) {
        this.registry = registry;
        schedulerVisitDuration = Timer.builder("scheduler_visit_duration").register(registry);
        schedulerIdleDuration = Timer.builder("scheduler_idle_duration").register(registry);
    }

    public void registerFunction(String functionName) {
        functions.computeIfAbsent(functionName, name -> {
            Timer offer = timer("function_queue_offer_duration", name);
            Timer poll = timer("function_queue_poll_duration", name);
            Timer submit = timer("function_scheduler_dispatch_submit_duration", name);
            Counter holdSeconds = counter("function_dispatch_slot_hold_seconds", name, "seconds");
            Counter holdEvents = counter("function_dispatch_slot_hold_events", name, null);
            Counter blocked = counter("function_scheduler_slot_blocked", name, null);
            meters.put(name, List.of(offer.getId(), poll.getId(), submit.getId(),
                    holdSeconds.getId(), holdEvents.getId(), blocked.getId()));
            return new FunctionMeters(offer, poll, submit, holdSeconds, holdEvents, blocked);
        });
    }

    public void removeFunction(String functionName) {
        functions.remove(functionName);
        List<Meter.Id> ids = meters.remove(functionName);
        if (ids != null) ids.forEach(registry::remove);
    }

    public void recordSchedulerVisitDuration(long nanos) { schedulerVisitDuration.record(nanos, TimeUnit.NANOSECONDS); }
    public void recordSchedulerIdleDuration(long nanos) { schedulerIdleDuration.record(nanos, TimeUnit.NANOSECONDS); }
    public void recordQueueOfferDuration(String functionName, long nanos) { if (function(functionName) != null) function(functionName).offer.record(nanos, TimeUnit.NANOSECONDS); }
    public void recordQueuePollDuration(String functionName, long nanos) { if (function(functionName) != null) function(functionName).poll.record(nanos, TimeUnit.NANOSECONDS); }
    public void recordDispatchSubmitDuration(String functionName, long nanos) { if (function(functionName) != null) function(functionName).submit.record(nanos, TimeUnit.NANOSECONDS); }
    public void recordSchedulerDispatchSubmitDuration(String functionName, long nanos) { recordDispatchSubmitDuration(functionName, nanos); }
    public void recordDispatchSlotHold(String functionName, long nanos) {
        FunctionMeters meters = function(functionName);
        if (meters == null) return;
        if (nanos >= 0) {
            meters.holdSeconds.increment(nanos / 1_000_000_000.0);
            meters.holdEvents.increment();
        }
    }
    public void recordSchedulerSlotBlocked(String functionName) { if (function(functionName) != null) function(functionName).blocked.increment(); }
    public void recordDispatchSlotBlocked(String functionName) { recordSchedulerSlotBlocked(functionName); }

    private FunctionMeters function(String name) {
        return functions.get(name);
    }

    private Timer timer(String name, String function) {
        return Timer.builder(name).tag("function", function).register(registry);
    }

    private Counter counter(String name, String function, String unit) {
        var builder = Counter.builder(name).tag("function", function);
        if (unit != null) builder.baseUnit(unit);
        return builder.register(registry);
    }

    private record FunctionMeters(Timer offer, Timer poll, Timer submit,
                                  Counter holdSeconds, Counter holdEvents, Counter blocked) {}
}
