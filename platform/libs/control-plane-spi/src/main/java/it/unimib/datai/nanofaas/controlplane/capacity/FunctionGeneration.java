package it.unimib.datai.nanofaas.controlplane.capacity;

/**
 * The internal identity of one incarnation of a function name: the name plus the
 * monotonic id minted when that incarnation was registered.
 *
 * <p>A function name is not an identity. The same name can be removed and
 * registered again while work admitted under the previous registration is still
 * draining, so anything that owns per-function resources — capacity, meters,
 * replica snapshots, wake-up gates — must attribute those resources to the
 * generation that acquired them, not to the name (ADR 0001 §8.2, invariants I4
 * and I7).
 *
 * <p>The identity is minted by {@link FunctionCapacityRegistry}, which is the
 * core-owned capacity authority and the only component that retires and replaces
 * a function's active incarnation. It travels with a {@link DispatchLease} so a
 * completion path releases exactly what its attempt acquired.
 *
 * <p><strong>Internal only.</strong> A generation is never part of a public
 * identifier (execution id, idempotency key scope) and is never exposed as a
 * metric tag: it fences stale callbacks, it does not add observable cardinality
 * (ADR 0001 §7).
 *
 * @param functionName the function this incarnation belongs to
 * @param id           monotonic incarnation id, unique per registry, starting at 1
 */
public record FunctionGeneration(String functionName, long id) {

    public FunctionGeneration {
        if (functionName == null || functionName.isBlank()) {
            throw new IllegalArgumentException("functionName must not be blank");
        }
        if (id < 1) {
            throw new IllegalArgumentException("generation id must be positive, was " + id);
        }
    }

    /**
     * Whether this generation replaced {@code other}: same function name, later id.
     *
     * <p>This is how an owner tells a stale event apart from a current one. A stale
     * event may close the resources of its own generation; it may never recreate a
     * generation, nor mutate the generation that superseded it.
     */
    public boolean supersedes(FunctionGeneration other) {
        return other != null && functionName.equals(other.functionName) && id > other.id;
    }

    @Override
    public String toString() {
        return functionName + "#" + id;
    }
}
