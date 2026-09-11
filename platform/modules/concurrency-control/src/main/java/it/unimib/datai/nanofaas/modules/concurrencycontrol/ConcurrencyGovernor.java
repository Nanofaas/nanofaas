package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaObservation;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistry;
import it.unimib.datai.nanofaas.controlplane.registry.ManagedDeploymentCoordinator;
import it.unimib.datai.nanofaas.controlplane.registry.RegisteredFunction;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerLifecycleSupport;
import it.unimib.datai.nanofaas.controlplane.service.InvocationObservations;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadCapacityController;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsSource;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Periodically re-evaluates the per-function concurrency limit.
 *
 * <p>Deliberately independent of the autoscaler: concurrency per replica is orthogonal to how many
 * replicas exist, so a function with a fixed replica count ({@code ScalingStrategy.NONE} or an
 * external HPA) still gets a governed concurrency limit.</p>
 */
public class ConcurrencyGovernor implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(ConcurrencyGovernor.class);

    private final FunctionRegistry registry;
    private final InvocationObservations metrics;
    private final java.util.Map<String, FunctionGeneration> observedGenerations = new java.util.concurrent.ConcurrentHashMap<>();
    private final ConcurrencyControlCoordinator coordinator;
    private final ConcurrencyControlProperties properties;
    private final ManagedDeploymentCoordinator deploymentCoordinator;
    private final WorkloadMetricsSource metricsSource;
    private final WorkloadCapacityController capacityController;
    private final ConcurrencyControlMetrics concurrencyMetrics;
    private final BudgetedConcurrencyController budgetedController;
    private final SojournConcurrencyController sojournController;
    private final InstantSource clock;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService executor;

    // Spring wiring: every parameter is a distinct collaborator this class holds. The
    // two that did belong together are already bundled into one controllers record, and
    // grouping the rest by nothing but arity would make the wiring harder to read.
    @SuppressWarnings("java:S107")
    public ConcurrencyGovernor(FunctionRegistry registry,
                               InvocationObservations metrics,
                               ConcurrencyControlCoordinator coordinator,
                               ConcurrencyControlProperties properties,
                               ManagedDeploymentCoordinator deploymentCoordinator,
                               WorkloadMetricsSource metricsSource,
                               WorkloadCapacityController capacityController,
                               ConcurrencyControlMetrics concurrencyMetrics) {
        this(registry, metrics,
                new ConcurrencyControllers(coordinator, new BudgetedConcurrencyController()),
                properties, deploymentCoordinator, metricsSource, capacityController, concurrencyMetrics,
                InstantSource.system());
    }

    /**
     * The two strategies the governor drives. Bundled because they are one choice seen from two
     * sides — a function is governed by one or the other, never both — and because a constructor
     * long enough to need counting is a constructor whose arguments get swapped.
     */
    public record ConcurrencyControllers(
            ConcurrencyControlCoordinator perFunction,
            BudgetedConcurrencyController budgeted,
            SojournConcurrencyController sojourn
    ) {
        public ConcurrencyControllers(
                ConcurrencyControlCoordinator perFunction, BudgetedConcurrencyController budgeted) {
            this(perFunction, budgeted, new SojournConcurrencyController());
        }
    }

    @SuppressWarnings("java:S107")   // see the delegating constructor above
    public ConcurrencyGovernor(FunctionRegistry registry,
                               InvocationObservations metrics,
                               ConcurrencyControllers controllers,
                               ConcurrencyControlProperties properties,
                               ManagedDeploymentCoordinator deploymentCoordinator,
                               WorkloadMetricsSource metricsSource,
                               WorkloadCapacityController capacityController,
                               ConcurrencyControlMetrics concurrencyMetrics,
                               InstantSource clock) {
        this.registry = registry;
        this.metrics = metrics;
        this.coordinator = controllers.perFunction();
        this.budgetedController = controllers.budgeted();
        this.sojournController = controllers.sojourn();
        this.properties = properties;
        this.deploymentCoordinator = deploymentCoordinator;
        this.metricsSource = metricsSource;
        this.capacityController = capacityController;
        this.concurrencyMetrics = concurrencyMetrics;
        this.clock = clock;
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            long interval = properties.pollIntervalMsOrDefault();
            log.info("ConcurrencyGovernor starting with poll interval {}ms", interval);
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "nanofaas-concurrency-governor");
                t.setDaemon(true);
                return t;
            });
            executor.scheduleAtFixedRate(this::governLoop, interval, interval, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            SchedulerLifecycleSupport.shutdownExecutor(executor, log, "ConcurrencyGovernor");
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    // Package-private for testing
    // Serialize a sampled cycle with lifecycle cleanup: stale samples cannot repopulate
    // controller or generation state after removal has returned. Not an invocation lock.
    synchronized void governLoop() {
        try {
            long now = clock.millis();
            List<BudgetedConcurrencyController.FunctionObservation> budgeted = new ArrayList<>();
            for (RegisteredFunction registeredFunction : registry.listRegistered()) {
                switch (modeOf(registeredFunction.spec())) {
                    case BUDGETED -> observe(registeredFunction).ifPresent(budgeted::add);
                    // Routed here rather than through the coordinator because it is the one
                    // controller that reads the end-to-end timer, and widening the coordinator's
                    // signature for it would hand every other mode an input it must ignore.
                    case SOJOURN -> governSojourn(registeredFunction, now);
                    default -> govern(registeredFunction, now);
                }
            }
            // One allocation for all of them, after every ask is in: a budget cannot be
            // respected by a decision taken one function at a time.
            if (!budgeted.isEmpty()) {
                budgetedController.apply(
                        budgeted, properties.totalBudgetOrDefault(), capacityController, concurrencyMetrics, now);
            }
        } catch (Exception ex) {
            log.error("Error in concurrency governor loop", ex);
        }
    }

    private static ConcurrencyControlMode modeOf(FunctionSpec spec) {
        if (spec.scalingConfig() == null || spec.scalingConfig().concurrencyControl() == null) {
            return ConcurrencyControlMode.FIXED;
        }
        return spec.scalingConfig().concurrencyControl().mode();
    }

    private void governSojourn(RegisteredFunction registeredFunction, long nowEpochMs) {
        try {
            String name = registeredFunction.name();
            var sample = observation(name);
            var e2e = sample.endToEnd();
            var service = sample.service();
            sojournController.apply(
                    new SojournConcurrencyController.FunctionObservation(
                            registeredFunction.spec(),
                            metricsSource.inFlight(name),
                            e2e.count(),
                            e2e.totalMillis(),
                            service.count(),
                            service.totalMillis()),
                    metricsSource, capacityController, concurrencyMetrics,
                    nowEpochMs);
        } catch (Exception ex) {
            log.error("Error governing sojourn concurrency for function {}",
                    registeredFunction.name(), ex);
        }
    }

    private Optional<BudgetedConcurrencyController.FunctionObservation> observe(
            RegisteredFunction registeredFunction) {
        try {
            var latency = observation(registeredFunction.name()).service();
            return Optional.of(new BudgetedConcurrencyController.FunctionObservation(
                    registeredFunction.spec(),
                    metricsSource.inFlight(registeredFunction.name()),
                    latency.count(),
                    latency.totalMillis()
            ));
        } catch (Exception ex) {
            // One unreadable function must not cost the others their allocation.
            log.error("Error reading concurrency inputs for function {}",
                    registeredFunction.name(), ex);
            return Optional.empty();
        }
    }

    private void govern(RegisteredFunction registeredFunction, long nowEpochMs) {
        try {
            OptionalInt readyReplicas = readyReplicas(registeredFunction);
            if (readyReplicas.isEmpty()) {
                // No replica reading for this cycle. The limit is per replica, so governing without
                // a replica count would mean inventing one — and inventing zero would collapse the
                // limit of a healthy function because a provider GET failed (invariant I9).
                log.debug("Skipping concurrency governing for {}: no replica reading available",
                        registeredFunction.name());
                return;
            }
            var latency = observation(registeredFunction.name()).service();
            coordinator.apply(
                    registeredFunction.spec(),
                    readyReplicas.getAsInt(),
                    latency.count(),
                    latency.totalMillis(),
                    nowEpochMs
            );
        } catch (Exception ex) {
            log.error("Error governing concurrency for function {}", registeredFunction.name(), ex);
        }
    }

    /**
     * Non-managed functions have no replicas to divide the limit across, so they are governed as a
     * single replica — the limit then caps in-flight invocations against the external endpoint.
     *
     * @return empty when the deployment provider has no usable reading for a managed function
     */
    private OptionalInt readyReplicas(RegisteredFunction registeredFunction) {
        if (deploymentCoordinator == null) {
            return OptionalInt.of(1);
        }
        Optional<ManagedDeploymentTarget> target = registeredFunction.managedDeploymentTarget();
        if (target.isEmpty()) {
            return OptionalInt.of(1);
        }
        return deploymentCoordinator.observeReplicaStatus(target.get())
                       instanceof ReplicaObservation.Available available
                ? OptionalInt.of(available.status().readyReplicas())
                : OptionalInt.empty();
    }

    private InvocationObservations.Snapshot observation(String functionName) {
        var sample = metrics.snapshot(functionName);
        if (sample.generation() != null) {
            var previous = observedGenerations.put(functionName, sample.generation());
            if (previous != null && !previous.equals(sample.generation())) {
                coordinator.removeFunctionState(functionName);
                budgetedController.removeFunctionState(functionName);
                sojournController.removeFunctionState(functionName);
            }
        }
        return sample;
    }

    synchronized void removeFunctionState(String functionName) {
        observedGenerations.remove(functionName);
        coordinator.removeFunctionState(functionName);
        budgetedController.removeFunctionState(functionName);
        sojournController.removeFunctionState(functionName);
    }
}
