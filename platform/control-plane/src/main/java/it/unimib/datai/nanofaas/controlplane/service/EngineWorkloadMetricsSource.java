package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.capacity.CapacityView;
import it.unimib.datai.nanofaas.controlplane.capacity.DispatchCapacity;
import it.unimib.datai.nanofaas.execution.SchedulerEngine;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsSource;

import java.util.Objects;

/**
 * The single {@link WorkloadMetricsSource} for the composed engine. Both the autoscaler and the concurrency governor gate their own startup on a
 * bean of this type ({@code @ConditionalOnBean(WorkloadMetricsSource.class)}); without it both
 * are silently disabled, which is the incident {@code AutoscalerConfigurationTest} documents.
 *
 * <p>Every method here is O(1) and reads a value already maintained incrementally at its own
 * mutation point — {@link SchedulerEngine#reservedCount} (updated at admission/settlement, never
 * a backlog scan) and {@link DispatchCapacity}'s own per-function state — so a Micrometer scrape
 * polling this for every registered function never scans the pending backlog.
 */
public final class EngineWorkloadMetricsSource implements WorkloadMetricsSource {

    private final SchedulerEngine engine;
    private final DispatchCapacity capacityRegistry;

    public EngineWorkloadMetricsSource(SchedulerEngine engine, DispatchCapacity capacityRegistry) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.capacityRegistry = Objects.requireNonNull(capacityRegistry, "capacityRegistry must not be null");
    }

    /**
     * Total reservations (pending + claimed + submitting) this function currently occupies in
     * the engine. This is not byte-identical to the retired schedulers' "queued, not yet
     * claimed" definition, but the divergence is bounded at <strong>+1, globally, never
     * per-execution</strong> — not the unbounded "N in-flight attempts inflate this by N" that a
     * first reading suggests:
     *
     * <ul>
     *   <li>{@code claimed} can only ever be 0 or 1 at any instant: {@code store.claim()} has
     *       exactly one caller ({@code SchedulerEngine.selectAndClaim}), invoked at most once per
     *       pass, on the single scheduler worker thread, and every pass carries that one claim to
     *       a decision (commit or abort) before the next pass starts — see
     *       {@code SchedulerEngine}'s own "one selection per pass" contract.</li>
     *   <li>{@code submitting} does not span an execution's lifetime: {@code EngineDispatch.submit}
     *       is contractually non-blocking and releases the reservation (via {@code finishSubmit}
     *       or {@code requeue}) at hand-off, in the same {@code carry()} call that set it — an
     *       in-flight execution's own duration is not counted here at all.</li>
     * </ul>
     *
     * <p>So a steady state with N in-flight executions reports the same {@code queueDepth} as one
     * with zero (plus, transiently, at most one extra while the single worker thread is between
     * claim and commit/abort) — this is not the admission-multiplying gap it can look like.
     * Neither the autoscaler's replica ratio nor the concurrency governor's high-water threshold
     * can be moved by a ±1 reading, and sync admission does not read this source at all (it reads
     * {@code PendingWorkStore.pendingCount()} directly, which keeps the narrower meaning). Do not
     * "fix" this with extra claim/abort bookkeeping the design does not need.
     */
    @Override
    public int queueDepth(String functionName) {
        return engine.reservedCount(functionName);
    }

    @Override
    public int inFlight(String functionName) {
        return capacityRegistry.inFlight(functionName);
    }

    @Override
    public int effectiveConcurrency(String functionName) {
        return capacityRegistry.effectiveConcurrency(functionName);
    }

    /** The function's reservations when its generation can currently dispatch, zero otherwise. */
    @Override
    public int dispatchableBacklog(String functionName) {
        CapacityView state = capacityRegistry.state(functionName);
        return state != null && state.canDispatch() ? engine.reservedCount(functionName) : 0;
    }
}
