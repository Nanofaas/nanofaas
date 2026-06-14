package it.unimib.datai.nanofaas.sdk.runtime;

/**
 * Normalized per-request runtime metadata.
 *
 * <p>The resolver builds this once so the invocation pipeline can pass execution and trace values
 * around without re-reading headers or environment variables at each step.</p>
 */
public record InvocationRuntimeContext(String executionId, String traceId) {
}
