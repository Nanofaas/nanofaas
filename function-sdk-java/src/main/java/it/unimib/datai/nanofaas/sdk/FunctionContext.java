package it.unimib.datai.nanofaas.sdk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Reads request-scoped metadata that the runtime installs into SLF4J MDC before handler code runs.
 *
 * <p>Handlers use this helper when they need execution or trace identifiers without threading
 * those values through every method signature. The data exists only for the lifetime of the
 * current request thread; it is not application state and it disappears when the request scope
 * ends.</p>
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
