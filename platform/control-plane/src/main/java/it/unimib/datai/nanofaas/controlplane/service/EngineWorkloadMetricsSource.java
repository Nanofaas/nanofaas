package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.capacity.CapacityView;
import it.unimib.datai.nanofaas.controlplane.capacity.DispatchCapacity;
import it.unimib.datai.nanofaas.execution.SchedulerEngine;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsSource;

import java.util.Objects;

/**
 * The single {@link WorkloadMetricsSource} for the composed engine (Task 11, issue #208),
 * replacing the two per-module sources ({@code AsyncQueueWorkloadMetricsSource},
 * {@code SyncQueueWorkloadMetricsSource}) that Task 8 retired along with the schedulers that
 * backed them. Both the autoscaler and the concurrency governor gate their own startup on a
 * bean of this type ({@code @ConditionalOnBean(WorkloadMetricsSource.class)}); its absence since
 * Task 8 silently disabled both, which is the incident {@code AutoscalerConfigurationTest}
 * documents.
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

    /** Total reservations (pending + claimed + submitting) this function currently occupies in
     * the engine. This is not byte-identical to the retired schedulers' "queued, not yet
     * claimed" definition: that finer-grained count would need extra bookkeeping at the
     * claim/abort boundary that this source does not otherwise need. */
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

    /** Mirrors the retired {@code SyncQueueWorkloadMetricsSource.dispatchableBacklog}: the
     * function's reservations when its generation can currently dispatch, zero otherwise. */
    @Override
    public int dispatchableBacklog(String functionName) {
        CapacityView state = capacityRegistry.state(functionName);
        return state != null && state.canDispatch() ? engine.reservedCount(functionName) : 0;
    }
}
