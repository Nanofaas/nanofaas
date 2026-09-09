package it.unimib.datai.nanofaas.controlplane.registry;

import java.util.List;

/**
 * The function is in <em>pending removal</em>: a previous delete deprovisioned it only partially,
 * and its backend still owns resources it could not delete.
 *
 * <p>The generation that owned those resources is retired — its capacity, queues and meters are
 * gone and its endpoint is closed — so nothing new is admitted under it. The catalog entry survives
 * on purpose: it is what keeps the leftover resources traceable and lets a retried delete resume
 * the cleanup. Reads that would lead somewhere (an invocation, a change of tuning or of replicas)
 * fail with this exception rather than reaching a deployment that is being torn down.
 */
public class FunctionRemovalPendingException extends RuntimeException {

    /** Error code carried in the API body, so a client can tell this apart from a plain conflict. */
    public static final String ERROR_CODE = "FUNCTION_REMOVAL_PENDING";

    private final transient List<String> remainingResources;
    private final String functionName;

    public FunctionRemovalPendingException(String functionName, List<String> remainingResources) {
        this(functionName, remainingResources, null);
    }

    public FunctionRemovalPendingException(String functionName,
                                           List<String> remainingResources,
                                           Throwable cause) {
        super(message(functionName, remainingResources), cause);
        this.functionName = functionName;
        this.remainingResources = remainingResources == null ? List.of() : List.copyOf(remainingResources);
    }

    public String functionName() {
        return functionName;
    }

    /** What the backend still owns, as it reported it at the time of the partial deprovision. */
    public List<String> remainingResources() {
        // Never null, including on the (unused) deserialization path where the transient list is lost.
        return remainingResources == null ? List.of() : remainingResources;
    }

    private static String message(String functionName, List<String> remainingResources) {
        List<String> remaining = remainingResources == null ? List.of() : remainingResources;
        return "Removal of function '" + functionName + "' is pending: its deployment backend still owns "
                + remaining + ". The function admits no invocation and cannot be changed; "
                + "retry DELETE /v1/functions/" + functionName + " to resume the cleanup.";
    }
}
