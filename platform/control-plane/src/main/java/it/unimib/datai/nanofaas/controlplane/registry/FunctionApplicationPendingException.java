package it.unimib.datai.nanofaas.controlplane.registry;

/**
 * A durable function record exists, but its provider-side application could not be verified.
 * The record remains a recovery handle and must not be advertised as a live function.
 */
public final class FunctionApplicationPendingException extends RuntimeException {
    public static final String ERROR_CODE = "FUNCTION_APPLICATION_PENDING";

    private final String functionName;

    public FunctionApplicationPendingException(String functionName, String reason) {
        super("Function '" + functionName + "' is unavailable while provider application is pending: " + reason);
        this.functionName = functionName;
    }

    public String functionName() {
        return functionName;
    }
}
