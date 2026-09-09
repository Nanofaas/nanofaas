package it.unimib.datai.nanofaas.controlplane.capacity;

import java.util.Objects;

/**
 * The lifecycle scope that decides when one resource reservation is released.
 *
 * <p>This is diagnostic ownership, not a second capacity lease. A physical
 * attempt's dispatch slot remains owned and released exclusively through its
 * {@link DispatchLease}; P07 quotas use this value to distinguish any additional
 * retained resource (for example an input copy) from logical execution and waiter
 * lifetimes.
 *
 * @param scope    lifecycle that owns the reservation
 * @param identity stable identity inside that scope, never used as a public key or metric tag
 */
public record ResourceOwner(Scope scope, String identity) {

    public ResourceOwner {
        Objects.requireNonNull(scope, "scope");
        if (identity == null || identity.isBlank()) {
            throw new IllegalArgumentException("identity must not be blank");
        }
    }

    public enum Scope {
        LOGICAL_EXECUTION,
        PHYSICAL_ATTEMPT,
        INPUT_COPY,
        WAITER
    }
}
