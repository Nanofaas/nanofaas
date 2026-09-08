package it.unimib.datai.nanofaas.controlplane.execution;

import com.github.benmanes.caffeine.cache.Ticker;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the store keeps, and for whom.
 *
 * <p>At steady state the store holds `retention x admission rate`, and a single clock
 * for every execution produced 270,000 records and 1.05 GB of live data against a
 * 1,002 MB tenured generation on 2026-08-23: the collector permanently at the limit,
 * 50.6% of the time in GC, pauses of 2.851 s, and the container killed by a liveness
 * probe it could no longer answer. All of it retained for readers that, for plain
 * synchronous traffic, do not exist: the response went back on the connection the
 * caller was holding.
 *
 * <p>Hence the second rule, measured on 2026-08-26: what has no readers does not carry
 * the payload either. A complete outcome weighs 4,916 bytes with a 4 KB response;
 * without the payload it weighs 116, whatever the response was.
 */
class RetentionFollowsTheReaderTest {

    private static final Duration SYNC_TTL = Duration.ofSeconds(30);
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final ExecutionStoreProperties PROPS =
            ExecutionStoreProperties.of(TTL, Duration.ofMinutes(30), SYNC_TTL);

    private final AtomicLong clock = new AtomicLong();
    private final Ticker ticker = clock::get;

    private ExecutionStore store() {
        return new ExecutionStore(PROPS, ticker);
    }

    private void advance(Duration duration) {
        clock.addAndGet(duration.toNanos());
    }

    // --- chi sopravvive ---------------------------------------------------

    @Test
    void aDeliveredSynchronousAnswerIsNotKept() {
        ExecutionStore store = store();
        store.settle(settled(store, "plain", InvocationKind.SYNC, null));

        advance(SYNC_TTL.plusSeconds(1));

        assertThat(store.outcomeOf("plain")).isNull();
    }

    @Test
    void anAsynchronousCallerHasNothingButTheId() {
        ExecutionStore store = store();
        store.settle(settled(store, "queued-work", InvocationKind.ASYNC, null));

        advance(SYNC_TTL.plusSeconds(1));

        // GET /v1/executions/{id} is its only route to the result.
        assertThat(store.outcomeOf("queued-work")).isNotNull();

        advance(TTL);
        assertThat(store.outcomeOf("queued-work")).isNull();
    }

    @Test
    void aKeyedExecutionOutlivesTheAnswerItHandedBack() {
        ExecutionStore store = store();
        store.settle(settled(store, "keyed", InvocationKind.SYNC, "order-8821"));

        advance(SYNC_TTL.plusSeconds(1));

        assertThat(store.outcomeOf("keyed")).isNotNull();
    }

    @Test
    void aRetryDoesNotDemoteAKeyedExecution() {
        ExecutionStore store = store();
        ExecutionRecord execution = new ExecutionRecord("retried", task("retried", InvocationKind.SYNC, "order-8821", 1));
        store.put(execution);

        // ExecutionCompletionHandler builds the retry task WITHOUT the key - the retry
        // is internal and must not claim it again. Read from the current task, retention
        // would demote exactly the executions that had trouble, and a client replaying
        // its key would find nothing and be charged twice.
        execution.resetForRetry(task("retried", InvocationKind.SYNC, null, 2));
        execution.markSuccess("done");
        store.settle(execution);

        advance(SYNC_TTL.plusSeconds(1));

        assertThat(store.outcomeOf("retried")).isNotNull();
        assertThat(store.outcomeOf("retried").output()).isEqualTo("done");
    }

    // --- cosa si portano dietro -------------------------------------------

    @Test
    void aPlainSynchronousOutcomeDropsThePayloadItAlreadyDelivered() {
        ExecutionStore store = store();
        store.settle(settled(store, "plain", InvocationKind.SYNC, null));

        Outcome outcome = store.outcomeOf("plain");
        assertThat(outcome).isNotNull();
        assertThat(outcome.readable()).isFalse();
        assertThat(outcome.output()).isNull();
        assertThat(outcome.headers()).isNull();
        assertThat(outcome.encoding()).isNull();
    }

    @Test
    void anAsynchronousOutcomeKeepsThePayloadNobodyElseHas() {
        ExecutionStore store = store();
        store.settle(settled(store, "queued-work", InvocationKind.ASYNC, null));

        Outcome outcome = store.outcomeOf("queued-work");
        assertThat(outcome.readable()).isTrue();
        assertThat(outcome.output()).isEqualTo("done");
        assertThat(outcome.headers()).containsExactly(Map.entry("Content-Type", "application/json"));
        assertThat(outcome.encoding()).isEqualTo("json");
    }

    @Test
    void aKeyedOutcomeKeepsThePayloadItsReplayMustReturn() {
        ExecutionStore store = store();
        store.settle(settled(store, "keyed", InvocationKind.SYNC, "order-8821"));

        // Serving an empty replay would be the double execution the key exists to
        // prevent, not a degradation.
        assertThat(store.outcomeOf("keyed").output()).isEqualTo("done");
    }

    @Test
    void everyOutcomeKeepsItsErrorAndItsTimings() {
        ExecutionStore store = store();
        ExecutionRecord execution = new ExecutionRecord("failed", task("failed", InvocationKind.SYNC, null, 1));
        execution.markRunning();
        execution.markError(new it.unimib.datai.nanofaas.common.model.ErrorInfo("BOOM", "blew up"));
        store.put(execution);
        store.settle(execution);

        Outcome outcome = store.outcomeOf("failed");
        // Two strings: the only thing worth re-reading if the connection dropped before
        // the response body.
        assertThat(outcome.error().code()).isEqualTo("BOOM");
        assertThat(outcome.startedAt()).isNotNull();
        assertThat(outcome.finishedAt()).isNotNull();
    }

    // --- fixture -----------------------------------------------------------

    private static ExecutionRecord settled(ExecutionStore store, String id, InvocationKind kind, String key) {
        ExecutionRecord execution = new ExecutionRecord(id, task(id, kind, key, 1));
        store.put(execution);
        execution.markRunning();
        execution.markSuccess("done", 200, Map.of("Content-Type", "application/json"), "json");
        return execution;
    }

    private static InvocationTask task(String id, InvocationKind kind, String key, int attempt) {
        FunctionSpec spec = new FunctionSpec("fn", "img", List.of(), Map.of(), null,
                1000, 1, 10, 0, null, ExecutionMode.LOCAL, RuntimeMode.HTTP, null, null, null);
        return new InvocationTask(id, "fn", spec, new InvocationRequest("payload", Map.of()),
                key, null, Instant.now(), attempt, kind);
    }
}
