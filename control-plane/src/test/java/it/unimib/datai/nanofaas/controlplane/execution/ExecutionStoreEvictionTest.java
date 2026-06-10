package it.unimib.datai.nanofaas.controlplane.execution;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionStoreEvictionTest {

    private static ExecutionRecord record(String id) {
        FunctionSpec spec = new FunctionSpec("fn", "img", List.of(), Map.of(), null,
                1000, 1, 10, 0, null, ExecutionMode.LOCAL, RuntimeMode.HTTP, null, null, null);
        InvocationTask task = new InvocationTask(id, "fn", spec,
                new InvocationRequest("payload", Map.of()), null, null, Instant.now(), 1);
        return new ExecutionRecord(id, task);
    }

    @Test
    void nonTerminalRecordsAreEvictedAfterMaxLifetime() throws InterruptedException {
        ExecutionStoreProperties props = new ExecutionStoreProperties(
                Duration.ofMinutes(5), Duration.ofMinutes(2), Duration.ofMillis(50));
        ExecutionStore store = new ExecutionStore(props);
        try {
            ExecutionRecord stuck = record("stuck-queued");
            store.put(stuck); // never transitions: simulates a lost dispatch

            Thread.sleep(120);
            store.evictExpired();

            assertThat(store.getOrNull("stuck-queued")).isNull();
        } finally {
            store.shutdown();
        }
    }

    @Test
    void freshNonTerminalRecordsSurviveEviction() {
        ExecutionStore store = new ExecutionStore(new ExecutionStoreProperties(null, null, null));
        try {
            ExecutionRecord running = record("fresh");
            store.put(running);
            store.evictExpired();
            assertThat(store.getOrNull("fresh")).isNotNull();
        } finally {
            store.shutdown();
        }
    }

    @Test
    void cleanupReleasesPayloadOnlyOnce() {
        ExecutionRecord done = record("done");
        done.markSuccess("out");
        done.cleanup();
        InvocationTask afterFirstCleanup = done.task();
        done.cleanup();
        // cleanup must be idempotent: no new task allocation on repeat sweeps
        assertThat(done.task()).isSameAs(afterFirstCleanup);
    }
}
