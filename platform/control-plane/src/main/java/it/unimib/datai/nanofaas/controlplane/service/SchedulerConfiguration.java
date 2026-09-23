package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.capacity.CapacityView;
import it.unimib.datai.nanofaas.controlplane.capacity.DispatchCapacity;
import it.unimib.datai.nanofaas.controlplane.capacity.DispatchOwnership;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.capacity.InvocationQuotaExceededException;
import it.unimib.datai.nanofaas.controlplane.config.SchedulerProperties;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistrationListener;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationDispatch;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.QueueLifecycle;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerControl;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingStrategy;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingTicket;
import it.unimib.datai.nanofaas.controlplane.scheduler.TicketId;
import it.unimib.datai.nanofaas.controlplane.service.EngineInvocationEnqueuer.AdmissionProfile;
import it.unimib.datai.nanofaas.execution.EngineDispatch;
import it.unimib.datai.nanofaas.execution.EngineReadiness;
import it.unimib.datai.nanofaas.execution.PendingWorkStore;
import it.unimib.datai.nanofaas.execution.SchedulerEngine;
import it.unimib.datai.nanofaas.execution.StrategyRegistry;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadCapacityController;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsBinder;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Composes both scheduling strategies (or however many are on the classpath) around ONE
 * {@link SchedulerEngine}, ONE {@link SchedulerControl} and ONE Spring lifecycle, instead of the
 * two mutually-exclusive scheduler implementations async-queue and sync-queue used to register
 * (Task 8, issue #208).
 *
 * <p>Activates only when at least one {@link SchedulingStrategy} bean exists — i.e. only when a
 * queue module is on the classpath. With none, the core's own {@code InvocationEnqueuerAutoConfiguration}
 * fallback (a direct, non-queued {@code InvocationEnqueuer}) is untouched, matching the existing
 * "no queue module" / {@code direct} admission profile.
 *
 * <p>Ordered after the queue modules' own auto-configurations (by class name, since core cannot
 * depend on either module) so {@link StrategyRegistry} sees every strategy factory they publish.
 */
@AutoConfiguration
@AutoConfigureAfter(name = {
        "it.unimib.datai.nanofaas.modules.asyncqueue.AsyncQueueConfiguration",
        "it.unimib.datai.nanofaas.modules.syncqueue.SyncQueueConfiguration"
})
@ConditionalOnBean(SchedulingStrategy.class)
@EnableConfigurationProperties(SchedulerProperties.class)
public class SchedulerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SchedulerConfiguration.class);

    /**
     * A generous engine-wide safety net for profiles where no single global admission cap ever
     * existed (async-only and both-modules default to {@link AdmissionProfile#FUNCTION_QUEUE},
     * which previously had only PER-FUNCTION caps — see {@link SchedulerEngine#enqueue(it.unimib.datai.nanofaas.execution.PendingEntry, int)}). When the
     * active profile is {@link AdmissionProfile#SYNC_QUEUE} this is overridden by the sync
     * module's own {@code sync-queue.max-depth}, preserving that cap exactly.
     *
     * <p>Under {@code FUNCTION_QUEUE} it is the only GLOBAL cap, and it is deliberately loose
     * enough to be unreachable with the shipped defaults rather than tuned to them: the binding
     * limit there is the per-function one, {@code nanofaas.defaults.queueSize}
     * ({@code application.yml}: 100), applied by the engine per function. Reaching
     * 10 000 pending entries therefore takes on the order of a hundred functions saturated at
     * once — so this constant is a backstop against an unbounded store, not a policy knob, and
     * nothing derives it from {@code queueSize} or from a configured function count. Two edits
     * would make it bind: raising the default {@code queueSize} by two orders of magnitude, or
     * the deployment's own function count reaching that order. Neither is a reason to change it;
     * both are why it is this high and why it is named here.</p>
     */
    private static final int DEFAULT_MAX_PENDING = 10_000;

    @Bean
    public StrategyRegistry strategyRegistry(List<SchedulingStrategy> strategies) {
        return new StrategyRegistry(strategies);
    }

    @Bean
    public AdmissionProfile admissionProfile(Environment env, StrategyRegistry strategies) {
        String explicit = env.getProperty("nanofaas.admission.profile");
        if (explicit != null && !explicit.isBlank()) {
            return AdmissionProfile.valueOf(
                    explicit.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        }
        List<String> ids = strategies.ids();
        if (ids.contains("per-function")) {
            return AdmissionProfile.FUNCTION_QUEUE;
        }
        if (ids.contains("shared-queue")) {
            return AdmissionProfile.SYNC_QUEUE;
        }
        return AdmissionProfile.DIRECT;
    }

    @Bean
    public boolean asyncInvocationEnabled(Environment env, AdmissionProfile profile) {
        String explicit = env.getProperty("nanofaas.invocation.async-enabled");
        if (explicit != null && !explicit.isBlank()) {
            return Boolean.parseBoolean(explicit.trim());
        }
        return profile == AdmissionProfile.FUNCTION_QUEUE;
    }

    private static String resolveInitialStrategy(SchedulerProperties props, StrategyRegistry strategies) {
        if (props.strategy() != null && !props.strategy().isBlank()) {
            return props.strategy();
        }
        List<String> ids = strategies.ids();
        return ids.contains("per-function") ? "per-function" : ids.get(0);
    }

    /** Shared ticket admission order across every strategy and both admission fronts. */
    @Bean
    public java.util.function.LongSupplier schedulerTicketSequence() {
        AtomicLong counter = new AtomicLong();
        return counter::incrementAndGet;
    }

    @Bean
    public PendingWorkStore pendingWorkStore(AdmissionProfile profile,
            @Qualifier("syncQueueMaxDepth") ObjectProvider<Integer> syncQueueMaxDepth) {
        Integer configured = syncQueueMaxDepth.getIfAvailable();
        int maxPending = profile == AdmissionProfile.SYNC_QUEUE && configured != null
                ? configured
                : DEFAULT_MAX_PENDING;
        return new PendingWorkStore(maxPending);
    }

    /**
     * Fix round I2 correction: this is called from inside the engine gate (it is the
     * {@code runnable} predicate passed to {@code SchedulingIndex.select}), and
     * {@code capacityRegistry.state(name)} takes {@code FunctionCapacityRegistry}'s own
     * per-function {@code ReentrantLock} — so the acquisition order here is genuinely
     * {@code gate -> entry.lock}, one external lock taken while holding the gate. The plan's
     * contract for this predicate excludes exactly this ("non chiama provider, store o registry
     * mutabili"); the original Task 8 report's claim that {@code FunctionCapacityRegistry} "has
     * no dependency on the scheduler/engine at all" stopped being true the moment
     * {@link #schedulerEngine} registered {@code engine::signal} as a capacity listener below —
     * do not read that claim as still standing.
     *
     * <p>This is not being redesigned: reviewed end to end, there is no cycle. The registry's
     * only path back into the engine is that one capacity-listener callback
     * ({@code capacityRegistry.addCapacityListener(functionName -> engine.signal())} in
     * {@link #schedulerEngine}), and it fires from {@code FunctionCapacityRegistry.setEffectiveConcurrency}
     * strictly <em>after</em> that method has already released the entry lock (the listener loop
     * runs "outside the entry lock: listeners may take other locks", per that method's own
     * comment) — so no path exists that holds {@code entry.lock} while trying to take
     * {@code gate}. That is what makes {@code gate -> entry.lock} a safe, one-directional order.
     *
     * <p><strong>Invariant, recorded for whoever touches this next:</strong> the acquisition
     * order here is {@code gate -> entry.lock}, and it must stay one-directional. The single fire
     * site above is the whole reason it is safe today. Adding a second capacity listener (or any
     * other engine-reachable callback) that runs <em>inside</em> {@code entry.lock} — i.e. before
     * {@code FunctionCapacityRegistry} releases it — would reintroduce the reverse edge and
     * deadlock against a concurrent {@code selectAndClaim}. Do not add one without either keeping
     * it outside the lock (as this one is) or re-deriving this whole argument.
     */
    @Bean
    public EngineReadiness engineReadiness(DispatchCapacity capacityRegistry) {
        return generation -> {
            if (generation == null) {
                return false;
            }
            CapacityView state = capacityRegistry.state(generation.functionName());
            return state != null && generation.equals(state.generation()) && state.canDispatch();
        };
    }

    /**
     * Task 11 (issue #208): the engine-backed replacement for the two per-module
     * {@code WorkloadMetricsSource} beans Task 8 retired. Autoscaler and the concurrency
     * governor both gate their own startup on a bean of this type
     * ({@code @ConditionalOnBean(WorkloadMetricsSource.class)}); its absence between Task 8 and
     * this one silently disabled both — the 2026-08-29 incident {@code AutoscalerConfigurationTest}
     * documents. There is no longer a presence-check log.warn: a genuine source is always present
     * whenever this configuration activates at all, so the loud-failure stopgap is retired with it.
     */
    @Bean
    public EngineWorkloadMetricsSource schedulerWorkloadMetricsSource(SchedulerEngine engine,
            DispatchCapacity capacityRegistry) {
        return new EngineWorkloadMetricsSource(engine, capacityRegistry);
    }

    // Fix round 1 (issue #208): a WorkloadDiagnostics bean was removed from here. It registered
    // six per-function meters (queue offer/poll duration, dispatch-slot hold, scheduler
    // wakeup/poll delay) via registerFunction below, but nothing in this composition ever called
    // a single recorder method on it — six always-empty series per function, presented as
    // restored observability. Registering without recording is half-wiring, not observability;
    // wiring the actual recorders into EngineTransport's hot dispatch path is deferred to a task
    // that reviews that path deliberately, not folded into this fix round. Do not re-add the
    // registerFunction/removeFunction calls without wiring at least one recorder alongside them.

    @Bean
    public WorkloadMetricsBinder schedulerWorkloadMetricsBinder(MeterRegistry registry,
            EngineWorkloadMetricsSource source) {
        return new WorkloadMetricsBinder(registry, source);
    }

    /**
     * Two gauges (one per built-in strategy, so cardinality is bounded by the artefact's own
     * {@link StrategyRegistry}, never by execution/ticket/generation identity) plus one bounded
     * switch-outcome counter and a switch-duration timer. Registered once, at composition time —
     * not per switch — since {@code Gauge} is pull-based and {@code Counter}/{@code Timer}
     * lookups by the same id are idempotent.
     *
     * <p>The switch observer runs after {@link SchedulerEngine#switchTo}'s own linearization
     * point and cannot affect its outcome (Task 5's invariant, restated on
     * {@code SchedulerEngine.switchTo}'s own javadoc): a throwing observer is caught inside the
     * engine itself, never here.
     */
    @Bean
    public Object schedulerSwitchObservability(MeterRegistry registry, StrategyRegistry strategies,
            SchedulerEngine engine) {
        for (String id : strategies.ids()) {
            Gauge.builder("scheduler_active", engine,
                            candidate -> candidate.snapshot().strategy().equals(id) ? 1 : 0)
                    .tag("strategy", id)
                    .register(registry);
        }
        Timer switchDuration = Timer.builder("scheduler_switch_duration").register(registry);
        engine.setSwitchObserver((strategy, outcome, durationNanos) -> {
            Counter.builder("scheduler_switch_total")
                    .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                    .register(registry)
                    .increment();
            switchDuration.record(durationNanos, TimeUnit.NANOSECONDS);
        });
        // The return value is never consumed; this bean exists for the registration side effects
        // above, run once at startup like every other composition bean here.
        return new Object();
    }

    /** Bound to the real engine once it exists (see {@link #schedulerEngine}), breaking the
     * constructor cycle between the engine and its own dispatch adapter. */
    @Bean
    public WakeHandle schedulerWakeHandle() {
        return new WakeHandle();
    }

    @Bean
    public EngineDispatch engineDispatch(DispatchCapacity capacityRegistry,
            InvocationDispatch invocationService,
            QueueLifecycle queueLifecycle,
            ObjectProvider<EngineSyncQueueGateway> syncGateway,
            AdmissionProfile profile,
            WakeHandle wakeHandle) {
        // Resolved once, not per dispatch: ObjectProvider.getIfAvailable() re-runs the bean lookup
        // on every call. Outside SYNC_QUEUE the gateway never admits, so it can have no sync-origin
        // ticket to settle.
        EngineSyncQueueGateway gateway = profile == AdmissionProfile.SYNC_QUEUE ? syncGateway.getIfAvailable() : null;
        return new EngineTransport(capacityRegistry, invocationService, queueLifecycle, wakeHandle,
                gateway);
    }

    @Bean(destroyMethod = "close")
    public SchedulerEngine schedulerEngine(PendingWorkStore store, StrategyRegistry strategies,
            SchedulerProperties props, DispatchCapacity capacityRegistry,
            EngineDispatch dispatch, EngineReadiness readiness, WakeHandle wakeHandle) {
        String initial = resolveInitialStrategy(props, strategies);
        SchedulerEngine engine = new SchedulerEngine(store, strategies, initial, dispatch, readiness,
                Clock.systemUTC(), System::nanoTime);
        wakeHandle.bind(engine::signal);
        // signal() path 2/2: a released or raised capacity ceiling wakes the engine so a
        // generation the last pass found blocked is re-examined without waiting out the park
        // safety bound. Path 1/2 is EngineTransport.tryAcquire's onReleased callback below.
        capacityRegistry.addCapacityListener(functionName -> engine.signal());
        return engine;
    }

    @Bean
    public SchedulerLifecycleAdapter schedulerLifecycleAdapter(SchedulerEngine engine) {
        return new SchedulerLifecycleAdapter(engine);
    }

    // No separate SchedulerControl bean: SchedulerEngine already implements it, and a second
    // bean method returning the same instance under that interface type would duplicate it in
    // any getBeansOfType(SchedulerControl.class) lookup (as it did for InvocationEnqueuer above).

    /** The governor's capacity knob (P06); exactly one bean regardless of which queue modules
     * are on the classpath — both used to publish their own. */
    @Bean
    public WorkloadCapacityController schedulerWorkloadCapacityController(DispatchCapacity capacityRegistry) {
        return capacityRegistry::setEffectiveConcurrency;
    }

    /**
     * The generation-registration listener both queue modules used to embed inside their own
     * (now retired) scheduler implementations: capacity must still be registered/removed for
     * every function so {@link EngineInvocationEnqueuer}/{@link EngineSyncQueueGateway} can
     * resolve an active {@link FunctionGeneration} at admission time.
     *
     * <p>Fix round C2: {@code onRemove} now also drains this function's pending, undispatched
     * engine tickets through {@link SchedulerEngine#removeAllFor}, terminating a
     * queued caller as {@code ExecutionState.ERROR}/{@code FUNCTION_REMOVED} rather than leaving
     * it to hang to its own timeout.
     *
     * <p>Fix round 2 correction: the drain is {@link SchedulerEngine#removeAllFor}, not a direct
     * {@code PendingWorkStore.snapshotPending()} scan from this listener. {@code PendingWorkStore}
     * documents that it does not lock internally — the engine serializes every operation on it —
     * and this listener runs on the request thread serving the removal HTTP call while the
     * engine's own worker mutates the same store concurrently; reading it directly here could
     * throw a {@code ConcurrentModificationException} out of the snapshot (leaving the fence
     * raised and capacity never retired) or silently miss entries. {@code removeAllFor} does the
     * whole scan-and-remove under the engine's own gate, the same requirement every other access
     * to that store already observes.
     *
     * <p>Fix round 2 correction: capacity is retired <em>before</em> the drain, not after, so a
     * concurrent admission finds no active generation and refuses. Draining first would leave {@code capacityRegistry.activeGeneration} resolvable for the
     * whole drain window, letting a concurrent {@link EngineInvocationEnqueuer#admitDirect} admit
     * a ticket the scan has already passed — stranded forever in the {@code FUNCTION_QUEUE}
     * profile, since that ticket's {@code queueDeadline} is null and the sync removal fence below
     * does not cover that admission front at all.
     *
     * <p>Task 11 addition: also owns the per-function {@link WorkloadMetricsBinder} meter
     * lifecycle, and is the sole place that registers the engine's drain listener — once, here,
     * never in a strategy (the plan's own constraint on capacity/lifecycle listeners).
     * {@code WorkloadDiagnostics} is deliberately NOT wired here (fix round 1) — see the comment
     * above where its bean used to be.
     *
     * <p>Meters register at {@code onRegister} like every other per-function resource, but do
     * NOT come down at {@code onRemove}: a generation's meters (queue depth, in-flight,
     * dispatchable backlog) are still meaningful while a physically active attempt is still
     * draining under the retired generation's lease, and removing them early would blind that
     * drain rather than document it. Instead {@code onRemove} calls
     * {@link SchedulerEngine#markDraining}, and the drain listener registered below fires the
     * first time this function's reservations reach zero. {@code onRegister} calls
     * {@link SchedulerEngine#clearDraining} first, so a function removed and re-registered before
     * the old generation finished draining does not have the new generation's freshly
     * (re-)registered meters torn down by the old generation's belated zero-crossing.
     */
    @Bean
    public FunctionRegistrationListener schedulerCapacityGenerationListener(DispatchCapacity capacityRegistry,
            SchedulerEngine engine,
            ObjectProvider<EngineSyncQueueGateway> syncGateway,
            WorkloadMetricsBinder metricsBinder) {
        engine.addDrainListener(functionName -> {
            metricsBinder.removeFunction(functionName);
            log.debug("Removed per-function meters for {} after its retired generation drained", functionName);
        });
        return new FunctionRegistrationListener() {
            @Override
            public void onRegister(FunctionSpec spec) {
                // Clear the fence before granting capacity, so a task admitted in the gap simply waits for a slot instead of
                // racing a stale removal fence.
                EngineSyncQueueGateway gateway = syncGateway.getIfAvailable();
                if (gateway != null) {
                    gateway.clearRemovalFence(spec.name());
                }
                engine.clearDraining(spec.name());
                capacityRegistry.register(spec.name(), spec.concurrency());
                metricsBinder.registerFunction(spec.name());
            }

            @Override
            public void onRemove(String functionName) {
                EngineSyncQueueGateway gateway = syncGateway.getIfAvailable();
                if (gateway != null) {
                    gateway.raiseRemovalFence(functionName);
                }
                // Retire capacity BEFORE draining — see the javadoc above for why the reverse
                // order reopens the exact race C2 exists to close.
                capacityRegistry.remove(functionName);
                engine.removeAllFor(functionName);
                // Meters come down once every reservation this function holds has settled, not
                // here — see this method's own javadoc.
                engine.markDraining(functionName);
                if (gateway != null) {
                    gateway.functionRemoved(functionName);
                    // Redundant once capacityRegistry.remove has run above (enqueueOrThrow then
                    // rejects on generation == null anyway) — cleared here so a function removed
                    // and never re-registered does not sit in this set forever.
                    gateway.clearRemovalFence(functionName);
                }
            }
        };
    }

    @Bean
    public EngineInvocationEnqueuer engineInvocationEnqueuer(ObjectProvider<SchedulerEngine> engine,
            DispatchCapacity capacityRegistry,
            java.util.function.LongSupplier sequence,
            AdmissionProfile profile,
            boolean asyncInvocationEnabled,
            ObjectProvider<EngineSyncQueueGateway> syncGateway) {
        return new EngineInvocationEnqueuer(engine, capacityRegistry, sequence, profile,
                asyncInvocationEnabled, syncGateway);
    }

    /** Mutable indirection so {@link EngineDispatch} can be built before the engine exists. */
    static final class WakeHandle implements Runnable {
        private volatile Runnable delegate = () -> { };

        void bind(Runnable delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        }

        @Override
        public void run() {
            delegate.run();
        }
    }

    /**
     * The engine's transport: capacity acquisition, dispatch and the three lifecycle events it
     * reports back through {@link QueueLifecycle}. Also (path 1/2 of {@code signal()}) the place
     * a released capacity slot wakes the engine.
     */
    private record EngineTransport(DispatchCapacity capacityRegistry,
                                   InvocationDispatch invocationService,
                                   QueueLifecycle queueLifecycle,
                                   WakeHandle wake,
                                   EngineSyncQueueGateway syncGateway)
            implements EngineDispatch {

        @Override
        public DispatchOwnership tryAcquire(SchedulingTicket ticket) {
            return capacityRegistry.tryAcquireLease(ticket.generation(), held -> wake.run());
        }

        @Override
        public boolean isCurrent(SchedulingTicket ticket) {
            // ponytail: no separate staleness pre-check here. Neither retired scheduler
            // (Scheduler, SyncScheduler) pre-checked either; dispatch() already fences by
            // execution/attempt/generation internally. Revisit only if a real staleness leak
            // shows up in practice.
            return true;
        }

        /**
         * Settled exactly at the engine's own {@code finishSubmit}/{@code requeue} boundary.
         * {@code SchedulerEngine.submit} takes the requeue branch precisely when this call throws
         * {@code InvocationQuotaExceededException} (input backpressure) — any other outcome,
         * including a throw of anything else, is the {@code finishSubmit} branch.
         */
        @Override
        public void submit(InvocationTask task) {
            try {
                invocationService.dispatch(task);
            } catch (InvocationQuotaExceededException requeue) {
                // The engine requeues this ticket; it still occupies its reservation, so the
                // sync-origin tracking is not released here.
                throw requeue;
            } catch (RuntimeException | Error other) {
                settle(task);
                throw other;
            }
            settle(task);
        }

        private void settle(InvocationTask task) {
            if (syncGateway != null) {
                // Fix round C1: feed the estimator sync-origin dispatches only.
                // settleIfSyncOrigin also clears this gateway's own bookkeeping either way.
                TicketId id = new TicketId(task.executionId(), task.attempt());
                if (syncGateway.settleIfSyncOrigin(task.functionName(), id)) {
                    syncGateway.recordDispatched(task.functionName(), Instant.now());
                }
            }
        }

        private void discardSyncOrigin(InvocationTask task) {
            if (syncGateway != null) {
                syncGateway.settleIfSyncOrigin(task.functionName(), new TicketId(task.executionId(), task.attempt()));
            }
        }

        @Override
        public void expired(InvocationTask task) {
            discardSyncOrigin(task);
            queueLifecycle.expired(task);
        }

        @Override
        public void removed(InvocationTask task) {
            discardSyncOrigin(task);
            queueLifecycle.removed(task);
        }

        @Override
        public void rejected(InvocationTask task, Throwable failure) {
            queueLifecycle.rejected(task, failure);
        }
    }
}
