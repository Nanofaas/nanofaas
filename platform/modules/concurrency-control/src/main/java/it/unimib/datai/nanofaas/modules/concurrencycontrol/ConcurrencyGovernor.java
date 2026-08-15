package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import io.micrometer.core.instrument.Timer;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentCoordinator;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistry;
import it.unimib.datai.nanofaas.controlplane.registry.RegisteredFunction;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulerLifecycleSupport;
import it.unimib.datai.nanofaas.controlplane.service.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.InstantSource;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final Metrics metrics;
    private final ConcurrencyControlCoordinator coordinator;
    private final ConcurrencyControlProperties properties;
    private final ManagedDeploymentCoordinator deploymentCoordinator;
    private final InstantSource clock;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService executor;

    public ConcurrencyGovernor(FunctionRegistry registry,
                               Metrics metrics,
                               ConcurrencyControlCoordinator coordinator,
                               ConcurrencyControlProperties properties,
                               ManagedDeploymentCoordinator deploymentCoordinator) {
        this(registry, metrics, coordinator, properties, deploymentCoordinator, InstantSource.system());
    }

    public ConcurrencyGovernor(FunctionRegistry registry,
                               Metrics metrics,
                               ConcurrencyControlCoordinator coordinator,
                               ConcurrencyControlProperties properties,
                               ManagedDeploymentCoordinator deploymentCoordinator,
                               InstantSource clock) {
        this.registry = registry;
        this.metrics = metrics;
        this.coordinator = coordinator;
        this.properties = properties;
        this.deploymentCoordinator = deploymentCoordinator;
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
    void governLoop() {
        try {
            long now = clock.millis();
            for (RegisteredFunction registeredFunction : registry.listRegistered()) {
                govern(registeredFunction, now);
            }
        } catch (Exception ex) {
            log.error("Error in concurrency governor loop", ex);
        }
    }

    private void govern(RegisteredFunction registeredFunction, long nowEpochMs) {
        try {
            Timer latency = metrics.latency(registeredFunction.name());
            coordinator.apply(
                    registeredFunction.spec(),
                    readyReplicas(registeredFunction),
                    latency.count(),
                    latency.totalTime(TimeUnit.MILLISECONDS),
                    nowEpochMs
            );
        } catch (Exception ex) {
            log.error("Error governing concurrency for function {}", registeredFunction.name(), ex);
        }
    }

    /**
     * Non-managed functions have no replicas to divide the limit across, so they are governed as a
     * single replica — the limit then caps in-flight invocations against the external endpoint.
     */
    private int readyReplicas(RegisteredFunction registeredFunction) {
        if (deploymentCoordinator == null) {
            return 1;
        }
        return registeredFunction.managedDeploymentTarget()
                .map(deploymentCoordinator::getReadyReplicas)
                .orElse(1);
    }

    void removeFunctionState(String functionName) {
        coordinator.removeFunctionState(functionName);
    }
}
