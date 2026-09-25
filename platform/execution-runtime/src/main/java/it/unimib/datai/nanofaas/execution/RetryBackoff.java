package it.unimib.datai.nanofaas.execution;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/** Calculates the next retry's eligibility from a recorded failure time. */
public final class RetryBackoff {
    private final Duration initial;
    private final Duration maximum;
    private final DoubleSupplier random;

    public RetryBackoff(Duration initial, Duration maximum, DoubleSupplier random) {
        if (initial == null || initial.isZero() || initial.isNegative()
                || maximum == null || maximum.isZero() || maximum.isNegative()
                || maximum.compareTo(initial) < 0) {
            throw new IllegalArgumentException("retry backoff durations must be positive and ordered");
        }
        this.initial = initial;
        this.maximum = maximum;
        this.random = Objects.requireNonNull(random, "random");
    }

    public Instant notBefore(int failedAttempt, Instant observedAt, Instant upstreamHint) {
        if (failedAttempt < 1) {
            throw new IllegalArgumentException("failedAttempt must be positive");
        }
        Objects.requireNonNull(observedAt, "observedAt");

        Duration base = initial;
        for (int n = 1; n < failedAttempt && base.compareTo(maximum) < 0; n++) {
            base = base.compareTo(maximum.dividedBy(2)) > 0 ? maximum : base.multipliedBy(2);
        }
        Duration lower = base.dividedBy(2);
        Duration width = base.minus(lower);
        double sample = random.getAsDouble();
        if (!(sample >= 0.0 && sample < 1.0)) {
            throw new IllegalArgumentException("random outside [0,1)");
        }
        double seconds = width.getSeconds() * sample;
        long whole = (long) seconds;
        long nanos = (long) ((seconds - whole) * 1_000_000_000L + width.getNano() * sample);
        Duration extra = Duration.ofSeconds(whole, nanos);
        if (extra.compareTo(width) > 0) {
            extra = width;
        }
        Duration delay = lower.plus(extra);
        if (delay.isZero()) {
            delay = Duration.ofNanos(1);
        }
        Instant local;
        try {
            local = observedAt.plus(delay);
        } catch (DateTimeException | ArithmeticException overflow) {
            local = Instant.MAX;
        }
        return upstreamHint != null && upstreamHint.isAfter(local) ? upstreamHint : local;
    }
}
