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
    BUDGETED,
    /**
     * Per-function limit chosen to minimise the time a caller spends in the system.
     *
     * <p>Every other mode decides from service time, which is what the control plane measures
     * between dispatch and completion. That is not what a caller experiences: under queueing the
     * wait was measured at 37-43ms against a service time near 5ms, so a controller can sit inside
     * a 10ms service SLO while its callers wait eighty. The wait is not incidental — it is the
     * direct product of the limit the controller chose.</p>
     *
     * <p>Service time cannot be optimised for that reason: it rises monotonically with concurrency,
     * so a rule that shrinks the limit when latency rises has no interior optimum and walks to its
     * floor, which two runtimes were measured doing. Sojourn time — wait plus service — does have
     * one, because the wait falls as the limit rises while the service time climbs. So this mode
     * searches for that minimum by moving and observing rather than by applying a threshold, and
     * the operating point it settles on is the knee itself, found rather than estimated.</p>
     *
     * <p>{@code targetLatencyMs} here is an end-to-end promise, which is what an SLO usually means
     * when somebody states one. Inside it the limit is left alone.</p>
     */
    SOJOURN
}
