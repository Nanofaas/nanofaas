package it.unimib.datai.nanofaas.controlplane.deployment;

import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Steerable wall-clock for snapshot tests, so the TTL boundary can be crossed deterministically
 * instead of sleeping (plan §2 test discipline). Wall-clock only: the replica snapshot's freshness
 * decision is entirely wall-clock based.
 */
public final class MutableInstantSource {
    private final AtomicLong epochMillis;

    public MutableInstantSource(long startEpochMillis) {
        this.epochMillis = new AtomicLong(startEpochMillis);
    }

    public InstantSource instantSource() {
        return () -> Instant.ofEpochMilli(epochMillis.get());
    }

    public Instant instant() {
        return Instant.ofEpochMilli(epochMillis.get());
    }

    public void advanceMillis(long millis) {
        epochMillis.addAndGet(millis);
    }
}
