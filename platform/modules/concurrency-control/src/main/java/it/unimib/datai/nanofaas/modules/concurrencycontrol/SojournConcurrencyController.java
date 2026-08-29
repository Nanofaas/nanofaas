package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import it.unimib.datai.nanofaas.common.model.ConcurrencyControlConfig;
import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadCapacityController;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsSource;

/**
 * Computes the limit a function needs, from what the caller experiences rather than from service
 * time alone.
 *
 * <p>The other controllers decide from {@code function_latency_ms}, which is dispatch to completion
 * and therefore service time. That is about a seventh of what a caller waits: measured under
 * queueing, the wait was 37-43ms against a service time near 5ms, and the missing six-sevenths is
 * produced by the limit the controller itself chose.</p>
 *
 * <p><strong>Two designs were tried before this one and both are recorded here, because each fails
 * for a reason that is easy to walk back into.</strong></p>
 *
 * <p>Feeding end-to-end latency into the existing gradient rule is unstable. That rule shrinks the
 * limit when latency exceeds its target; a smaller limit drains the queue more slowly, so the wait
 * grows, so it shrinks again, to the floor.</p>
 *
 * <p>Searching for the minimum by stepping and comparing — better than last time, carry on; worse,
 * turn round — was implemented and measured, and it fails because it cannot tell "my move helped"
 * from "the load fell". In a trough it credited the natural relief to its own decision to shrink and
 * was still small when the load returned: 36,181 rejected requests against 32 for the mode it was
 * meant to improve on. Comparing two moments assumes the world held still between them.</p>
 *
 * <p><strong>So this computes instead of comparing</strong>, in three parts with a strict order of
 * authority.</p>
 *
 * <ol>
 *   <li><em>Admission.</em> The limit does two jobs, and the second one was missed: together with
 *   the queue it is the admission window, since a function can hold {@code limit + queueSize}
 *   requests and refuses everything past that. So a queue approaching its ceiling raises the limit
 *   and forbids lowering it, whatever the latency arithmetic prefers — refusing traffic is worse
 *   than being slow, and that ordering is not a tuning parameter.</li>
 *   <li><em>Little's law.</em> Sustaining an arrival rate needs {@code rate x service time} in
 *   flight. The rate is predicted rather than measured so the limit leads the load instead of
 *   trailing it, and the backlog already in the queue is added on top as something to drain.</li>
 *   <li><em>The promise.</em> {@code targetLatencyMs} is end-to-end here. Outside it the queue is
 *   drained harder — a shorter horizon, hence more concurrency — because the wait is the only part
 *   of the caller's latency a limit can actually shorten.</li>
 * </ol>
 *
 * <p>Nothing here compares two intervals, so the attribution problem that sank the search cannot
 * arise. ponytail: no explicit knee cap — the Little term already asks for no more than the load
 * needs, and the configured ceiling bounds the rest. Add one if a function is seen climbing while
 * its throughput stays flat.</p>
 */
public class SojournConcurrencyController {

    @Deprecated
    public int apply(FunctionObservation observation,
                     it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsSource source,
                     long nowEpochMs) {
        return apply(observation, source, (it.unimib.datai.nanofaas.workloadmetrics.WorkloadCapacityController) source,
                new ConcurrencyControlMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), source), nowEpochMs);
    }

    /**
     * The share of the queue that counts as pressure. Below it the buffer is doing its job of
     * absorbing bursts; above it the next burst is refused, and the limit is the only lever left.
     */
    private static final double HIGH_WATER = 0.7;

    /** Seconds allowed to drain a backlog when the function is meeting its promise. */
    private static final double DRAIN_HORIZON_SECONDS = 5.0;

    /** The shortest that horizon is allowed to become however far outside the promise it is. */
    private static final double MIN_DRAIN_HORIZON_SECONDS = 1.0;

    private final LatencyIntervals sojournIntervals;
    private final LatencyIntervals serviceIntervals;
    private final LoadForecast forecast;

    public SojournConcurrencyController() {
        this(new LatencyIntervals(), new LatencyIntervals(), new LoadForecast());
    }

    SojournConcurrencyController(
            LatencyIntervals sojournIntervals,
            LatencyIntervals serviceIntervals,
            LoadForecast forecast) {
        this.sojournIntervals = sojournIntervals;
        this.serviceIntervals = serviceIntervals;
        this.forecast = forecast;
    }

    /**
     * One function's inputs for a tick.
     *
     * @param e2eCount        cumulative count of the end-to-end timer, enqueue to completion
     * @param serviceCount    cumulative count of the service timer, dispatch to completion
     */
    public record FunctionObservation(
            FunctionSpec spec,
            int inFlight,
            long e2eCount,
            double e2eTotalMs,
            long serviceCount,
            double serviceTotalMs
    ) {
    }

    /**
     * @return the limit granted, so a caller can log or assert on it without reading it back
     */
    public int apply(
            FunctionObservation observation, WorkloadMetricsSource metricsSource,
            WorkloadCapacityController capacityController, ConcurrencyControlMetrics concurrencyMetrics,
            long nowEpochMs) {
        FunctionSpec spec = observation.spec();
        Bounds bounds = Bounds.of(spec);
        String name = spec.name();

        LatencyIntervals.Interval service = serviceIntervals.sample(
                name, observation.serviceCount(), observation.serviceTotalMs(), nowEpochMs);
        LatencyIntervals.Interval sojourn = sojournIntervals.sample(
                name, observation.e2eCount(), observation.e2eTotalMs(), nowEpochMs);
        int queueDepth = metricsSource.queueDepth(name);

        int limit = decide(bounds, service, sojourn.meanLatencyMs(), queueDepth,
                observation.inFlight());

        capacityController.setEffectiveConcurrency(name, limit);
        concurrencyMetrics.update(name, ConcurrencyControlMode.SOJOURN, limit);
        return limit;
    }

    private int decide(
            Bounds bounds,
            LatencyIntervals.Interval service,
            double sojournMs,
            int queueDepth,
            int inFlight) {
        // An interval in which nothing completed says nothing about what the function needs, so the
        // admission guard is still consulted and the model is not.
        int needed = service.meanLatencyMs() <= 0
                ? Math.max(inFlight, bounds.floor())
                : modelled(bounds, service, sojournMs, queueDepth);
        if (underQueuePressure(queueDepth, bounds)) {
            // Never below what is already in flight while the buffer is filling: lowering the limit
            // here shrinks the admission window and refuses the very traffic that is queueing.
            needed = Math.max(needed, Math.max(inFlight + 1, bounds.floor()));
        }
        return Math.clamp(needed, bounds.floor(), bounds.ceiling());
    }

    private int modelled(
            Bounds bounds, LatencyIntervals.Interval service, double sojournMs, int queueDepth) {
        double serviceSeconds = service.meanLatencyMs() / 1000.0;
        double predictedRps = forecast.next(bounds.name(), service.throughputRps());
        // Little's law: sustaining an arrival rate needs rate x service time in flight.
        double toServe = predictedRps * serviceSeconds;
        // Plus whatever is already waiting, spread over the horizon it is allowed to take.
        double toDrain = queueDepth * serviceSeconds / drainHorizonSeconds(bounds, sojournMs);
        return (int) Math.ceil(toServe + toDrain);
    }

    /**
     * How long the backlog may take to clear. Outside the promise it is shortened in proportion to
     * how far outside, which asks for more concurrency — the wait is the only part of the caller's
     * latency that a concurrency limit can shorten.
     */
    private static double drainHorizonSeconds(Bounds bounds, double sojournMs) {
        if (bounds.targetMs() <= 0 || sojournMs <= bounds.targetMs()) {
            return DRAIN_HORIZON_SECONDS;
        }
        double urgency = bounds.targetMs() / sojournMs;
        return Math.max(MIN_DRAIN_HORIZON_SECONDS, DRAIN_HORIZON_SECONDS * urgency);
    }

    private static boolean underQueuePressure(int queueDepth, Bounds bounds) {
        return bounds.queueSize() > 0 && queueDepth >= bounds.queueSize() * HIGH_WATER;
    }

    /** The window the limit may move in, the promise it is held to, and the buffer in front of it. */
    private record Bounds(String name, int floor, int ceiling, long targetMs, int queueSize) {
        static Bounds of(FunctionSpec spec) {
            ConcurrencyControlConfig control = spec.scalingConfig() == null
                    ? null
                    : spec.scalingConfig().concurrencyControl();
            int ceiling = Math.max(1, spec.concurrency());
            int floor = control == null || control.minTargetInFlightPerPod() == null
                    ? 1
                    : Math.max(1, control.minTargetInFlightPerPod());
            if (control != null && control.maxTargetInFlightPerPod() != null) {
                ceiling = Math.clamp(control.maxTargetInFlightPerPod(), floor, ceiling);
            }
            floor = Math.min(floor, ceiling);
            long targetMs = control == null || control.targetLatencyMs() == null
                    ? 0L
                    : control.targetLatencyMs();
            int queueSize = spec.queueSize() == null ? 0 : spec.queueSize();
            return new Bounds(spec.name(), floor, ceiling, targetMs, queueSize);
        }
    }

    void removeFunctionState(String functionName) {
        sojournIntervals.removeFunctionState(functionName);
        serviceIntervals.removeFunctionState(functionName);
        forecast.removeFunctionState(functionName);
    }
}
