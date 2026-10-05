package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaObservation;

/**
 * What a scaling control loop may do to a managed deployment's replicas.
 *
 * <p>It lives beside the catalog rather than beside the deployment DTOs because it names a
 * {@link RegisteredFunction}: the registry already depends on the deployment package, and putting
 * the port the other way round would close a package cycle.</p>
 *
 * <p>Reading is separated from acting on purpose. {@link #observeReplicaStatus} never blocks on
 * the provider and never reports a missing reading as zero replicas (invariant I9); the caller
 * narrows the observation before trusting a number. Mutation is generation-fenced, so a decision
 * computed for one registration cannot be applied to the function that replaced it.</p>
 */
public interface ManagedReplicaControl {

    default java.util.Optional<ReplicaControlLease> acquireReplicaLease(FunctionGeneration generation,String owner,java.time.Duration ttl) { return java.util.Optional.empty(); }
    default java.util.Optional<ReplicaControlLease> renewReplicaLease(ReplicaControlLease lease,java.time.Duration ttl) { return java.util.Optional.empty(); }
    default boolean setReplicas(ReplicaControlLease lease,ManagedDeploymentTarget target,int replicas) { return false; }
    default boolean drainAndReleaseReplicaLease(ReplicaControlLease lease,ManagedDeploymentTarget target) { return false; }
    /** Sets the node-wide admission cap from physically ready replicas, under the same generation fence. */
    default boolean setReadyConcurrency(ReplicaControlLease lease,int readyReplicas) { return false; }
    default boolean ownsReplicaLease(ReplicaControlLease lease) { return false; }
    default boolean supportsPhysicalReplicaControl(ManagedDeploymentTarget target) { return false; }

    /** The latest observation for {@code target}: fresh, stale, or explicitly unavailable. */
    ReplicaObservation observeReplicaStatus(ManagedDeploymentTarget target);

    /**
     * Applies a replica target only while {@code expectedGeneration} is still the active one.
     *
     * @return {@code false} when the generation is gone (removed, or re-registered since the
     *         decision was computed), {@code true} once the change has been applied
     */
    boolean setReplicas(FunctionGeneration expectedGeneration, ManagedDeploymentTarget target, int replicas);

    /**
     * The generation of {@code observed}, or {@code null} when the catalog entry is no longer that
     * exact object — which is how a control loop learns that its snapshot is stale before acting.
     */
    FunctionGeneration generationOf(RegisteredFunction observed);
}
