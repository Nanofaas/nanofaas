package it.unimib.datai.nanofaas.controlplane.registry;

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
    private java.util.function.LongSupplier nanoTime=System::nanoTime;
    private final java.util.Map<String,ReplicaControlLease> replicaOwners=new java.util.concurrent.ConcurrentHashMap<>();

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

    public ManagedDeploymentCoordinator(DeploymentProviderResolver resolver,FunctionRegistry registry,FunctionOperationLocks locks,
            FunctionCapacityRegistry generations,ReplicaStatusSnapshot snapshot,java.util.function.LongSupplier nanoTime) {
        this(resolver,registry,locks,generations,snapshot,false); this.nanoTime=java.util.Objects.requireNonNull(nanoTime);
    }
    private ReplicaControlLease ownerOf(String function) {
        var lease=replicaOwners.get(function);
        if(lease!=null && !lease.generation().equals(generations.activeGeneration(function))) {
            replicaOwners.remove(function,lease); return null;
        }
        return lease;
    }
    public void requireReplicaControlUnowned(String function) {
        locks.withLock(function,()-> { if(ownerOf(function)!=null) throw new ReplicaOwnershipException(function); return null; });
    }
    private long leaseDeadline(java.time.Duration ttl) {
        if(ttl==null || ttl.isZero() || ttl.isNegative() || ttl.compareTo(java.time.Duration.ofDays(1))>0) throw new IllegalArgumentException("positive lease TTL up to one day required");
        return nanoTime.getAsLong()+ttl.toNanos();
    }
    @Override public java.util.Optional<ReplicaControlLease> acquireReplicaLease(FunctionGeneration expected,String owner,java.time.Duration ttl) {
        long deadline=leaseDeadline(ttl);
        return locks.withLock(expected.functionName(),()-> {
            var function=registry.getRegistered(expected.functionName()).orElse(null);
            if(!expected.equals(generations.activeGeneration(expected.functionName())) || function==null || function.managedDeploymentTarget().isEmpty() || ownerOf(expected.functionName())!=null) return java.util.Optional.empty();
            registry.applicationState().requireAvailable(expected.functionName());
            var lease=new ReplicaControlLease(expected,java.util.UUID.randomUUID().toString(),owner,deadline);
            replicaOwners.put(expected.functionName(),lease); return java.util.Optional.of(lease);
        });
    }
    @Override public java.util.Optional<ReplicaControlLease> renewReplicaLease(ReplicaControlLease lease,java.time.Duration ttl) {
        long deadline=leaseDeadline(ttl);
        return locks.withLock(lease.generation().functionName(),()-> {
            if(!lease.equals(ownerOf(lease.generation().functionName())) || nanoTime.getAsLong()>=lease.deadlineNanos()) return java.util.Optional.empty();
            var renewed=new ReplicaControlLease(lease.generation(),java.util.UUID.randomUUID().toString(),lease.owner(),deadline);
            replicaOwners.put(lease.generation().functionName(),renewed); return java.util.Optional.of(renewed);
        });
    }
    @Override public boolean setReplicas(ReplicaControlLease lease,ManagedDeploymentTarget target,int replicas) {
        if(replicas<0 || !lease.generation().functionName().equals(target.functionName())) throw new IllegalArgumentException("invalid leased target");
        return locks.withLock(target.functionName(),()-> {
            if(!lease.equals(ownerOf(target.functionName())) || nanoTime.getAsLong()>=lease.deadlineNanos()) return false;
            return setReplicasLocked(target,replicas,lease);
        });
    }
    @Override public boolean drainAndReleaseReplicaLease(ReplicaControlLease lease,ManagedDeploymentTarget target) {
        if(!lease.generation().functionName().equals(target.functionName())) throw new IllegalArgumentException("invalid leased target");
        return locks.withLock(target.functionName(),()-> {
            if(!lease.equals(replicaOwners.get(target.functionName()))) return false;
            if(!lease.generation().equals(generations.activeGeneration(target.functionName()))) {
                replicaOwners.remove(target.functionName(),lease); return true;
            }
            // The expired owner may drain, but cannot grow or reuse the expired capability.
            if(!setReplicasLocked(target,0,lease)) return false;
            var status=getFreshReplicaStatus(target);
            if(status.desiredReplicas()!=0 || status.readyReplicas()!=0) return false;
            replicaOwners.remove(target.functionName(),lease); return true;
        });
    }
    @Override public boolean setReadyConcurrency(ReplicaControlLease lease,int readyReplicas) {
        if(readyReplicas<0) throw new IllegalArgumentException("negative ready replicas");
        var name=lease.generation().functionName();
        return locks.withLock(name,()-> {
            if(!lease.equals(ownerOf(name)) || nanoTime.getAsLong()>=lease.deadlineNanos() || readyReplicas>generations.configuredConcurrency(name)) return false;
            // Zero remains closed by the active routing plan; the legacy admission state has a minimum of one.
            generations.setEffectiveConcurrency(name,Math.max(1,readyReplicas));
            return true;
        });
    }
    @Override public boolean ownsReplicaLease(ReplicaControlLease lease) {
        return locks.withLock(lease.generation().functionName(),()->lease.equals(ownerOf(lease.generation().functionName())) && nanoTime.getAsLong()<lease.deadlineNanos());
    }
    @Override public boolean supportsPhysicalReplicaControl(ManagedDeploymentTarget target) {
        return requireProvider(target).supportsPhysicalReplicaControl(target.functionName());
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
    @Override
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
    @Override
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
    @Override
    @SuppressWarnings("ReferenceEquality") // A replacement with equal values is still a stale observation.
    public FunctionGeneration generationOf(RegisteredFunction observed) {
        return locks.withLock(observed.name(), () -> {
            if (registry.getRegistered(observed.name()).orElse(null) != observed) {
                return null;
            }
            return generations.activeGeneration(observed.name());
        });
    }

    private boolean setReplicasLocked(ManagedDeploymentTarget target,int replicas) { return setReplicasLocked(target,replicas,null); }
    private boolean setReplicasLocked(ManagedDeploymentTarget target, int replicas,ReplicaControlLease caller) {
        var owner=ownerOf(target.functionName());
        if(owner!=null && !owner.equals(caller)) throw new ReplicaOwnershipException(target.functionName());
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
