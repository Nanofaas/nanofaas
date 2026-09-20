package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.capacity.CapacityView;
import it.unimib.datai.nanofaas.controlplane.capacity.DispatchCapacity;
import it.unimib.datai.nanofaas.controlplane.capacity.DispatchOwnership;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.config.SchedulerProperties;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistrationListener;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationDispatch;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.QueueLifecycle;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerControl;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingStrategy;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingTicket;
import it.unimib.datai.nanofaas.controlplane.service.EngineInvocationEnqueuer.AdmissionProfile;
import it.unimib.datai.nanofaas.controlplane.service.EngineInvocationEnqueuer.PerFunctionDepth;
import it.unimib.datai.nanofaas.execution.EngineDispatch;
import it.unimib.datai.nanofaas.execution.EngineReadiness;
import it.unimib.datai.nanofaas.execution.PendingWorkStore;
import it.unimib.datai.nanofaas.execution.SchedulerEngine;
import it.unimib.datai.nanofaas.execution.StrategyRegistry;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadCapacityController;
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
     * <p>ponytail: a function removed while it still has admitted-but-undispatched tickets in
     * the engine leaves those tickets in their index — the old per-module schedulers actively
     * drained their own queue on removal, this composition does not yet drain the engine's.
     * Bounded, not corrupting (see {@code SchedulerEngine} javadoc on out-of-band removal): a
     * sync ticket is still reaped by its queue deadline; a function-queue ticket (no deadline)
     * would sit inert until the process restarts. Revisit if redeploy churn makes this material
     * — closing it needs a per-function ticket-id index this task did not add.
     */
    @Bean
    public FunctionRegistrationListener schedulerCapacityGenerationListener(DispatchCapacity capacityRegistry,
            ObjectProvider<EngineSyncQueueGateway> syncGateway) {
        return new FunctionRegistrationListener() {
            @Override
            public void onRegister(FunctionSpec spec) {
                capacityRegistry.register(spec.name(), spec.concurrency());
            }

            @Override
            public void onRemove(String functionName) {
                capacityRegistry.remove(functionName);
                EngineSyncQueueGateway gateway = syncGateway.getIfAvailable();
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

        @Override
        public void submit(InvocationTask task) {
            perFunctionDepth.decrement(task.functionName());
            EngineSyncQueueGateway gateway = syncGateway.getIfAvailable();
            if (gateway != null) {
                gateway.recordDispatched(task.functionName(), Instant.now());
            }
            invocationService.dispatch(task);
        }

        @Override
        public void expired(InvocationTask task) {
            perFunctionDepth.decrement(task.functionName());
            queueLifecycle.expired(task);
        }

        @Override
        public void removed(InvocationTask task) {
            perFunctionDepth.decrement(task.functionName());
            queueLifecycle.removed(task);
        }

        @Override
        public void rejected(InvocationTask task, Throwable failure) {
            queueLifecycle.rejected(task, failure);
        }
    }
}
