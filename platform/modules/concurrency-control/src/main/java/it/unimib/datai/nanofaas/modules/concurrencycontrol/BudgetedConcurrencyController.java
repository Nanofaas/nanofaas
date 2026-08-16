package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.service.ScalingMetricsSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The BUDGETED mode: every function states what it needs, and one allocation answers them all.
 *
 * <p>This runs over the whole set on each tick rather than function by function, and that is the
 * substance of the mode rather than an implementation detail. A per-function decision cannot
 * respect a shared budget, because no function knows what the others are asking for; the measured
 * consequence of deciding independently was a function's limit moving when a neighbour arrived,
 * with its own load unchanged. Here the sum of the grants cannot exceed the budget, so functions
 * do not have to discover each other through interference.</p>
 */
public class BudgetedConcurrencyController {

    private final SloDemandEstimator estimator;
    private final ConcurrencyBudgetAllocator allocator;
    private final LatencyIntervals intervals;

    public BudgetedConcurrencyController() {
        this(new SloDemandEstimator(), new ConcurrencyBudgetAllocator(), new LatencyIntervals());
    }

    BudgetedConcurrencyController(
            SloDemandEstimator estimator,
            ConcurrencyBudgetAllocator allocator,
            LatencyIntervals intervals
    ) {
        this.estimator = estimator;
        this.allocator = allocator;
        this.intervals = intervals;
    }

    /**
     * @param observations one per function under this mode, in any order
     * @return what each function was granted, by name
     */
    public Map<String, Integer> apply(
            List<FunctionObservation> observations,
            int budget,
            ScalingMetricsSource metricsSource,
            long nowEpochMs
    ) {
        List<ConcurrencyDemand> demands = new ArrayList<>(observations.size());
        for (FunctionObservation observation : observations) {
            FunctionSpec spec = observation.spec();
            LatencyIntervals.Interval interval = intervals.sample(
                    spec.name(), observation.latencyCount(), observation.latencyTotalMs(), nowEpochMs);
            demands.add(estimator.estimate(
                    spec, interval.meanLatencyMs(), interval.throughputRps(), observation.inFlight()));
        }

        Map<String, Integer> granted = allocator.allocate(demands, budget);
        for (ConcurrencyDemand demand : demands) {
            int limit = granted.getOrDefault(demand.functionName(), demand.floor());
            estimator.recordGrant(demand.functionName(), limit);
            metricsSource.setEffectiveConcurrency(demand.functionName(), limit);
            metricsSource.updateConcurrencyController(
                    demand.functionName(), ConcurrencyControlMode.BUDGETED, limit);
        }
        return granted;
    }

    void removeFunctionState(String functionName) {
        estimator.removeFunctionState(functionName);
        intervals.removeFunctionState(functionName);
    }

    /** One function's inputs for a tick. */
    public record FunctionObservation(
            FunctionSpec spec,
            int inFlight,
            long latencyCount,
            double latencyTotalMs
    ) {
    }
}
