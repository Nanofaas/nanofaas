package it.unimib.datai.nanofaas.controlplane.deployment;

import java.util.List;

/**
 * Explicit partial outcome of {@link ManagedDeploymentProvider#deprovision(String)}: the backend
 * removed what it could, and the resources named by {@link #remainingResources()} are still there.
 *
 * <p>Throwing this is a statement about ownership, not only about failure. The backend keeps
 * whatever it needs to finish the job, so a later {@code deprovision} of the same name resumes the
 * cleanup idempotently — including after a restart, where the backend rediscovers its resources
 * from their own metadata. Because it says that resources really were lost from the control
 * plane's reach, the caller must not present the removal as rolled back: an operational rollback is
 * only honest when nothing needed was lost, or when it has been rebuilt and verified.
 *
 * <p>The individual removal errors travel as the cause (the first one) and as suppressed
 * exceptions (the rest), so nothing that went wrong is dropped on the way up.
 */
public class PartialDeprovisionException extends RuntimeException {

    private final transient List<String> remainingResources;
    private final String functionName;
    private final String backendId;

    public PartialDeprovisionException(String functionName,
                                       String backendId,
                                       List<String> remainingResources,
                                       List<? extends Throwable> failures) {
        super(message(functionName, backendId, remainingResources),
                failures == null || failures.isEmpty() ? null : failures.getFirst());
        this.functionName = functionName;
        this.backendId = backendId;
        this.remainingResources = remainingResources == null ? List.of() : List.copyOf(remainingResources);
        if (failures != null) {
            for (int i = 1; i < failures.size(); i++) {
                addSuppressed(failures.get(i));
            }
        }
    }

    public String functionName() {
        return functionName;
    }

    public String backendId() {
        return backendId;
    }

    /** Human-readable identifiers of what the backend still owns, for the operator and for a retry. */
    public List<String> remainingResources() {
        // Never null, including on the (unused) deserialization path where the transient list is lost.
        return remainingResources == null ? List.of() : remainingResources;
    }

    private static String message(String functionName, String backendId, List<String> remainingResources) {
        List<String> remaining = remainingResources == null ? List.of() : remainingResources;
        return "Deprovision of function '" + functionName + "' on backend '" + backendId
                + "' left " + remaining.size() + " resource(s) behind: " + remaining
                + ". The backend still owns them; retry the removal to resume the cleanup.";
    }
}
