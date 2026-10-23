package it.unimib.datai.nanofaas.controlplane.dispatch;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.logging.LoggingContext;
import org.slf4j.Logger;
import java.util.HashMap;

/** Snapshot from the task itself: scheduler threads may handle many unrelated requests. */
final class InvocationLogs {
    private InvocationLogs() { }
    static void event(Logger logger, InvocationTask task, String event) {
        if (!logger.isDebugEnabled()) return;
        var context = new HashMap<String, String>();
        if (task.executionId() != null) context.put("executionId", task.executionId());
        if (task.traceId() != null) context.put("traceId", task.traceId());
        if (task.functionName() != null) context.put("functionName", task.functionName());
        if (task.attempt() > 0) context.put("attempt", Integer.toString(task.attempt()));
        context.put("event", event);
        try (var ignored = LoggingContext.open(context)) { logger.debug(event); }
    }
}
