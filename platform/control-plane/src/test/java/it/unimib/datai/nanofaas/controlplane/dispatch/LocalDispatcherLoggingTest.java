package it.unimib.datai.nanofaas.controlplane.dispatch;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class LocalDispatcherLoggingTest {
    @Test void dispatcherLogsOwnIdentityAndRestoresCallerContext() {
        Logger logger = (Logger) LoggerFactory.getLogger(LocalDispatcher.class);
        Level previous = logger.getLevel();
        var appender = new ListAppender<ILoggingEvent>() { @Override protected void append(ILoggingEvent event) { event.prepareForDeferredProcessing(); super.append(event); } }; appender.start(); logger.addAppender(appender);
        logger.setLevel(Level.DEBUG); MDC.put("traceId", "caller");
        try {
            var task = new InvocationTask("exec-7", "fn", null, new InvocationRequest("input", null), null,
                    "trace-7", Instant.now(), 1, InvocationKind.SYNC);
            new LocalDispatcher().dispatch(task).join();
            assertFalse(appender.list.isEmpty());
            var context = appender.list.getFirst().getMDCPropertyMap();
            assertEquals("exec-7", context.get("executionId"));
            assertEquals("trace-7", context.get("traceId"));
            assertEquals("fn", context.get("functionName"));
            assertEquals("caller", MDC.get("traceId")); assertNull(MDC.get("executionId"));
        } finally { logger.setLevel(previous); logger.detachAppender(appender); MDC.clear(); }
    }
}
