package it.unimib.datai.nanofaas.logging;

import org.slf4j.MDC;
import java.util.Map;

/** Restores the caller's MDC, including when work fails or a worker is reused. */
public final class LoggingContext implements AutoCloseable {
    private final Map<String, String> previous;
    private LoggingContext(Map<String, String> context) {
        previous = MDC.getCopyOfContextMap();
        if (context == null || context.isEmpty()) MDC.clear(); else MDC.setContextMap(context);
    }
    public static LoggingContext open(Map<String, String> context) { return new LoggingContext(context); }
    public static Runnable wrap(Map<String, String> context, Runnable task) {
        Map<String, String> snapshot = context == null ? Map.of() : Map.copyOf(context);
        return () -> { try (var ignored = open(snapshot)) { task.run(); } };
    }
    @Override public void close() {
        if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
    }
}
