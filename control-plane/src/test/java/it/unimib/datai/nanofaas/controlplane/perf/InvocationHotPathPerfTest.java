package it.unimib.datai.nanofaas.controlplane.perf;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.service.InvocationExecutionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class InvocationHotPathPerfTest {

    private final CountingExecutionStore executionStore = new CountingExecutionStore();
    private final IdempotencyStore idempotencyStore = new IdempotencyStore(Duration.ofMinutes(15));
    private final InvocationExecutionFactory factory = new InvocationExecutionFactory(executionStore, idempotencyStore);

    @AfterEach
    void tearDown() {
        executionStore.shutdown();
        idempotencyStore.shutdown();
    }

    @Test
    void repeatedIdempotentReplay_doesNotAllocateNewExecutionRecords() {
        FunctionSpec spec = functionSpec("perf-idempotent-fn");
        InvocationRequest request = new InvocationRequest("payload", Map.of());

        InvocationExecutionFactory.ExecutionLookup first = factory.createOrReuseExecution(
                spec.name(), spec, request, "idem-1", "trace-1");
        InvocationExecutionFactory.ExecutionLookup replayOne = factory.createOrReuseExecution(
                spec.name(), spec, request, "idem-1", "trace-1");
        InvocationExecutionFactory.ExecutionLookup replayTwo = factory.createOrReuseExecution(
                spec.name(), spec, request, "idem-1", "trace-1");

        assertThat(first.isNew()).isTrue();
        assertThat(replayOne.isNew()).isFalse();
        assertThat(replayTwo.isNew()).isFalse();
        assertThat(replayOne.record().executionId()).isEqualTo(first.record().executionId());
        assertThat(replayTwo.record().executionId()).isEqualTo(first.record().executionId());
        assertThat(executionStore.putCount())
                .as("replays should reuse the published execution instead of creating speculative records")
                .isEqualTo(1);
        assertThat(executionStore.removeCount())
                .as("replays should not publish temporary records that need cleanup")
                .isZero();
    }

    private static FunctionSpec functionSpec(String name) {
        return new FunctionSpec(
                name,
                "example/image:latest",
                null,
                Map.of(),
                null,
                1_000,
                1,
                8,
                3,
                null,
                ExecutionMode.LOCAL,
                null,
                null,
                null
        );
    }

    private static final class CountingExecutionStore extends ExecutionStore {
        private int putCount;
        private int removeCount;

        @Override
        public void put(ExecutionRecord record) {
            putCount++;
            super.put(record);
        }

        @Override
        public void remove(String executionId) {
            removeCount++;
            super.remove(executionId);
        }

        @Override
        public Optional<ExecutionRecord> get(String executionId) {
            return super.get(executionId);
        }

        int putCount() {
            return putCount;
        }

        int removeCount() {
            return removeCount;
        }
    }
}
