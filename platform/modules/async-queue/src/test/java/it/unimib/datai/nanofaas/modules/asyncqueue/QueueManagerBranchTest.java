package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QueueManagerBranchTest {

    @Test
    void enqueue_unknownFunction_returnsFalse() {
        QueueManager queueManager = new QueueManager(new SimpleMeterRegistry());

        boolean enqueued = queueManager.enqueue(task("missing"));

        assertThat(enqueued).isFalse();
    }

    @Test
    void tryAcquireSlot_unknownFunction_returnsFalse() {
        QueueManager queueManager = new QueueManager(new SimpleMeterRegistry());

        assertThat(queueManager.tryAcquireSlot("missing")).isFalse();
    }

    @Test
    void releaseSlot_andIncrementDecrement_unknownFunction_doNotThrow() {
        QueueManager queueManager = new QueueManager(new SimpleMeterRegistry());

        queueManager.releaseSlot("missing");
        queueManager.incrementInFlight("missing");
        queueManager.decrementInFlight("missing");
    }

    @Test
    void getOrCreate_existingFunction_updatesConcurrency() {
        QueueManager queueManager = new QueueManager(new SimpleMeterRegistry());
        FunctionSpec initial = spec("fn", 1, 10);
        FunctionSpec updated = spec("fn", 2, 10);

        queueManager.getOrCreate(initial);
        assertThat(queueManager.tryAcquireSlot("fn")).isTrue();
        assertThat(queueManager.tryAcquireSlot("fn")).isFalse();

        queueManager.getOrCreate(updated);
        assertThat(queueManager.tryAcquireSlot("fn")).isTrue();
    }

    private InvocationTask task(String functionName) {
        return new InvocationTask(
                "exec",
                functionName,
                spec(functionName, 1, 10),
                new InvocationRequest("payload", Map.of()),
                null,
                null,
                Instant.now(),
                1
        );
    }

    private FunctionSpec spec(String name, int concurrency, int queueSize) {
        return new FunctionSpec(
                name,
                "image",
                null,
                Map.of(),
                null,
                1000,
                concurrency,
                queueSize,
                1,
                null,
                ExecutionMode.LOCAL,
                null,
                null,
                null
        );
    }
}
