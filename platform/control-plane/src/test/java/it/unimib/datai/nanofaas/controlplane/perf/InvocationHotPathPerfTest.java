package it.unimib.datai.nanofaas.controlplane.perf;

import it.unimib.datai.nanofaas.controlplane.execution.OverridableExecutionStore;
import it.unimib.datai.nanofaas.controlplane.service.ServiceFixtures;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionFixtures;
import it.unimib.datai.nanofaas.controlplane.service.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.service.InvocationExecutionFactory;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InvocationHotPathPerfTest {

    private final IdempotencyStore idempotencyStore = ExecutionFixtures.keys(Duration.ofMinutes(15));
    private final CountingExecutionStore executionStore = new CountingExecutionStore(idempotencyStore);
    private final InvocationExecutionFactory factory = ServiceFixtures.factory(executionStore, idempotencyStore,
            new Metrics(new SimpleMeterRegistry()), "perf-idempotent-fn");


    @Test
    void repeatedIdempotentReplay_doesNotAllocateNewExecutionRecords() {
        FunctionSpec spec = functionSpec("perf-idempotent-fn");
        InvocationRequest request = new InvocationRequest("payload", Map.of());

        InvocationExecutionFactory.ExecutionLookup first = factory.createOrReuseExecution(
                spec.name(), spec, request, "idem-1", "trace-1",
                InvocationKind.SYNC
            );
        first.publishAdmission();
        InvocationExecutionFactory.ExecutionLookup replayOne = factory.createOrReuseExecution(
                spec.name(), spec, request, "idem-1", "trace-1",
                InvocationKind.SYNC
            );
        InvocationExecutionFactory.ExecutionLookup replayTwo = factory.createOrReuseExecution(
                spec.name(), spec, request, "idem-1", "trace-1",
                InvocationKind.SYNC
            );

        assertThat(first.isNew()).isTrue();
        assertThat(replayOne.isNew()).isFalse();
        assertThat(replayTwo.isNew()).isFalse();
        assertThat(replayOne.executionRecord().executionId()).isEqualTo(first.executionRecord().executionId());
        assertThat(replayTwo.executionRecord().executionId()).isEqualTo(first.executionRecord().executionId());
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

    private static final class CountingExecutionStore extends OverridableExecutionStore {
        private int putCount;
        private int removeCount;

        CountingExecutionStore(IdempotencyStore keys) {
            super(keys);
        }

        @Override
        public void put(ExecutionRecord executionRecord) {
            putCount++;
            super.put(executionRecord);
        }

        @Override
        public void remove(String executionId) {
            removeCount++;
            super.remove(executionId);
        }

        int putCount() {
            return putCount;
        }

        int removeCount() {
            return removeCount;
        }
    }
}
