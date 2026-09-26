package it.unimib.datai.nanofaas.controlplane.capacity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Rollback scope for an admission that needs more than one quota reservation.
 *
 * <p>Successful reservations are tracked until {@link #commit()}. Closing an
 * uncommitted batch releases them in reverse order, including when a later quota
 * is saturated or publication throws. A committed batch drops its references and
 * leaves each reservation with the published owner that received its handle.
 */
public final class ReservationBatch implements AutoCloseable {

    private final List<ResourceQuota.Reservation> reservations = new ArrayList<>();
    private State state = State.OPEN;

    public Optional<ResourceQuota.Reservation> tryReserve(
            ResourceQuota quota,
            FunctionGeneration generation,
            ResourceOwner owner,
            long units) {
        requireOpen();
        Optional<ResourceQuota.Reservation> reservation =
                Objects.requireNonNull(quota, "quota").tryReserve(generation, owner, units);
        reservation.ifPresent(reservations::add);
        return reservation;
    }

    /** Transfers rollback ownership to the callers holding the reservation handles. */
    public void commit() {
        requireOpen();
        state = State.COMMITTED;
        reservations.clear();
    }

    @Override
    public void close() {
        if (state == State.CLOSED) {
            return;
        }
        if (state == State.OPEN) {
            for (int i = reservations.size() - 1; i >= 0; i--) {
                reservations.get(i).close();
            }
            reservations.clear();
        }
        state = State.CLOSED;
    }

    private void requireOpen() {
        if (state != State.OPEN) {
            throw new IllegalStateException("reservation batch is no longer open");
        }
    }

    private enum State {
        OPEN,
        COMMITTED,
        CLOSED
    }
}
