package it.unimib.datai.nanofaas.controlplane.capacity;

/**
 * The handle for capacity already acquired by an attempt. The existing lease
 * implements this contract; transporting the handle neither acquires nor copies
 * ownership. Release is idempotent and applies only to the acquired generation.
 * Before dispatch the scheduler owns cleanup; after dispatch physical work owns
 * release, even when the caller's wait or the attempt deadline has elapsed.
 */
public interface DispatchOwnership {
    FunctionGeneration generation();
    void release();
    boolean isReleased();
}
