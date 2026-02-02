package com.mcfaas.controlplane.queue;

import com.mcfaas.common.model.ExecutionMode;
import com.mcfaas.common.model.FunctionSpec;
import com.mcfaas.common.model.InvocationRequest;
import com.mcfaas.controlplane.scheduler.InvocationTask;
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
