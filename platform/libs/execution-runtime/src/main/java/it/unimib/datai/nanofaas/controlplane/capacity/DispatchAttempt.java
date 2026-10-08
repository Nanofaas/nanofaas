package it.unimib.datai.nanofaas.controlplane.capacity;

/**
 * One dispatch of an invocation: its execution, its attempt number, and the
 * capacity lease it owns (or none, for a path that never acquired local capacity
 * such as offload). The lease travels with the attempt, so a completion releases
 * exactly the capacity that attempt acquired (ADR 0001 invariant I4).
 */
public record DispatchAttempt(String executionId, int attempt, DispatchOwnership lease) {
}
