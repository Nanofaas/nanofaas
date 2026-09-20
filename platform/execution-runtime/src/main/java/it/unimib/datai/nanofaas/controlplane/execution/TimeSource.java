package it.unimib.datai.nanofaas.controlplane.execution;

import java.time.Instant;
import java.time.InstantSource;
import java.util.function.LongSupplier;

/**
 * One source for the two different questions the execution lifecycle asks about time.
 *
 * <p>Wall-clock {@link Instant} is for the instants exposed in the API — the status a caller
 * reads back, the archived outcome. Monotonic {@link #nanoTime()} is for durations, which must
 * never be corrupted by an NTP step or a manual clock change. The two are deliberately not one:
 * they answer different questions, and conflating them is what turns a harmless clock adjustment
 * into a nonsense latency sample.</p>
 *
 * <p>Injectable so tests can steer time instead of sleeping (see the plan's test discipline);
 * production uses {@link #system()}.</p>
 */
public final class TimeSource {
    private static final TimeSource SYSTEM =
            new TimeSource(InstantSource.system(), System::nanoTime);

    private final InstantSource instantSource;
    private final LongSupplier nanoTime;

    public TimeSource(InstantSource instantSource, LongSupplier nanoTime) {
        this.instantSource = instantSource;
        this.nanoTime = nanoTime;
    }

    public static TimeSource system() {
        return SYSTEM;
    }

    /** Wall-clock now, for the instants exposed in the API. */
    public Instant instant() {
        return instantSource.instant();
    }

    /** Monotonic now, for durations. */
    public long nanoTime() {
        return nanoTime.getAsLong();
    }
}
