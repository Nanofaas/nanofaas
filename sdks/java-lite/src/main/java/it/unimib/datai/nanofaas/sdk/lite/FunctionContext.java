package it.unimib.datai.nanofaas.sdk.lite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Provides execution context for nanofaas functions.
 * Values are populated by the runtime per request via SLF4J MDC.
 */
public final class FunctionContext {

    private static final String EXECUTION_ID_KEY = "executionId";
    private static final String TRACE_ID_KEY = "traceId";

    private FunctionContext() {}

    // Used by InvokeHandler (different package) - not part of public user API
    public static void set(String executionId, String traceId) {
        if (executionId != null) {
            MDC.put(EXECUTION_ID_KEY, executionId);
        }
        if (traceId != null) {
            MDC.put(TRACE_ID_KEY, traceId);
        }
    }

    public static void clear() {
        MDC.remove(EXECUTION_ID_KEY);
        MDC.remove(TRACE_ID_KEY);
    }

    public static String getExecutionId() {
        return MDC.get(EXECUTION_ID_KEY);
    }

    public static String getTraceId() {
        return MDC.get(TRACE_ID_KEY);
    }

    public static Logger getLogger(Class<?> clazz) {
        return LoggerFactory.getLogger(clazz);
    }
}
