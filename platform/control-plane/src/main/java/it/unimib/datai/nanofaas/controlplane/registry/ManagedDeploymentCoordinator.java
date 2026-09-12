package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.controlplane.registry.ManagedReplicaControl;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProviderResolver;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaObservation;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatus;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatusSnapshot;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.InstantSource;

/**
 * Single persistence point for a managed deployment's replica target: it durably commits the new
 * {@code desiredReplicas} to the {@link FunctionRegistry} before applying the change through the
 * backend provider, and serializes every caller (manual scaling, the autoscaler, and the deployment
 * wake-up gate) on the shared per-function lock.
 *
 * <p>Replica-status reads go through a {@link ReplicaStatusSnapshot} shared by every consumer, so
 * the autoscaler, the governor and any other periodic reader hit the provider at most once per TTL
 * window. Periodic readers use {@link #observeReplicaStatus}, which never blocks on the provider and
 * distinguishes a fresh reading from a stale one from none at all; wake-up and lifecycle paths force
 * a fresh read through {@link #getFreshReplicaStatus}.</p>
 */
public class ManagedDeploymentCoordinator implements ManagedReplicaControl, AutoCloseable {

    private final DeploymentProviderResolver deploymentProviderResolver;
    private final FunctionRegistry registry;
    private final FunctionOperationLocks locks;
    private final FunctionCapacityRegistry generations;
    private final ReplicaStatusSnapshot snapshot;
    private final boolean ownsSnapshot;

    @Autowired
    public ManagedDeploymentCoordinator(DeploymentProviderResolver deploymentProviderResolver,
                                        FunctionRegistry registry,
                                        FunctionOperationLocks locks,
                                        FunctionCapacityRegistry generations,
                                        ReplicaStatusSnapshot snapshot) {
        this(deploymentProviderResolver, registry, locks, generations, snapshot, false);
    }

    public ManagedDeploymentCoordinator(DeploymentProviderResolver deploymentProviderResolver,
                                        FunctionRegistry registry,
                                        FunctionOperationLocks locks,
                                        ReplicaStatusSnapshot snapshot) {
        this(deploymentProviderResolver, registry, locks, new FunctionCapacityRegistry(), snapshot, false);
    }

    /**
     * Standalone wiring (tests, and the fallback in {@code FunctionService} when no coordinator bean
     * exists): the coordinator creates the snapshot and therefore owns its executors.
     */
    public ManagedDeploymentCoordinator(DeploymentProviderResolver deploymentProviderResolver,
                                        FunctionRegistry registry,
                                        FunctionOperationLocks locks) {
        this(deploymentProviderResolver, registry, locks,
                new FunctionCapacityRegistry(), ReplicaStatusSnapshot.withDefaults(InstantSource.system()), true);
    }

    private ManagedDeploymentCoordinator(DeploymentProviderResolver deploymentProviderResolver,
                                         FunctionRegistry registry,
                                         FunctionOperationLocks locks,
                                         FunctionCapacityRegistry generations,
                                         ReplicaStatusSnapshot snapshot,
                                         boolean ownsSnapshot) {
        this.deploymentProviderResolver = deploymentProviderResolver;
        this.registry = registry;
        this.locks = locks;
        this.generations = generations;
        this.snapshot = snapshot;
        this.ownsSnapshot = ownsSnapshot;
    }

    /**
     * Non-blocking read for the periodic consumers (autoscaler, concurrency governor, ...).
     *
     * <p>Returns what is known now — a FRESH or STALE reading, or UNAVAILABLE — and schedules the
     * refresh in the background. There is deliberately no variant that hands back a bare
     * {@link ReplicaStatus} here: a periodic consumer must decide what to do without a measurement,
     * and the sealed {@link ReplicaObservation} is what stops "no reading" from silently becoming
     * zero replicas.</p>
     */
    public ReplicaObservation observeReplicaStatus(ManagedDeploymentTarget target) {
        return snapshot.observe(target, this::fetchReplicaStatus);
    }

    /** Forced fresh read for wake-up and lifecycle paths (still single-flight, with a deadline). */
    public ReplicaStatus getFreshReplicaStatus(ManagedDeploymentTarget target) {
        return snapshot.refresh(target, this::fetchReplicaStatus);
    }

    /**
     * Drops the cached replica status for a function after a target change, removal or
     * re-registration, so the next read re-fetches instead of serving stale data.
     */
    public void invalidate(ManagedDeploymentTarget target) {
        snapshot.invalidate(target.functionName());
    }

    private ReplicaStatus fetchReplicaStatus(ManagedDeploymentTarget target) {
        return requireProvider(target).getReplicaStatus(target.functionName());
    }

    /**
     * Persists the new replica target and applies it through the backend provider.
     *
     * @return {@code false} when the function is no longer registered (e.g. a concurrent removal
     *         won the race); {@code true} once the change has been applied.
     */
    public boolean setReplicas(ManagedDeploymentTarget target, int replicas) {
        if (replicas < 0) {
            throw new IllegalArgumentException("replicas must be >= 0");
        }
        return locks.withLock(target.functionName(), () -> setReplicasLocked(target, replicas));
    }

    /** Applies a replica mutation only to the exact still-active P07 generation. */
    public boolean setReplicas(FunctionGeneration expectedGeneration,
                               ManagedDeploymentTarget target,
                               int replicas) {
        if (replicas < 0) {
            throw new IllegalArgumentException("replicas must be >= 0");
        }
        if (!expectedGeneration.functionName().equals(target.functionName())) {
            throw new IllegalArgumentException("generation and target must name the same function");
        }
        return locks.withLock(target.functionName(), () -> {
            if (!expectedGeneration.equals(generations.activeGeneration(target.functionName()))) {
                return false;
            }
            return setReplicasLocked(target, replicas);
        });
    }

    /** Captures a generation only while the registry entry is still the observed object. */
    public FunctionGeneration generationOf(RegisteredFunction observed) {
        return locks.withLock(observed.name(), () -> {
            if (registry.getRegistered(observed.name()).orElse(null) != observed) {
                return null;
            }
            return generations.activeGeneration(observed.name());
        });
    }

    private boolean setReplicasLocked(ManagedDeploymentTarget target, int replicas) {
        FunctionApplicationState applicationState = registry.applicationState();
        applicationState.requireAvailable(target.functionName());
        RegisteredFunction existing = registry.getRegistered(target.functionName()).orElse(null);
        if (existing == null) {
            return false;
        }
        if (existing.managedDeploymentTarget().filter(target::equals).isEmpty()) {
            // A stale target (removed and re-registered under a different backend between the
            // caller's lookup and this locked re-check) is a no-op, not an error.
            return false;
        }

        RegisteredFunction updated = existing.withDesiredReplicas(replicas);
        boolean applicationPending = applicationState.isScalePending(target, replicas);
        if (updated.equals(existing) && !applicationPending) {
            return true;
        }
        // Durable-first: desired state is the operator's target, not a claim about current provider
        // state. A provider failure is returned to the caller, while the persisted target remains
        // available for retry and restart reconciliation.
        if (!updated.equals(existing)) {
            registry.put(updated);
        }
        applicationState.markScale(target, replicas);
        try {
            requireProvider(target).setReplicas(target.functionName(), replicas);
            applicationState.completeScale(target, replicas);
        } finally {
            // The target changed and the provider attempt may have partially applied it. Forget the
            // volatile observation on both success and failure so it is never presented as current.
            snapshot.invalidate(target.functionName());
        }
        return true;
    }

    public void deprovision(ManagedDeploymentTarget target) {
        try {
            requireProvider(target).deprovision(target.functionName());
        } finally {
            // Even a deprovision that only partly succeeded changed what the backend holds: a
            // status cached from before it would be read as truth by the next observer.
            snapshot.invalidate(target.functionName());
        }
    }

    public ManagedDeploymentProvider requireProvider(ManagedDeploymentTarget target) {
        return deploymentProviderResolver.requireBackend(target.backendId());
    }

    /** Closes the snapshot only when this coordinator created it; an injected bean is the context's. */
    @Override
    public void close() {
        registry.applicationState().clearScaleApplications();
        if (ownsSnapshot) {
            snapshot.close();
        }
    }
}
