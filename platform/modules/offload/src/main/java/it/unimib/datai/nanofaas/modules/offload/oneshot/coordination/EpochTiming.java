package it.unimib.datai.nanofaas.modules.offload.oneshot.coordination;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Canonical UTC labels for correlation; permissions use only local monotonic durations. */
public record EpochTiming(String sessionId, Instant startsAt, Instant endsAt, long startedNanos,
                          long activationDeadline, long expiryDeadline, LongSupplier nanos) {
    public EpochTiming {
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 256)
            throw new IllegalArgumentException("invalid session identity");
        Objects.requireNonNull(nanos);
        if (startsAt == null || endsAt == null || !startsAt.isBefore(endsAt)
                || Duration.between(startsAt, endsAt).compareTo(Duration.ofDays(1)) > 0
                || activationDeadline - startedNanos <= 0
                || activationDeadline - startedNanos > Duration.ofDays(1).toNanos()
                || expiryDeadline - activationDeadline != Duration.between(startsAt, endsAt).toNanos())
            throw new IllegalArgumentException("invalid logical epoch timing");
    }
    public static EpochTiming capture(String sessionId, Instant from, Instant until,
                                      Duration activationDelay, LongSupplier nanos) {
        if (activationDelay == null || activationDelay.isNegative() || activationDelay.isZero()
                || activationDelay.compareTo(Duration.ofDays(1)) > 0 || from == null || until == null)
            throw new IllegalArgumentException("invalid activation delay or labels");
        long start = nanos.getAsLong();
        long activation = start + activationDelay.toNanos();
        return new EpochTiming(sessionId, from, until, start, activation,
                activation + Duration.between(from, until).toNanos(), nanos);
    }
    public long auctionDeadline(Duration budget) {
        if (budget == null || budget.isNegative() || budget.isZero() || budget.compareTo(Duration.ofDays(1)) > 0)
            throw new IllegalArgumentException("invalid auction budget");
        return startedNanos + Math.min(budget.toNanos(), activationDeadline - startedNanos);
    }
    public boolean expired(long deadline) { return nanos.getAsLong() - deadline >= 0; }
    public Duration remaining(long deadline) { return Duration.ofNanos(Math.max(0, deadline - nanos.getAsLong())); }
}
