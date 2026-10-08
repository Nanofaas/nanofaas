package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;

/**
 * Read-only cumulative observations shared by control loops, across SYNC and ASYNC.
 * Reading never registers meters or retains an execution/generation owner.
 * Durations use monotonic execution timestamps, expressed in milliseconds:
 * service is the final observed attempt's dispatch-to-completion (not earlier retried
 * attempts); endToEnd is original admission-to-terminal outcome, including retries
 * and queue waits, exactly once. Censored/undispatched outcomes contribute only to
 * endToEnd. An independent waiter timeout is not an execution conclusion.
 * Dispatched counts attempts, including retries. Counters are concurrent approximate
 * samples, but each snapshot belongs to one registration; compare deltas only within
 * the same generation. An absent registration has null identity and zero totals.
 */
public interface InvocationObservations {
    Snapshot snapshot(String functionName);

    record DurationTotals(long count, double totalMillis) { }
    record Snapshot(FunctionGeneration generation, DurationTotals service,
                    DurationTotals endToEnd, double dispatched) {
        public static Snapshot absent() {
            return new Snapshot(null, new DurationTotals(0, 0), new DurationTotals(0, 0), 0);
        }
    }
}
