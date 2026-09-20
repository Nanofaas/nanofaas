package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionState;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistrationListener;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncQueueConfigurationTest {
    private final ExecutionStore executionStore = new ExecutionStore();


    @Test
    void queueLifecycleListener_marksDrainedQueuedExecutionAsFunctionRemoved() {
        QueueManager queueManager = new QueueManager(new SimpleMeterRegistry(), new FunctionCapacityRegistry());
        // Task 8 (issue #208) retired AsyncQueueConfiguration.queueLifecycleListener as a Spring
        // bean method: capacity registration moved to SchedulerConfiguration's consolidated
        // listener, and QueueManager is no longer wired at all. This test still pins
        // QueueManager's own drain-on-removal behavior, so it rebuilds the equivalent listener
        // body inline rather than through the (now-gone) bean method.
        FunctionRegistrationListener listener = new FunctionRegistrationListener() {
            @Override
            public void onRegister(FunctionSpec spec) {
                queueManager.getOrCreate(spec);
            }

            @Override
            public void onRemove(String functionName) {
                for (InvocationTask removedTask : queueManager.remove(functionName)) {
                    executionStore.removed(removedTask);
                }
            }
        };
        FunctionSpec spec = spec("echo");
        InvocationTask task = task("exec-queued", spec);
        ExecutionRecord executionRecord = new ExecutionRecord(task.executionId(), task);

        listener.onRegister(spec);
        executionStore.put(executionRecord);
        assertThat(queueManager.enqueue(task)).isTrue();

        listener.onRemove("echo");

        assertThat(executionRecord.state()).isEqualTo(ExecutionState.ERROR);
        assertThat(executionRecord.lastError().code()).isEqualTo("FUNCTION_REMOVED");
        assertThat(executionRecord.lastError().message()).contains("echo");
        assertThat(executionRecord.completion().isDone()).isTrue();
        InvocationResult result = executionRecord.completion().join();
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
        ,
        InvocationKind.SYNC
    );
    }
}
