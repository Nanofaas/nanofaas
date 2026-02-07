package it.unimib.datai.nanofaas.sdk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Provides execution context for nanofaas functions.
 * Values are populated by the runtime's {@code TraceLoggingFilter} via SLF4J MDC.
 */
public final class FunctionContext {

    private FunctionContext() {}

    /** Current execution ID (set by runtime per request). */
    public static String getExecutionId() {
        return MDC.get("executionId");
    }

    /** Current distributed trace ID (set by runtime per request). */
    public static String getTraceId() {
        return MDC.get("traceId");
    }

    /** Convenience logger that automatically includes trace context from MDC. */
    public static Logger getLogger(Class<?> clazz) {
        return LoggerFactory.getLogger(clazz);
    }
}
