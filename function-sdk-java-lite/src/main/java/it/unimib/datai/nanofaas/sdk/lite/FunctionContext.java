package it.unimib.datai.nanofaas.sdk.lite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Provides execution context for nanofaas functions.
 * Values are populated by the runtime per request via SLF4J MDC.
 */
public final class FunctionContext {

    private FunctionContext() {}

    // Used by InvokeHandler (different package) - not part of public user API
    public static void set(String executionId, String traceId) {
        if (executionId != null) {
            MDC.put("executionId", executionId);
        }
        if (traceId != null) {
            MDC.put("traceId", traceId);
        }
    }

    public static void clear() {
        MDC.remove("executionId");
        MDC.remove("traceId");
    }

    public static String getExecutionId() {
        return MDC.get("executionId");
    }

    public static String getTraceId() {
        return MDC.get("traceId");
    }

    public static Logger getLogger(Class<?> clazz) {
        return LoggerFactory.getLogger(clazz);
    }
}
