package it.unimib.datai.nanofaas.controlplane.deployment;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * What a periodic consumer knows about a managed deployment's replicas at a point in time.
 *
 * <p>The type is sealed on the one distinction that changes what a caller may legally do:
 * {@link Available} carries a measurement, {@link Unavailable} carries none. There is no accessor
 * anywhere on this interface that yields a {@link ReplicaStatus} without first narrowing to
 * {@code Available}, so "no reading from the provider" cannot silently become
 * {@code readyReplicas = 0} — invariant I9. The finer FRESH/STALE distinction rides along as
 * {@link #state()} because it changes how much a reader should trust the number, not whether the
 * number exists at all.</p>
 *
 * <p>Immutable and free of exception chains on purpose: an observation is retained per function
 * inside {@link ReplicaStatusSnapshot}, so a failure is summarised as a short reason string rather
 * than a {@link Throwable} whose stack trace would be retained with it.</p>
 */
public sealed interface ReplicaObservation {

    /** How much the reported reading can be trusted; {@code UNAVAILABLE} means there is none. */
    enum State {
        /** Read from the provider within the snapshot's TTL. */
        FRESH,
        /** Past the TTL but still inside the documented stale bound; a refresh has been scheduled. */
        STALE,
        /** No usable reading: never fetched, or older than the stale bound, or the fetch failed. */
        UNAVAILABLE
    }

    State state();

    /**
     * For {@link Available}, when the reported value was read from the provider. For
     * {@link Unavailable}, when the absence of a usable value was established.
     */
    Instant observedAt();

    /** Age of this observation as of {@code now}; never negative. */
    default Duration age(Instant now) {
        Duration age = Duration.between(observedAt(), now);
        return age.isNegative() ? Duration.ZERO : age;
    }

    /** {@code true} exactly when this observation carries a measurement. */
    default boolean isUsable() {
        return this instanceof Available;
    }

    /** A reading from the provider, either {@link State#FRESH} or {@link State#STALE}. */
    record Available(ReplicaStatus status, State state, Instant observedAt) implements ReplicaObservation {
        public Available {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(observedAt, "observedAt");
            if (state != State.FRESH && state != State.STALE) {
                throw new IllegalArgumentException("an available observation is FRESH or STALE, not " + state);
            }
        }
    }

    /**
     * No reading a consumer may act on. {@code reason} is a short, human-readable summary for logs
     * and never a replica count.
     */
    record Unavailable(Instant observedAt, String reason) implements ReplicaObservation {
        public Unavailable {
            Objects.requireNonNull(observedAt, "observedAt");
            reason = reason == null || reason.isBlank() ? "no reading from the deployment provider" : reason;
        }

        @Override
        public State state() {
            return State.UNAVAILABLE;
        }
    }

    static ReplicaObservation fresh(ReplicaStatus status, Instant observedAt) {
        return new Available(status, State.FRESH, observedAt);
    }

    static ReplicaObservation stale(ReplicaStatus status, Instant observedAt) {
        return new Available(status, State.STALE, observedAt);
    }

    static ReplicaObservation unavailable(Instant observedAt, String reason) {
        return new Unavailable(observedAt, reason);
    }
}
