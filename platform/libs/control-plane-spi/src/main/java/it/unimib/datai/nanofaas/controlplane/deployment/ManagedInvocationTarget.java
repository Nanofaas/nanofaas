package it.unimib.datai.nanofaas.controlplane.deployment;

import java.util.concurrent.CompletionStage;

/** A provider-owned reservation, distinct from the lifetime of the HTTP request. */
public interface ManagedInvocationTarget extends AutoCloseable {
    /** Preserves an accepted attempt through allocation quiescence within its original deadline. */
    default CompletionStage<Void> ready() { return java.util.concurrent.CompletableFuture.completedFuture(null); }
    String endpointUrl();
    String replicaId();

    /** Atomically commits sending, or refuses a cancelled/draining reservation. */
    boolean markDispatched();

    /** Correlated provider/SDK evidence, never an arbitrary upstream response header. */
    CompletionStage<Boolean> executionEvidence();

    /** Idempotent; after sending, physical leases require positive release proof. */
    @Override void close();
}
