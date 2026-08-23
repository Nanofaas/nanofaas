package it.unimib.datai.nanofaas.controlplane.execution;

import it.unimib.datai.nanofaas.common.model.ErrorInfo;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionStoreEvictionTest {

    private static ExecutionRecord executionRecord(String id) {
        FunctionSpec spec = new FunctionSpec("fn", "img", List.of(), Map.of(), null,
                1000, 1, 10, 0, null, ExecutionMode.LOCAL, RuntimeMode.HTTP, null, null, null);
        InvocationTask task = new InvocationTask(id, "fn", spec,
                new InvocationRequest("payload", Map.of()), null, null, Instant.now(), 1,
                InvocationKind.SYNC
            );
        return new ExecutionRecord(id, task);
    }

    @SuppressWarnings("java:S2925") // deliberate TTL aging: the record must age past maxLifetime (50ms) before evictExpired() can evict it
    @Test
    void nonTerminalRecordsAreEvictedAfterMaxLifetime() throws InterruptedException {
        ExecutionStoreProperties props = new ExecutionStoreProperties(
                Duration.ofMinutes(5), Duration.ofMinutes(2), Duration.ofMillis(50), null);
        ExecutionStore store = new ExecutionStore(props);
        try {
            ExecutionRecord stuck = executionRecord("stuck-queued");
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
        ExecutionStore store = new ExecutionStore(new ExecutionStoreProperties(null, null, null, null));
        try {
            ExecutionRecord running = executionRecord("fresh");
            store.put(running);
            store.evictExpired();
            assertThat(store.getOrNull("fresh")).isNotNull();
        } finally {
            store.shutdown();
        }
    }

    @Test
    void cleanupReleasesPayloadOnlyOnce() {
        ExecutionRecord done = executionRecord("done");
        done.markSuccess("out");
        done.cleanup();
        InvocationTask afterFirstCleanup = done.task();
        done.cleanup();
        // cleanup must be idempotent: no new task allocation on repeat sweeps
        assertThat(done.task()).isSameAs(afterFirstCleanup);
    }

    @SuppressWarnings("java:S2925") // deliberate TTL aging: terminal records must outlive their ttl (50ms) before evictExpired() evicts them
    @Test
    void terminalRecordsAreEvictedAfterTtl() throws InterruptedException {
        ExecutionStoreProperties props = new ExecutionStoreProperties(
                Duration.ofMillis(50), Duration.ofMillis(50), Duration.ofMinutes(10), null);

        ExecutionStore store = new ExecutionStore(props);
        try {
            ExecutionRecord success = executionRecord("success");
            success.markSuccess("ok");
            store.put(success);

            ExecutionRecord error = executionRecord("error");
            error.markError(new ErrorInfo("ERR", "boom"));
            store.put(error);

            ExecutionRecord timeout = executionRecord("timeout");
            timeout.markTimeout();
            store.put(timeout);

            Thread.sleep(120);
            store.evictExpired();

            assertThat(store.getOrNull("success")).isNull();
            assertThat(store.getOrNull("error")).isNull();
            assertThat(store.getOrNull("timeout")).isNull();
        } finally {
            store.shutdown();
        }
    }

    @SuppressWarnings("java:S2925") // deliberate TTL aging: createdAt must age past ttl/cleanupTtl (200/150ms) before the record finishes, so evictExpired() only cleans it
    @Test
    void recentlyFinishedLongRunningRecordIsRetained() throws InterruptedException {
        ExecutionStoreProperties props = new ExecutionStoreProperties(
                Duration.ofMillis(200), Duration.ofMillis(150), Duration.ofMinutes(10), null);

        ExecutionStore store = new ExecutionStore(props);
        try {
            ExecutionRecord running = executionRecord("long-running");
            store.put(running);

            // createdAt becomes older than ttl/cleanupTtl before the record finishes
            Thread.sleep(250);

            running.markSuccess("out");
            store.evictExpired();

            ExecutionRecord retained = store.getOrNull("long-running");
            assertThat(retained).isNotNull();
            assertThat(retained.output()).isEqualTo("out");
        } finally {
            store.shutdown();
        }
    }

    @SuppressWarnings("java:S2925") // deliberate TTL aging: the record must age past cleanupTtl (50ms) but stay under ttl (10min) so evictExpired() cleans the payload without evicting
    @Test
    void cleanupFiresBetweenCleanupTtlAndTtl() throws InterruptedException {
        ExecutionStoreProperties props = new ExecutionStoreProperties(
                Duration.ofMinutes(10), Duration.ofMillis(50), Duration.ofMinutes(10), null);

        ExecutionStore store = new ExecutionStore(props);
        try {
            ExecutionRecord done = executionRecord("done");
            done.markSuccess("payload");
            store.put(done);

            Thread.sleep(120);
            store.evictExpired();

            ExecutionRecord retained = store.getOrNull("done");
            assertThat(retained).isNotNull();
            assertThat(retained.output()).isNull();
            assertThat(retained.task().request()).isNull();
        } finally {
            store.shutdown();
        }
    }

    @SuppressWarnings("java:S2925") // deliberate TTL aging: the record must age past ttl/cleanupTtl (50ms) while staying under maxLifetime so evictExpired() retains it
    @Test
    void agedNonTerminalRecordUnderMaxLifetimeIsRetained() throws InterruptedException {
        ExecutionStoreProperties props = new ExecutionStoreProperties(
                Duration.ofMillis(50), Duration.ofMillis(50), Duration.ofMinutes(10), null);

        ExecutionStore store = new ExecutionStore(props);
        try {
            ExecutionRecord queued = executionRecord("queued");
            store.put(queued);

            Thread.sleep(120);
            store.evictExpired();

            assertThat(store.getOrNull("queued")).isNotNull();
        } finally {
            store.shutdown();
        }
    }

    @Test
    void removeDeletesExecution() {
        ExecutionStore store = new ExecutionStore(new ExecutionStoreProperties(null, null, null, null));
        try {
            ExecutionRecord toRemove = executionRecord("to-remove");
            store.put(toRemove);

            store.remove("to-remove");

            assertThat(store.getOrNull("to-remove")).isNull();
        } finally {
            store.shutdown();
        }
    }
}
