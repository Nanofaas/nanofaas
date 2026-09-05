package it.unimib.datai.nanofaas.controlplane.service;

/**
 * The idempotency-key/tombstone budget ({@code nanofaas.execution-store.max-keys})
 * is exhausted, so a <b>new</b> keyed admission is refused before dispatch. Replays
 * of keys already held are unaffected and stay serviceable.
 */
public final class IdempotencyBudgetExhaustedException extends RuntimeException {
    public IdempotencyBudgetExhaustedException() {
        super("Idempotency key budget exhausted");
    }
}
