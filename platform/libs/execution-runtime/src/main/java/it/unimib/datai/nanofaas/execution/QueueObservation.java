package it.unimib.datai.nanofaas.execution;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Correlatable queue events; monotonic values are comparable only within processId. */
final class QueueObservation {
    private static final Logger LOG = LoggerFactory.getLogger(QueueObservation.class);
    private static final String PROCESS_ID = UUID.randomUUID().toString();

    private QueueObservation() { }

    static void record(String event, InvocationTask task, long nanos) {
        try {
            LOG.atInfo().addKeyValue("event", event).addKeyValue("processId", PROCESS_ID)
                    .addKeyValue("monotonicNanos", nanos)
                    .addKeyValue("executionId", task.executionId())
                    .addKeyValue("originalId", task.traceId())
                    .addKeyValue("attempt", task.attempt())
                    .addKeyValue("function", task.functionName()).log("queue observation");
        } catch (RuntimeException ignored) {
            // Observability must not change admission or dispatch outcomes.
        }
    }
}
