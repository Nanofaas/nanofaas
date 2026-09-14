package it.unimib.datai.nanofaas.modules.autoscaler;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.registry.ManagedReplicaControl;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentWakeUpControl;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaObservation;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatus;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionCatalogView;
import it.unimib.datai.nanofaas.controlplane.registry.RegisteredFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;

import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("FutureReturnValueIgnored") // Lifecycle shutdown owns the periodic task.
public class InternalScaler implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(InternalScaler.class);

    private final FunctionCatalogView registry;
    private final ManagedReplicaControl deploymentCoordinator;
    private final ScalingProperties properties;
    private final ColdStartTracker coldStartTracker;
    private final ScalingDecisionCalculator decisionCalculator;
    private final ScalingCooldownTracker cooldownTracker;
    private final ScalingProgressTracker progressTracker;
    private final DeploymentWakeUpControl wakeUpCoordinator;
    private final ScalingDecisionMetrics decisionMetrics;
    private final InstantSource instantSource;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService executor;

    public InternalScaler(FunctionCatalogView registry,
                          ScalingMetricsReader metricsReader,
                          @Autowired(required = false) ManagedReplicaControl deploymentCoordinator,
                          ScalingProperties properties,
                          ColdStartTracker coldStartTracker,
                          DeploymentWakeUpControl wakeUpCoordinator) {
        this(registry, metricsReader, deploymentCoordinator, properties, coldStartTracker,
                wakeUpCoordinator, null);
    }

    public InternalScaler(FunctionCatalogView registry,
                          ScalingMetricsReader metricsReader,
                          @Autowired(required = false) ManagedReplicaControl deploymentCoordinator,
                          ScalingProperties properties,
                          ColdStartTracker coldStartTracker,
                          DeploymentWakeUpControl wakeUpCoordinator,
                          ScalingDecisionMetrics decisionMetrics) {
        this(registry, metricsReader, deploymentCoordinator, properties, coldStartTracker,
                wakeUpCoordinator, decisionMetrics, InstantSource.system());
    }

    public InternalScaler(FunctionCatalogView registry,
                          ScalingMetricsReader metricsReader,
                          @Autowired(required = false) ManagedReplicaControl deploymentCoordinator,
                          ScalingProperties properties,
                          ColdStartTracker coldStartTracker,
                          DeploymentWakeUpControl wakeUpCoordinator,
                          ScalingDecisionMetrics decisionMetrics,
                          InstantSource instantSource) {
        this.decisionMetrics = decisionMetrics;
        this.registry = registry;
        this.deploymentCoordinator = deploymentCoordinator;
        this.properties = properties;
        this.coldStartTracker = coldStartTracker;
        this.decisionCalculator = new ScalingDecisionCalculator(metricsReader);
        this.cooldownTracker = new ScalingCooldownTracker();
        this.progressTracker = new ScalingProgressTracker();
        this.wakeUpCoordinator = wakeUpCoordinator;
        this.instantSource = instantSource;
    }

    @Override
    public void start() {
        if (deploymentCoordinator == null) {
            log.info("InternalScaler disabled: no ManagedReplicaControl available");
            return;
        }
        if (running.compareAndSet(false, true)) {
            log.info("InternalScaler starting with poll interval {}ms", properties.pollIntervalMsOrDefault());
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "nanofaas-internal-scaler");
                t.setDaemon(true);
                return t;
            });
            executor.scheduleAtFixedRate(this::scalingLoop, properties.pollIntervalMsOrDefault(),
                    properties.pollIntervalMsOrDefault(), TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("InternalScaler stopping...");
            if (executor != null) {
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException _) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            log.info("InternalScaler stopped");
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
    void scalingLoop() {
        try {
            for (RegisteredFunction registeredFunction : registry.listRegistered()) {
                FunctionSpec spec = registeredFunction.spec();
                ScalingConfig scaling = spec.scalingConfig();
                registeredFunction.managedDeploymentTarget().ifPresent(target -> {
                    if (scaling != null
                        && scaling.strategy() == ScalingStrategy.INTERNAL) {
                        FunctionGeneration generation = deploymentCoordinator.generationOf(registeredFunction);
                        if (generation != null) {
                            scaleFunction(generation, target, spec);
                        }
                    }
                });
            }
        } catch (Exception ex) {
            log.error("Error in scaling loop", ex);
        }
    }

    private void scaleFunction(FunctionGeneration generation,
                               ManagedDeploymentTarget target,
                               FunctionSpec spec) {
        try {
            evaluateAndScale(generation, target, spec);
        } catch (Exception ex) {
            log.error("Error scaling function {}", spec.name(), ex);
        }
    }

    private void evaluateAndScale(FunctionGeneration generation,
                                  ManagedDeploymentTarget target,
                                  FunctionSpec spec) {
        String functionName = spec.name();
        // Desired and ready are read from the SAME snapshot so a decision can never be
        // based on a target that has already moved on (or a ready count from another pass).
        // The observation is non-blocking: a slow provider costs this function its cycle, never
        // the loop's other functions.
        ReplicaObservation observation = deploymentCoordinator.observeReplicaStatus(target);
        if (!(observation instanceof ReplicaObservation.Available available)) {
            // No reading at all. Skipping the cycle is the only correct move: treating it as zero
            // replicas would scale a healthy deployment on the strength of a failed GET (I9).
            log.debug("Skipping scaling for {}: no replica reading available ({})",
                    functionName, observation);
            return;
        }
        ReplicaStatus status = available.status();
        int readyReplicas = status.readyReplicas();
        int requestedReplicas = status.desiredReplicas();

        // The recommendation is still computed from the metric ratio multiplied by the
        // *serving* (ready) replicas — the formula's semantics are preserved; only the
        // comparison against the already-requested target changes.
        ScalingDecision decision = decisionCalculator.calculate(spec, readyReplicas);
        if (decisionMetrics != null) {
            decisionMetrics.recordDecision(functionName, decision);
        }

        Instant now = instantSource.instant();
        int recommended = decision.desiredReplicas();

        if (recommended > requestedReplicas) {
            // Genuine scale-up: the load needs more replicas than we have already asked for.
            scaleUp(generation, target, functionName, decision, requestedReplicas, now);
        } else if (recommended < requestedReplicas) {
            // The load needs fewer replicas than already requested.
            if (decision.downscaleSignal()) {
                // Explicit downscale: the serving (ready) replicas already exceed what the
                // load needs. This never waits for the rollout to complete, so replicas that
                // never became ready cannot block it.
                scaleDown(generation, target, functionName, decision, requestedReplicas, now);
            } else if (progressTracker.isStuck(functionName, requestedReplicas, readyReplicas, now)) {
                // Mid-rollout recommendation (ready <= recommended < requested) but the
                // rollout has made no progress for a full window: reconcile the requested
                // target down so a stuck rollout cannot hold a phantom target (or block a
                // real downscale) forever.
                scaleDown(generation, target, functionName, decision, requestedReplicas, now);
            }
            // Otherwise the rollout is still catching up and progressing: keep the
            // already-commanded higher target, do not walk it back.
        }
        // recommended == requestedReplicas: nothing to do.
    }

    private void scaleUp(FunctionGeneration generation, ManagedDeploymentTarget target, String functionName,
                         ScalingDecision decision, int requestedReplicas, Instant now) {
        if (!cooldownTracker.allowScaleUp(functionName, now)) {
            log.debug("Skipping scale-up for {} (cooldown)", functionName);
            return;
        }
        log.info("Scaling UP function {} from {} to {} replicas (maxRatio={})",
                functionName, requestedReplicas, decision.desiredReplicas(), decision.maxRatio());
        coldStartTracker.recordScaleUp(functionName, decision.currentReplicas(), decision.desiredReplicas());
        deploymentCoordinator.setReplicas(generation, target, decision.desiredReplicas());
        cooldownTracker.recordScaleUp(functionName, now);
    }

    private void scaleDown(FunctionGeneration generation, ManagedDeploymentTarget target, String functionName,
                           ScalingDecision decision, int requestedReplicas, Instant now) {
        if (!cooldownTracker.allowScaleDown(functionName, now)) {
            log.debug("Skipping scale-down for {} (cooldown)", functionName);
            return;
        }
        boolean scaled = wakeUpCoordinator.scaleDownIfUnprotected(generation, target, () -> {
            log.info("Scaling DOWN function {} from {} to {} replicas (maxRatio={})",
                    functionName, requestedReplicas, decision.desiredReplicas(), decision.maxRatio());
            return deploymentCoordinator.setReplicas(generation, target, decision.desiredReplicas());
        });
        if (scaled) {
            cooldownTracker.recordScaleDown(functionName, now);
            progressTracker.clear(functionName);
        } else {
            log.debug("Skipping scale-down for {} while deployment wake-up is protected", functionName);
        }
    }

    void removeFunctionState(String functionName) {
        cooldownTracker.clear(functionName);
        progressTracker.clear(functionName);
        coldStartTracker.removeFunctionState(functionName);
        wakeUpCoordinator.removeFunctionState(functionName);
    }
}
