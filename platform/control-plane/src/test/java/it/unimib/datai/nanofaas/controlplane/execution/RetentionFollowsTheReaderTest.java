package it.unimib.datai.nanofaas.controlplane.execution;

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

/**
 * What the store keeps, and for whom.
 *
 * At steady state the store holds `retention x admission rate`, and one clock for
 * every execution made that 270,000 records and 1.05 GB of live data against a
 * 1,002 MB tenured generation on 2026-08-23 - the collector permanently at its
 * limit, 50.6% of wall time in GC, pauses of 2.851 s, and the container killed by
 * a liveness probe it could no longer answer. All of it held for readers that,
 * for plain synchronous traffic, do not exist: the answer went back on the
 * connection the caller was holding.
 */
class RetentionFollowsTheReaderTest {

    private static final Duration SYNC_TTL = Duration.ofMillis(50);
    private static final ExecutionStoreProperties PROPS = new ExecutionStoreProperties(
            Duration.ofMinutes(5), Duration.ofMinutes(2), Duration.ofMinutes(30), SYNC_TTL);

    @SuppressWarnings("java:S2925")
    @Test
    void aDeliveredSynchronousAnswerIsNotKept() throws InterruptedException {
        ExecutionStore store = new ExecutionStore(PROPS);
        try {
            store.put(finished("plain", InvocationKind.SYNC, null));
            Thread.sleep(120);
            store.evictExpired();

            assertThat(store.getOrNull("plain")).isNull();
        } finally {
            store.shutdown();
        }
    }

    @SuppressWarnings("java:S2925")
    @Test
    void anAsynchronousCallerHasNothingButTheId() throws InterruptedException {
        ExecutionStore store = new ExecutionStore(PROPS);
        try {
            store.put(finished("queued-work", InvocationKind.ASYNC, null));
            Thread.sleep(120);
            store.evictExpired();

            // GET /v1/executions/{id} is its only way of learning the outcome.
            assertThat(store.getOrNull("queued-work")).isNotNull();
        } finally {
            store.shutdown();
        }
    }

    @SuppressWarnings("java:S2925")
    @Test
    void aKeyedExecutionOutlivesTheAnswerItHandedBack() throws InterruptedException {
        ExecutionStore store = new ExecutionStore(PROPS);
        try {
            store.put(finished("keyed", InvocationKind.SYNC, "order-8821"));
            Thread.sleep(120);
            store.evictExpired();

            assertThat(store.getOrNull("keyed")).isNotNull();
        } finally {
            store.shutdown();
        }
    }

    @SuppressWarnings("java:S2925")
    @Test
    void aRetryDoesNotDemoteAKeyedExecution() throws InterruptedException {
        ExecutionStore store = new ExecutionStore(PROPS);
        try {
            ExecutionRecord record = finished("retried", InvocationKind.SYNC, "order-8821");
            store.put(record);

            // ExecutionCompletionHandler builds the retry task WITHOUT the key - the
            // retry is internal and must not claim it again. Read from the current
            // task, retention would demote exactly the executions that had trouble,
            // and a client replaying its key would find nothing and be charged twice.
            record.resetForRetry(task("retried", InvocationKind.SYNC, null, 2));
            record.markSuccess("done");

            Thread.sleep(120);
            store.evictExpired();

            assertThat(store.getOrNull("retried")).isNotNull();
        } finally {
            store.shutdown();
        }
    }

    private static ExecutionRecord finished(String id, InvocationKind kind, String key) {
        ExecutionRecord record = new ExecutionRecord(id, task(id, kind, key, 1));
        record.markSuccess("done");
        return record;
    }

    private static InvocationTask task(String id, InvocationKind kind, String key, int attempt) {
        FunctionSpec spec = new FunctionSpec("fn", "img", List.of(), Map.of(), null,
                1000, 1, 10, 0, null, ExecutionMode.LOCAL, RuntimeMode.HTTP, null, null, null);
        return new InvocationTask(id, "fn", spec, new InvocationRequest("payload", Map.of()),
                key, null, Instant.now(), attempt, kind);
    }
}
