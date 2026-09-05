package it.unimib.datai.nanofaas.controlplane.execution;

import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Steerable clock for tests: the wall-clock epoch millis and the monotonic nanos move together, so
 * a test can advance "now" deterministically instead of sleeping (plan §2 test discipline). A
 * duration is then exactly the wall-clock distance the test advanced, which is what makes assertions
 * on the recorded timers exact rather than approximate.
 */
public final class MutableClock {
    private final AtomicLong epochMillis;
    private final AtomicLong nanos;

    public MutableClock(long startEpochMillis, long startNanos) {
        this.epochMillis = new AtomicLong(startEpochMillis);
        this.nanos = new AtomicLong(startNanos);
    }

    /** A {@link TimeSource} bound to this clock, for records created at the current "now". */
    public TimeSource source() {
        InstantSource instants = () -> Instant.ofEpochMilli(epochMillis.get());
        return new TimeSource(instants, nanos::get);
    }

    /** Advances both clocks by the same amount, as if wall-clock time passed. */
    public void advanceMillis(long millis) {
        epochMillis.addAndGet(millis);
        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
    }

    public Instant instant() {
        return Instant.ofEpochMilli(epochMillis.get());
    }

    public long nanos() {
        return nanos.get();
    }
}
