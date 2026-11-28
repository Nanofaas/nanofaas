package it.unimib.datai.nanofaas.controlplane.service;

/**
 * Another request still holds the idempotency key's claim after the function's own timeout,
 * so this duplicate stops waiting for it instead of parking a thread until the claim expires.
 * The client may retry the same key later (HTTP 409).
 */
public final class IdempotencyKeyBusyException extends RuntimeException {
    public IdempotencyKeyBusyException() {
        super("Idempotency key is still claimed by another request");
    }
}
