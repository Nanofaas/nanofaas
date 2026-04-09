package it.unimib.datai.nanofaas.sdk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Request-scoped execution metadata for nanoFaaS handlers.
 *
 * <p>This class exists because handlers need access to execution and trace identifiers without
 * threading those values through every method signature. The runtime populates the SLF4J MDC in
 * {@code TraceLoggingFilter} before user code runs, and handlers read it through this facade.</p>
 *
 * <p>Lifecycle boundary: these values only exist for the current request/thread context. They are
 * cleared when the request completes, so callers must not cache them beyond handler execution.</p>
 *
 * <p>Historical note: the Java SDK used to rely on direct logger access in handlers; the context
 * facade centralizes the request metadata contract and keeps it aligned with the runtime headers.</p>
 */
public final class FunctionContext {

    private FunctionContext() {}

    /** Current execution ID from the runtime-populated MDC. */
    public static String getExecutionId() {
        return MDC.get("executionId");
    }

    /** Current distributed trace ID from the runtime-populated MDC. */
    public static String getTraceId() {
        return MDC.get("traceId");
    }

    /** Convenience logger used by handlers that rely on MDC-backed correlation fields. */
    public static Logger getLogger(Class<?> clazz) {
        return LoggerFactory.getLogger(clazz);
    }
}
