package it.unimib.datai.nanofaas.modules.asyncqueue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionState;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistrationListener;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncQueueConfigurationTest {
    private final ExecutionStore executionStore = new ExecutionStore();

    @AfterEach
    void tearDown() {
        executionStore.shutdown();
    }

    @Test
    void queueLifecycleListener_marksDrainedQueuedExecutionAsFunctionRemoved() {
        QueueManager queueManager = new QueueManager(new SimpleMeterRegistry());
        AsyncQueueConfiguration configuration = new AsyncQueueConfiguration();
        FunctionRegistrationListener listener = configuration.queueLifecycleListener(queueManager, executionStore);
        FunctionSpec spec = spec("echo");
        InvocationTask task = task("exec-queued", spec);
        ExecutionRecord record = new ExecutionRecord(task.executionId(), task);

        listener.onRegister(spec);
        executionStore.put(record);
        assertThat(queueManager.enqueue(task)).isTrue();

        listener.onRemove("echo");

        assertThat(record.state()).isEqualTo(ExecutionState.ERROR);
        assertThat(record.lastError().code()).isEqualTo("FUNCTION_REMOVED");
        assertThat(record.lastError().message()).contains("echo");
        assertThat(record.completion().isDone()).isTrue();
        InvocationResult result = record.completion().join();
        assertThat(result.success()).isFalse();
        assertThat(result.error().code()).isEqualTo("FUNCTION_REMOVED");
    }

    private static FunctionSpec spec(String name) {
        return new FunctionSpec(
                name,
                "image",
                null,
                Map.of(),
                null,
                1000,
                4,
                10,
                3,
                null,
                ExecutionMode.DEPLOYMENT,
                null,
                null,
                null
        );
    }

    private static InvocationTask task(String executionId, FunctionSpec spec) {
        return new InvocationTask(
                executionId,
                spec.name(),
                spec,
                new InvocationRequest("payload", Map.of()),
                null,
                null,
                Instant.now(),
                1
        );
    }
}
