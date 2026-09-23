package it.unimib.datai.nanofaas.controlplane.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Thread-safe per-second rate limiter.
 *
 * <p>The windowing semantics are fixed-width and non-smoothing: up to
 * {@link #maxPerSecond} requests are admitted in any given epoch-second window, and the window
 * rolls over at the first request observed after a second boundary. This is <em>not</em> a
 * token bucket: two requests that arrive back-to-back across a window boundary can both be
 * admitted even though they are nanoseconds apart, because each one lands in a different
 * second window. The limiter therefore bounds the rate over each individual second, but does
 * not smooth bursts that straddle a boundary; a caller that needs a sustained, burst-free cap
 * (e.g. {@code maxPerSecond} averaged over a sliding interval) must layer a token bucket or
 * similar on top.
 *
 * <p>{@link #maxPerSecond} is volatile and is re-read on every call, so the limit can be
 * changed at runtime (the admin runtime-config endpoint does so) and takes effect on the next
 * admission decision without any extra synchronization.
 */
@Component
@ConfigurationProperties(prefix = "nanofaas.rate")
public class RateLimiter {

    /**
     * Window start (epoch second) and admission count for that window are kept in a single
     * {@link AtomicLong}: the epoch-second window occupies the upper 33 bits, the count the
     * lower 31. A window rollover and the counter reset are therefore one CAS transition and
     * no caller can ever observe the window advanced while the counter still holds the
     * previous window's value (the race that used to wrongly reject fresh-window requests and
     * to let a reset wipe increments made after the rollover).
     *
     * <p>31 count bits are enough for every positive int {@code maxPerSecond} (up to
     * {@link Integer#MAX_VALUE}); the admission loop never stores a count above the limit, so
     * the counter can never overflow into the window bits. 33 window bits address epoch
     * seconds up to roughly the year 2242, after which the window field would overflow.
     */
    private static final int COUNT_BITS = 31;
    private static final long COUNT_MASK = (1L << COUNT_BITS) - 1L;

    private volatile int maxPerSecond = 1_000_000;

    private final AtomicLong windowAndCount;
    private final LongSupplier epochSecondSource;

    @Autowired
    public RateLimiter() {
        this(() -> Instant.now().getEpochSecond());
    }

    /**
     * Package-private for tests: allows a controllable time reference so window-boundary
     * behaviour can be verified without sleeping on the real clock.
     */
    RateLimiter(LongSupplier epochSecondSource) {
        this.epochSecondSource = epochSecondSource;
        this.windowAndCount = new AtomicLong(encode(epochSecondSource.getAsLong(), 0L));
    }

    /**
     * Returns {@code true} if a request is admitted in the current per-second window.
     *
     * <p>The decision is a lock-free CAS loop over the single packed state. When the clock has
     * moved past the stored window, the state is swapped atomically to the new window seeded
     * with a count of 1 — the calling request is the new window's first admission, so the
     * rollover never discards an admission and never lets another thread observe a stale
     * counter. When the window is current, a request is admitted by CAS-incrementing the
     * count only if it is still below the (volatile) {@link #maxPerSecond}; once the count is
     * at the limit the request is rejected without touching the shared state.
     */
    public boolean allow() {
        long max = maxPerSecond;
        // The clock is read once per decision, not per CAS retry: re-reading it on every
        // retry would make contention pay for an expensive Instant.now() each time it lost
        // the CAS. A slightly stale `now` can only mean this call skips the rollover branch
        // and is counted in the already-current window, which never over-admits.
        long now = epochSecondSource.getAsLong();
        while (true) {
            long state = windowAndCount.get();
            long window = state >>> COUNT_BITS;
            long count = state & COUNT_MASK;
            if (now > window) {
                // New second window: claim it in the same CAS that seeds its count at 1.
                long freshWindow = encode(now, 1L);
                if (windowAndCount.compareAndSet(state, freshWindow)) {
                    return 1L <= max;
                }
                continue; // another thread rolled the window first; re-read its state
            }
            if (count >= max) {
                return false;
            }
            // count < max <= Integer.MAX_VALUE < 2^31, so +1 never carries into the window bits.
            if (windowAndCount.compareAndSet(state, state + 1L)) {
                return true;
            }
            // Lost the increment race; re-read and retry.
        }
    }

    private static long encode(long window, long count) {
        return (window << COUNT_BITS) | count;
    }

    public int getMaxPerSecond() {
        return maxPerSecond;
    }

    public void setMaxPerSecond(int maxPerSecond) {
        this.maxPerSecond = maxPerSecond;
    }
}
