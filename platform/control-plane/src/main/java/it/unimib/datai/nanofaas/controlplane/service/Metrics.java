package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.offload.OffloadMeters;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.capacity.GenerationLifecycle;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadTrigger;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class Metrics implements InvocationObservations, OffloadMeters {
    private static final String FUNCTION_TAG = "function";
    private final MeterRegistry registry;
    private final Map<String, FunctionMeters> meters = new ConcurrentHashMap<>();
    /** Current registrations only; removed names must never become a history cache. */
    private final Set<String> registeredFunctions = ConcurrentHashMap.newKeySet();
    private final FunctionTimers removedFunctionTimers;
    private final Object functionStateMonitor = new Object();
    private final FunctionCapacityRegistry capacityRegistry;
    private final AtomicLong standaloneGeneration = new AtomicLong(1);
    /** Active owners are name-addressable; draining owners are identity-addressable only. */
    private final Map<String, OffloadMeterOwner> activeOffloadOwners = new HashMap<>();

    public Metrics(MeterRegistry registry) {
        this(registry, null);
    }

    @Autowired
    public Metrics(MeterRegistry registry, FunctionCapacityRegistry capacityRegistry) {
        this.registry = registry;
        this.capacityRegistry = capacityRegistry;
        MeterRegistry removedRegistry = new SimpleMeterRegistry();
        this.removedFunctionTimers = new FunctionTimers(
                Timer.builder("removed_function_latency_ms").register(removedRegistry),
                Timer.builder("removed_function_init_duration_ms").register(removedRegistry),
                Timer.builder("removed_function_queue_wait_ms").register(removedRegistry),
                Timer.builder("removed_function_e2e_latency_ms").register(removedRegistry)
        );
    }

    /**
     * Sync and async share one queue per function, so every meter above reports a
     * mixture. These three carry the door the invocation came in by, which is the only
     * way to ask whether async work displaced a caller that was waiting.
     *
     * Deliberately new names rather than a tag on the existing meters: five of those
     * feed control loops - function_latency_ms and function_e2e_latency_ms steer the
     * concurrency governor, function_dispatch_total the autoscaler, function_inFlight
     * and function_queue_depth the Kubernetes HPA - and splitting a series a control
     * loop reads changes what that loop sees.
     */
    public void admitted(String function, InvocationKind kind) {
        pathMeters(function, kind, PathMeters::admitted);
    }

    /** Refused at admission: a full queue, whether caught early or by `offer`. */
    public void refused(String function, InvocationKind kind) {
        pathMeters(function, kind, PathMeters::refused);
    }

    /** An idempotency key that found an execution already on file. */
    public void replayed(String function, InvocationKind kind) {
        pathMeters(function, kind, PathMeters::replayed);
    }

    private void pathMeters(String function, InvocationKind kind,
                            java.util.function.Function<PathMeters, Counter> pick) {
        FunctionMeters metersFor = metersOrNull(function);
        if (metersFor != null) {
            pick.apply(metersFor.byPath().get(kind)).increment();
        }
    }

    public void enqueue(String function) {
        FunctionMeters metersFor = metersOrNull(function);
        if (metersFor != null) {
            metersFor.enqueue().increment();
        }
    }

    public void dispatch(String function) {
        FunctionMeters metersFor = metersOrNull(function);
        if (metersFor != null) {
            metersFor.dispatch().increment();
        }
    }

    public void success(String function) {
        FunctionMeters metersFor = metersOrNull(function);
        if (metersFor != null) {
            metersFor.success().increment();
        }
    }

    public void error(String function) {
        FunctionMeters metersFor = metersOrNull(function);
        if (metersFor != null) {
            metersFor.error().increment();
        }
    }

    public void retry(String function) {
        FunctionMeters metersFor = metersOrNull(function);
        if (metersFor != null) {
            metersFor.retry().increment();
        }
    }

    public void timeout(String function) {
        FunctionMeters metersFor = metersOrNull(function);
        if (metersFor != null) {
            metersFor.timeout().increment();
        }
    }

    public void queueRejected(String function) {
        FunctionMeters metersFor = metersOrNull(function);
        if (metersFor != null) {
            metersFor.queueRejected().increment();
        }
    }

    public void coldStart(String function) {
        FunctionMeters metersFor = metersOrNull(function);
        if (metersFor != null) {
            metersFor.coldStart().increment();
        }
    }

    public void warmStart(String function) {
        FunctionMeters metersFor = metersOrNull(function);
        if (metersFor != null) {
            metersFor.warmStart().increment();
        }
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
        FunctionMeters metersFor = metersOrNull(function);
        if (metersFor == null) {
            return removedFunctionTimers;
        }
        return metersFor.timers();
    }

    @Override
    public Snapshot snapshot(String function) {
        synchronized (functionStateMonitor) {
            FunctionMeters current = meters.get(function);
            OffloadMeterOwner owner = activeOffloadOwners.get(function);
            if (current == null || owner == null) return Snapshot.absent();
            if (capacityRegistry != null && !owner.generation().equals(capacityRegistry.activeGeneration(function))) {
                return Snapshot.absent();
            }
            FunctionTimers timers = current.timers();
            return new Snapshot(owner.generation(),
                    new DurationTotals(timers.latency().count(), timers.latency().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)),
                    new DurationTotals(timers.e2eLatency().count(), timers.e2eLatency().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)),
                    current.dispatch().count());
        }
    }

    public void registerFunction(String function) {
        synchronized (functionStateMonitor) {
            FunctionGeneration generation = generationForRegistration(function);
            OffloadMeterOwner previous = activeOffloadOwners.get(function);
            if (previous == null || !previous.generation().equals(generation)) {
                if (previous != null) {
                    retireOffloadOwner(previous);
                }
                activeOffloadOwners.put(function, new OffloadMeterOwner(generation));
            }
            registeredFunctions.add(function);
            metersOrNull(function);
        }
    }

    public void removeFunction(String function) {
        synchronized (functionStateMonitor) {
            registeredFunctions.remove(function);
            FunctionMeters removed = meters.remove(function);
            if (removed != null) {
                removed.meterIds().forEach(registry::remove);
            }
            OffloadMeterOwner owner = activeOffloadOwners.remove(function);
            if (owner != null) {
                retireOffloadOwner(owner);
            }
        }
    }

    /**
     * Captures the owner for one offload before Reactor subscribes. The returned lease is
     * generation-bound, so a callback that outlives remove/re-register can only release its old
     * owner; it cannot look the name up again and mutate the replacement's meters.
     */
    public OffloadMeterLease offloadMeters(String function, OffloadTrigger trigger) {
        synchronized (functionStateMonitor) {
            return new OffloadMeterLease(activeOffloadOwners.get(function), trigger);
        }
    }

    private FunctionGeneration generationForRegistration(String function) {
        if (capacityRegistry != null) {
            FunctionGeneration active = capacityRegistry.activeGeneration(function);
            if (active != null) {
                return active;
            }
        }
        OffloadMeterOwner existing = activeOffloadOwners.get(function);
        return existing != null ? existing.generation()
                : new FunctionGeneration(function, standaloneGeneration.getAndIncrement());
    }

    private void retireOffloadOwner(OffloadMeterOwner owner) {
        // Meter identity cannot include the internal generation. Remove the series immediately:
        // a new registration may use the same public function tag while an old remote call drains.
        owner.removeMeters();
        owner.lifecycle().retire();
    }

    /**
     * A function's meters, or {@code null} if it has been removed.
     *
     * <p>The common case — an already registered, live function — takes no lock. It used to
     * take one always, and it was a GLOBAL monitor shared by every function, on a path each
     * invocation crosses six times (dispatch, outcome, three timers, and their lookup).
     * Measured: 223 ns per operation on one thread, 2,638 ns on eight — the per-operation cost
     * grew with the thread count instead of staying flat, which is the signature of a
     * serialization, not of a cost
     * (docs/experiments/control-plane-tuning-2026-09/RESULTS.md).
     *
     * <p>The lock stays on the slow path, where it is actually needed: first registration and
     * the race with {@link #removeFunction}. The invariant it protects — a removed function
     * does not re-register its meters — still holds, because only an explicit registration adds
     * the name to {@code registeredFunctions}. A fast read that grabs the meters an instant before removal increments a counter
     * about to be deregistered: that sample is lost, and it is an acceptable price for not
     * serializing every invocation on the platform.
     */
    private FunctionMeters metersOrNull(String function) {
        FunctionMeters registered = meters.get(function);
        if (registered != null) {
            return registered;
        }
        synchronized (functionStateMonitor) {
            if (!registeredFunctions.contains(function)) {
                return null;
            }
            return meters.computeIfAbsent(function, this::registerMeters);
        }
    }

    private FunctionMeters registerMeters(String function) {
        Counter enqueue = counter("function_enqueue_total", function);
        Counter dispatch = counter("function_dispatch_total", function);
        Counter success = counter("function_success_total", function);
        Counter error = counter("function_error_total", function);
        Counter retry = counter("function_retry_total", function);
        Counter timeout = counter("function_timeout_total", function);
        Counter queueRejected = counter("function_queue_rejected_total", function);
        Counter coldStart = counter("function_cold_start_total", function);
        Counter warmStart = counter("function_warm_start_total", function);
        Timer latency = timer("function_latency_ms", function);
        Timer initDuration = timer("function_init_duration_ms", function);
        Timer queueWait = timer("function_queue_wait_ms", function);
        Timer e2eLatency = timer("function_e2e_latency_ms", function);
        Map<InvocationKind, PathMeters> byPath = new EnumMap<>(InvocationKind.class);
        for (InvocationKind kind : InvocationKind.values()) {
            byPath.put(kind, new PathMeters(
                    pathCounter("function_admitted_total", function, kind),
                    pathCounter("function_refused_total", function, kind),
                    pathCounter("function_replayed_total", function, kind)));
        }
        return new FunctionMeters(
                enqueue,
                dispatch,
                success,
                error,
                retry,
                timeout,
                queueRejected,
                coldStart,
                warmStart,
                new FunctionTimers(latency, initDuration, queueWait, e2eLatency),
                byPath,
                concat(byPath, List.of(
                        enqueue.getId(),
                        dispatch.getId(),
                        success.getId(),
                        error.getId(),
                        retry.getId(),
                        timeout.getId(),
                        queueRejected.getId(),
                        coldStart.getId(),
                        warmStart.getId(),
                        latency.getId(),
                        initDuration.getId(),
                        queueWait.getId(),
                        e2eLatency.getId()
                ))
        );
    }

    /** Registered ids travel with the function so a delete removes these too. */
    private static List<Meter.Id> concat(Map<InvocationKind, PathMeters> byPath, List<Meter.Id> base) {
        List<Meter.Id> all = new java.util.ArrayList<>(base);
        byPath.values().forEach(path -> {
            all.add(path.admitted().getId());
            all.add(path.refused().getId());
            all.add(path.replayed().getId());
        });
        return List.copyOf(all);
    }

    private Counter pathCounter(String name, String function, InvocationKind kind) {
        return Counter.builder(name)
                .tag(FUNCTION_TAG, function)
                .tag("path", kind.tag())
                .register(registry);
    }

    private Counter counter(String name, String function) {
        return Counter.builder(name).tag(FUNCTION_TAG, function).register(registry);
    }

    private Timer timer(String name, String function) {
        return Timer.builder(name)
                .tag(FUNCTION_TAG, function)
                .register(registry);
    }

    record FunctionMeters(Counter enqueue, Counter dispatch, Counter success, Counter error,
                          Counter retry, Counter timeout, Counter queueRejected,
                          Counter coldStart, Counter warmStart, FunctionTimers timers,
                          Map<InvocationKind, PathMeters> byPath,
                          List<Meter.Id> meterIds) {
    }

    record PathMeters(Counter admitted, Counter refused, Counter replayed) {
    }

    record FunctionTimers(Timer latency, Timer initDuration, Timer queueWait, Timer e2eLatency) {
    }

    /** Lifecycle handle for lazy offload counters; it deliberately exposes no generation tag. */
    public final class OffloadMeterLease implements OffloadMeters.OffloadMeterLease {
        private final OffloadMeterOwner owner;
        private final OffloadTrigger trigger;
        private boolean subscribed;

        private OffloadMeterLease(OffloadMeterOwner owner, OffloadTrigger trigger) {
            this.owner = owner;
            this.trigger = trigger;
        }

        /** Called from Reactor's subscribe callback: only an active owner can create the meter. */
        public void subscribed() {
            synchronized (functionStateMonitor) {
                if (owner == null || subscribed || !owner.lifecycle().retain()) {
                    return;
                }
                subscribed = true;
                owner.offload(trigger).increment();
            }
        }

        /** A retired owner intentionally ignores a late error rather than reviving a public series. */
        public void failed() {
            synchronized (functionStateMonitor) {
                if (owner != null && subscribed && owner.lifecycle().isActive()) {
                    owner.failure().increment();
                }
            }
        }

        @Override
        public void close() {
            synchronized (functionStateMonitor) {
                if (owner != null && subscribed) {
                    subscribed = false;
                    closeOwner();
                }
            }
        }

        private void closeOwner() {
            if (owner.lifecycle().release()) {
                owner.removeMeters();
            }
        }
    }

    private final class OffloadMeterOwner {
        private final FunctionGeneration generation;
        private final GenerationLifecycle lifecycle = new GenerationLifecycle();
        private final Map<OffloadTrigger, Counter> offloads = new EnumMap<>(OffloadTrigger.class);
        private final List<Meter.Id> meterIds = new ArrayList<>();
        private Counter failure;

        private OffloadMeterOwner(FunctionGeneration generation) {
            this.generation = generation;
        }

        private FunctionGeneration generation() {
            return generation;
        }

        private GenerationLifecycle lifecycle() {
            return lifecycle;
        }

        private Counter offload(OffloadTrigger trigger) {
            return offloads.computeIfAbsent(trigger, ignored -> {
                Counter counter = Counter.builder("nanofaas.offload")
                        .tag(FUNCTION_TAG, generation.functionName())
                        .tag("trigger", trigger.name().toLowerCase(java.util.Locale.ROOT))
                        .register(registry);
                meterIds.add(counter.getId());
                return counter;
            });
        }

        private Counter failure() {
            if (failure == null) {
                failure = Counter.builder("nanofaas.offload.failure")
                        .tag(FUNCTION_TAG, generation.functionName())
                        .register(registry);
                meterIds.add(failure.getId());
            }
            return failure;
        }

        private void removeMeters() {
            meterIds.forEach(registry::remove);
            meterIds.clear();
            offloads.clear();
            failure = null;
        }
    }
}
