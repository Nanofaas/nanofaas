package it.unimib.datai.nanofaas.sdk.runtime;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Component
/**
 * Captures the first-request boundary for cold-start metadata.
 *
 * <p>The tracker marks when the first request arrives so the invoke controller can report whether
 * the current execution observed a cold start and how long initialization took before work began.
 * This state is scoped to the container lifetime, not to the Spring application shutdown path.</p>
 */
public class ColdStartTracker {

    private final AtomicBoolean firstInvocation = new AtomicBoolean(true);
    private final long containerStartMs = Instant.now().toEpochMilli();
    // -1 = not yet captured; set exactly once, before handler execution
    private final AtomicLong firstRequestArrivalMs = new AtomicLong(-1);

    /**
     * Returns true if this is the first invocation (cold start). Idempotent thereafter.
     */
    public boolean firstInvocation() {
        return firstInvocation.compareAndSet(true, false);
    }

    /**
     * Captures the timestamp of the first request arrival.
     * Must be called BEFORE handler execution. Idempotent: only the first call has effect.
     */
    public void markFirstRequestArrival() {
        firstRequestArrivalMs.compareAndSet(-1, Instant.now().toEpochMilli());
    }

    /**
     * Duration from container start to first request arrival (excludes handler execution time).
     * Returns -1 if {@link #markFirstRequestArrival()} has not been called yet.
     */
    public long initDurationMs() {
        long arrival = firstRequestArrivalMs.get();
        if (arrival < 0) {
            return -1;
        }
        return arrival - containerStartMs;
    }
}
