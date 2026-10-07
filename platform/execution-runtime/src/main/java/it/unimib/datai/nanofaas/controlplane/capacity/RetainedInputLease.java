package it.unimib.datai.nanofaas.controlplane.capacity;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One canonical-input reservation shared by a bounded number of physical readers.
 * The base owner and each reader release one reference; quota drains only at zero.
 */
public final class RetainedInputLease implements AutoCloseable {
    private final ResourceQuota.Reservation reservation;
    private final int maxReferences;
    private int references = 1;
    private boolean baseReleased;

    RetainedInputLease(ResourceQuota.Reservation reservation, int maxReferences) {
        this.reservation = Objects.requireNonNull(reservation, "reservation");
        if (maxReferences < 1) throw new IllegalArgumentException("maxReferences must be positive");
        this.maxReferences = maxReferences;
    }

    public synchronized Reference retain(ResourceOwner owner) {
        Objects.requireNonNull(owner, "owner");
        if (baseReleased) {
            throw new IllegalStateException("canonical input no longer accepts readers");
        }
        if (references >= maxReferences) {
            throw new IllegalStateException("canonical input reader limit reached");
        }
        references++;
        return new Reference(owner);
    }

    public FunctionGeneration generation() {
        return reservation.generation();
    }

    public long units() {
        return reservation.units();
    }

    public synchronized int references() {
        return references;
    }

    @Override
    public void close() {
        boolean drained;
        synchronized (this) {
            if (baseReleased) return;
            baseReleased = true;
            references--;
            drained = references == 0;
        }
        if (drained) reservation.close();
    }

    private void releaseReference() { // NOSONAR (java:S3398): updates the lease's own reference count; the reference only delegates
        boolean drained;
        synchronized (this) {
            if (references == 0) return;
            references--;
            drained = references == 0;
        }
        if (drained) reservation.close();
    }

    public final class Reference implements AutoCloseable {
        private final ResourceOwner owner;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Reference(ResourceOwner owner) {
            this.owner = owner;
        }

        public ResourceOwner owner() {
            return owner;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) releaseReference();
        }
    }
}
