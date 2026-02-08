package it.unimib.datai.nanofaas.controlplane.queue;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueueManagerTest {
    @Test
    void issue008_queueIsBounded() {
        QueueManager manager = new QueueManager(new SimpleMeterRegistry());
        FunctionSpec spec = new FunctionSpec(
                "bounded",
                "image",
                null,
                Map.of(),
                null,
                1000,
                1,
                1,
                3,
                null,
                ExecutionMode.LOCAL,
                null,
                null,
                null
        );
        manager.getOrCreate(spec);

        InvocationTask first = new InvocationTask(
                "exec-1",
                "bounded",
                spec,
                new InvocationRequest("hello", Map.of()),
                null,
                null,
                Instant.now(),
                1
        );
        InvocationTask second = new InvocationTask(
                "exec-2",
                "bounded",
                spec,
                new InvocationRequest("world", Map.of()),
                null,
                null,
                Instant.now(),
                1
        );

        assertTrue(manager.enqueue(first));
        assertFalse(manager.enqueue(second));
    }
}
