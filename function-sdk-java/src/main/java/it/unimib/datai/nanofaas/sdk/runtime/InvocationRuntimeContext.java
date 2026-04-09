package it.unimib.datai.nanofaas.sdk.runtime;

/**
 * Normalized per-request metadata used by the invocation pipeline.
 *
 * <p>This record exists to keep header/env precedence in one place and to avoid passing raw request
 * headers through the controller, handler registry, and callback path. It is a request-scoped value
 * object, not application state.</p>
 */
public record InvocationRuntimeContext(String executionId, String traceId) {
}
