package it.unimib.datai.nanofaas.containerdeployment;

import java.time.Duration;
import java.util.List;

public interface ManagedReplicaSet extends AutoCloseable {
    default it.unimib.datai.nanofaas.controlplane.deployment.ManagedInvocationTarget acquire(String executionId, String attempt) { return null; }
    default void updateReadyBackends(List<String> ready) { }
    default void updateLimits(int global, int perReplica, Duration timeout) { updateLimits(global, timeout); }

    void updateBackends(List<String> backendBaseUrls);

    /** Updates admission and the deadline retained by accepted allocation waiters. */
    default void updateLimits(int maxInFlight, Duration singleHopTimeout) {
    }

    default void enablePhysicalSlots() { throw new UnsupportedOperationException("Physical replica slots unsupported"); }
    default boolean backendReady(String backendId) { return true; }
    default void beginDrain(String backendId) { }
    default boolean awaitDrained(String backendId, Duration timeout) { return true; }
    default ReplicaSlots.QuiesceToken beginQuiesce() {
        throw new UnsupportedOperationException("Epoch replica fencing unsupported");
    }
    default boolean awaitQuiesced(Duration timeout) {
        throw new UnsupportedOperationException("Epoch replica fencing unsupported");
    }
    default boolean resume(ReplicaSlots.QuiesceToken token, String admissionEpoch) {
        throw new UnsupportedOperationException("Epoch replica fencing unsupported");
    }

    /** Rotates admission on unchanged allocations without releasing occupied physical leases. */
    default boolean resumeRetained(ReplicaSlots.QuiesceToken token, String admissionEpoch) {
        throw new UnsupportedOperationException("Retained epoch replica fencing unsupported");
    }

    @Override
    void close();
}
