package it.unimib.datai.nanofaas.common.model;

import java.util.Map;

public record InvocationResult(
        boolean success,
        Object output,
        ErrorInfo error,
        Integer statusCode,
        Map<String, String> headers,
        String encoding
) {
    /** The code a failure carries when whoever reported it gave no error. */
    public static final String UNSPECIFIED_ERROR_CODE = "UNSPECIFIED_ERROR";

    /**
     * A failure always says what failed. Results are also built from untrusted JSON (the runtime
     * callback, a remote plane), and every reader downstream tells a failure apart by its error:
     * without one, the same execution read as a timeout to its waiter and a success to a replay.
     */
    public InvocationResult {
        if (!success && error == null) {
            error = new ErrorInfo(UNSPECIFIED_ERROR_CODE, "The function reported a failure without error details");
        }
    }

    public InvocationResult(boolean success, Object output, ErrorInfo error) {
        this(success, output, error, null, null, null);
    }

    public static InvocationResult success(Object output) {
        return new InvocationResult(true, output, null);
    }

    public static InvocationResult successWithEnvelope(Object output, Integer statusCode,
                                                         Map<String, String> headers, String encoding) {
        return new InvocationResult(true, output, null, statusCode, headers, encoding);
    }

    public static InvocationResult error(String code, String message) {
        return new InvocationResult(false, null, new ErrorInfo(code, message));
    }
}
