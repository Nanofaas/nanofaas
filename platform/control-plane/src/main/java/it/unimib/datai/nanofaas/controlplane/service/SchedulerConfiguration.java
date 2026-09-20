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
import it.unimib.datai.nanofaas.controlplane.service.EngineInvocationEnqueuer.PerFunctionDepth;
import it.unimib.datai.nanofaas.execution.EngineDispatch;
import it.unimib.datai.nanofaas.execution.EngineReadiness;
import it.unimib.datai.nanofaas.execution.PendingEntry;
import it.unimib.datai.nanofaas.execution.PendingWorkStore;
import it.unimib.datai.nanofaas.execution.SchedulerEngine;
import it.unimib.datai.nanofaas.execution.StrategyRegistry;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadCapacityController;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsSource;
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
     * which previously had only PER-FUNCTION caps — see {@link PerFunctionDepth}). When the
     * active profile is {@link AdmissionProfile#SYNC_QUEUE} this is overridden by the sync
     * module's own {@code sync-queue.max-depth}, preserving that cap exactly.
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
    public PerFunctionDepth schedulerPerFunctionDepth() {
        return new PerFunctionDepth();
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
     * Fix round C3: autoscaler and the concurrency governor both refuse to start without a
     * {@code WorkloadMetricsSource} bean (each declares its own {@code @ConditionalOnBean}), and
     * this composition retires the per-module sources (Task 11 owns their engine-backed
     * replacement — see the Task 8 fix-round report). A silently-skipped {@code @ConditionalOnBean}
     * produces no bean, no log line and no failure on its own, which is exactly the shape of the
     * 2026-08-29 incident this ledger already carries a test for
     * ({@code AutoscalerConfigurationTest}) — so this makes the gap loud instead of silent.
     */
    @Bean
    public Object schedulerWorkloadMetricsSourcePresenceCheck(ObjectProvider<WorkloadMetricsSource> metricsSources) {
        if (metricsSources.stream().findAny().isEmpty()) {
            log.warn("No WorkloadMetricsSource bean is present: autoscaler and the concurrency "
                    + "governor will stay inactive (their @ConditionalOnBean on this type will not "
                    + "be satisfied) until Task 11 restores an engine-backed source (issue #208).");
        }
        // The return value is never consumed; this bean exists solely for the constructor-time
        // side effect above, evaluated once at startup alongside every other composition bean.
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
            PerFunctionDepth perFunctionDepth,
            ObjectProvider<EngineSyncQueueGateway> syncGateway,
            WakeHandle wakeHandle) {
        return new EngineTransport(capacityRegistry, invocationService, queueLifecycle, wakeHandle,
                perFunctionDepth, syncGateway);
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
     * engine tickets — the old per-module schedulers each did this on their own queue
     * ({@code QueueManager.remove}, {@code SyncQueueService.removeFunctionState}), terminating a
     * queued caller as {@code ExecutionState.ERROR}/{@code FUNCTION_REMOVED} rather than leaving
     * it to hang to its own timeout. {@link PendingWorkStore#snapshotPending()} is a bounded scan
     * (the store is capped), so no per-function ticket index is needed for this.
     */
    @Bean
    public FunctionRegistrationListener schedulerCapacityGenerationListener(DispatchCapacity capacityRegistry,
            SchedulerEngine engine, PendingWorkStore store,
            ObjectProvider<EngineSyncQueueGateway> syncGateway) {
        return new FunctionRegistrationListener() {
            @Override
            public void onRegister(FunctionSpec spec) {
                // Mirror SyncQueueService.registerFunction: clear the fence before granting
                // capacity, so a task admitted in the gap simply waits for a slot instead of
                // racing a stale removal fence.
                EngineSyncQueueGateway gateway = syncGateway.getIfAvailable();
                if (gateway != null) {
                    gateway.clearRemovalFence(spec.name());
                }
                capacityRegistry.register(spec.name(), spec.concurrency());
            }

            @Override
            public void onRemove(String functionName) {
                EngineSyncQueueGateway gateway = syncGateway.getIfAvailable();
                if (gateway != null) {
                    gateway.raiseRemovalFence(functionName);
                }
                // Drain before retiring capacity: a ticket claimed mid-drain by a concurrent
                // pass is untouched by engine.remove (PendingWorkStore.remove is a no-op once a
                // ticket is submitting — that attempt is already committed and belongs to the
                // lifecycle, exactly as SchedulerEngine's own out-of-band removal documents).
                for (PendingEntry entry : store.snapshotPending()) {
                    if (entry.ticket().generation().functionName().equals(functionName)) {
                        engine.remove(entry.ticket().id());
                    }
                }
                capacityRegistry.remove(functionName);
                if (gateway != null) {
                    gateway.functionRemoved(functionName);
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
            ObjectProvider<EngineSyncQueueGateway> syncGateway,
            PerFunctionDepth perFunctionDepth) {
        return new EngineInvocationEnqueuer(engine, capacityRegistry, sequence, profile,
                asyncInvocationEnabled, syncGateway, perFunctionDepth);
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
     * reports back through {@link QueueLifecycle}. Also the one place both admission fronts'
     * per-function depth counter is settled, and (path 1/2 of {@code signal()}) the place a
     * released capacity slot wakes the engine.
     */
    private record EngineTransport(DispatchCapacity capacityRegistry,
                                   InvocationDispatch invocationService,
                                   QueueLifecycle queueLifecycle,
                                   WakeHandle wake,
                                   PerFunctionDepth perFunctionDepth,
                                   ObjectProvider<EngineSyncQueueGateway> syncGateway)
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
         * Fix round I1: settled exactly at the engine's own {@code finishSubmit}/{@code requeue}
         * boundary rather than unconditionally on entry. {@code SchedulerEngine.submit} takes the
         * requeue branch precisely when this call throws {@code InvocationQuotaExceededException}
         * (input backpressure) — any other outcome, including a throw of anything else, is the
         * {@code finishSubmit} branch. The previous unconditional decrement-on-entry both
         * under-counted a requeued ticket's continued occupancy and, worse, double-released it
         * (once here, once more on its eventual real settlement) — this now releases exactly once
         * per admitted ticket, on the same condition the engine itself branches on.
         */
        @Override
        public void submit(InvocationTask task) {
            try {
                invocationService.dispatch(task);
            } catch (InvocationQuotaExceededException requeue) {
                // The engine requeues this ticket; it is still occupying its reservation, so
                // neither the per-function cap nor the sync-origin tracking is released here.
                throw requeue;
            } catch (RuntimeException | Error other) {
                settle(task);
                throw other;
            }
            settle(task);
        }

        private void settle(InvocationTask task) {
            perFunctionDepth.release(task.functionName());
            EngineSyncQueueGateway gateway = syncGateway.getIfAvailable();
            if (gateway != null) {
                // Fix round C1: feed the estimator sync-origin dispatches only.
                // settleIfSyncOrigin also clears this gateway's own bookkeeping either way.
                TicketId id = new TicketId(task.executionId(), task.attempt());
                if (gateway.settleIfSyncOrigin(id)) {
                    gateway.recordDispatched(task.functionName(), Instant.now());
                }
            }
        }

        private void discardSyncOrigin(InvocationTask task) {
            EngineSyncQueueGateway gateway = syncGateway.getIfAvailable();
            if (gateway != null) {
                gateway.settleIfSyncOrigin(new TicketId(task.executionId(), task.attempt()));
            }
        }

        @Override
        public void expired(InvocationTask task) {
            perFunctionDepth.release(task.functionName());
            discardSyncOrigin(task);
            queueLifecycle.expired(task);
        }

        @Override
        public void removed(InvocationTask task) {
            perFunctionDepth.release(task.functionName());
            discardSyncOrigin(task);
            queueLifecycle.removed(task);
        }

        @Override
        public void rejected(InvocationTask task, Throwable failure) {
            queueLifecycle.rejected(task, failure);
        }
    }
}
