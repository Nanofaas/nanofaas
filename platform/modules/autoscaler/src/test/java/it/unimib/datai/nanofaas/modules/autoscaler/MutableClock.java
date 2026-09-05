package it.unimib.datai.nanofaas.modules.autoscaler;

import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Steerable wall-clock for autoscaler tests, so cooldowns and the lack-of-progress window can
 * be crossed deterministically instead of sleeping (plan §2 test discipline). Wall-clock only:
 * the autoscaler's cooldown and progress decisions are all wall-clock based.
 */
final class MutableClock {
    private final AtomicLong epochMillis;

    MutableClock(long startEpochMillis) {
        this.epochMillis = new AtomicLong(startEpochMillis);
    }

    InstantSource instantSource() {
        return () -> Instant.ofEpochMilli(epochMillis.get());
    }

    Instant instant() {
        return Instant.ofEpochMilli(epochMillis.get());
    }

    void advanceMillis(long millis) {
        epochMillis.addAndGet(millis);
    }
}
