package it.unimib.datai.nanofaas.controlplane.offload;

/**
 * The offload counters a gateway may record against, without access to the meter registry.
 *
 * <p>An offload gateway needs to count attempts and failures, not to register or remove meters.
 * It receives a lease bound to the generation that was active when the offload started: a lease
 * belonging to a retired generation silently drops its updates instead of resurrecting a series
 * for a function that was removed (invariant I7).</p>
 */
public interface OffloadMeters {

    /** A lease for one offload attempt on {@code function}, to be closed when the attempt ends. */
    OffloadMeterLease offloadMeters(String function, OffloadTrigger trigger);

    /**
     * Lifecycle handle for one offload attempt's counters.
     *
     * <p>The three calls follow the reactive sequence they are named for: the attempt is counted
     * when the request is actually subscribed rather than when the pipeline is assembled, a
     * failure is counted only for an attempt that had been counted, and {@code close} releases
     * the generation's meters once. It exposes no generation tag, by design.</p>
     */
    interface OffloadMeterLease extends AutoCloseable {

        /** Counts the attempt, at subscribe time. */
        void subscribed();

        /** Counts a failure of an attempt that was counted. */
        void failed();

        @Override
        void close();
    }
}
