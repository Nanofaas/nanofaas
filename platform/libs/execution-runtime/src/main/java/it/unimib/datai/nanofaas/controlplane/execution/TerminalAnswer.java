package it.unimib.datai.nanofaas.controlplane.execution;

import it.unimib.datai.nanofaas.common.model.ErrorInfo;
import it.unimib.datai.nanofaas.common.model.InvocationResult;

import java.util.Map;

/**
 * The answer an execution settled on, chosen once by its terminal transition: either the
 * handler's output with its response envelope, or an error with the status and headers that
 * came with it. {@code failure}, when present, is what the shared completion future completes
 * exceptionally with instead (an offload infrastructure failure).
 */
record TerminalAnswer(Object output, ErrorInfo error, Integer statusCode, Map<String, String> headers,
                      String encoding, Throwable failure) {

    static TerminalAnswer success(Object output, Integer statusCode, Map<String, String> headers, String encoding) {
        return new TerminalAnswer(output, null, statusCode, headers, encoding, null);
    }

    static TerminalAnswer error(ErrorInfo error, Integer statusCode, Map<String, String> headers, Throwable failure) {
        return new TerminalAnswer(null, error, statusCode, headers == null ? null : Map.copyOf(headers), null, failure);
    }

    /** The result the shared completion publishes for a record that ended in {@code state}. */
    InvocationResult toResult(ExecutionState state) {
        if (state == ExecutionState.SUCCESS) {
            return InvocationResult.successWithEnvelope(output, statusCode, headers, encoding);
        }
        ErrorInfo published = error != null ? error : new ErrorInfo("TIMEOUT", "Execution timed out");
        return new InvocationResult(false, null, published, statusCode, headers, null);
    }
}
