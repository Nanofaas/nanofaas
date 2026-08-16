package it.unimib.datai.nanofaas.common.model;

public enum ConcurrencyControlMode {
    /** The configured concurrency is used as-is. */
    FIXED,
    /** {@code readyReplicas x targetInFlightPerPod}, clamped to the configured concurrency. */
    STATIC_PER_POD,
    /** Per-replica target moved by a latency gradient against the function's own best. */
    ADAPTIVE_PER_POD,
    /**
     * Per-function latency SLO, served out of a concurrency budget shared by every function.
     *
     * <p>The other adaptive mode asks each function to find its own limit against a resource it
     * shares with the rest, which is a decision no function has the information to make: measured,
     * one function's load moved its neighbour's limit while the neighbour's own load was
     * unchanged. This mode splits the question in two — how much a function needs to meet its SLO,
     * and how much the platform can give it — so that the sum of the limits cannot exceed what the
     * platform has.</p>
     */
    BUDGETED
}
