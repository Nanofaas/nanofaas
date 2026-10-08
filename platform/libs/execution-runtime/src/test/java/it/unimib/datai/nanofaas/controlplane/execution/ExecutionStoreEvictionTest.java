package it.unimib.datai.nanofaas.controlplane.execution;

import com.github.benmanes.caffeine.cache.Ticker;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** The mechanics of the two structures: what sits where, and when it leaves. */
class ExecutionStoreEvictionTest {

    private static final Duration TTL = Duration.ofMinutes(5);
    private static final Duration MAX_LIFETIME = Duration.ofMinutes(30);
    private static final Duration SYNC_TTL = Duration.ofSeconds(30);

    private final AtomicLong clock = new AtomicLong();
    private final Ticker ticker = clock::get;

    private ExecutionStore store() {
        return store(ExecutionStoreProperties.of(TTL, MAX_LIFETIME, SYNC_TTL));
    }

    private ExecutionStore store(ExecutionStoreProperties props) {
        ExecutionStore store = new ExecutionStore(props, ticker);
        // The owner is mandatory for settle(); the store-level tests attach a minimal one.
        new ExecutionLifecycle(store, new IdempotencyStore(props, ticker));
        return store;
    }

    private void advance(Duration duration) {
        clock.addAndGet(duration.toNanos());
    }

    @Test
    void aLiveRecordIsFoundAmongTheLiving() {
        ExecutionStore store = store();
        store.put(executionRecord("running"));

        assertThat(store.getOrNull("running")).isNotNull();
        assertThat(store.outcomeOf("running")).isNull();
        assertThat(store.inFlightCount()).isEqualTo(1);
        assertThat(store.size()).isZero();
    }

    @Test
    void settleMovesTheRecordOutOfTheLiving() {
        ExecutionStore store = store();
        ExecutionRecord execution = executionRecord("done");
        store.put(execution);
        execution.markSuccess("out");

        store.settle(execution);

        // This is the point of the whole exercise: the apparatus of the living - the
        // future, the task, the request, the set of attempts - stops being reachable.
        assertThat(store.getOrNull("done")).isNull();
        assertThat(store.outcomeOf("done")).isNotNull();
        assertThat(store.inFlightCount()).isZero();
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void settleIgnoresARecordThatIsStillRunning() {
        ExecutionStore store = store();
        ExecutionRecord execution = executionRecord("still-going");
        store.put(execution);
        execution.markRunning();

        store.settle(execution);

        assertThat(store.getOrNull("still-going")).isNotNull();
        assertThat(store.outcomeOf("still-going")).isNull();
    }

    @Test
    void settleIsIdempotent() {
        // There are nine call sites across three modules, and some of them overlap: a
        // dispatch that completes after the sync path has already timed out.
        ExecutionStore store = store();
        ExecutionRecord execution = executionRecord("twice");
        store.put(execution);
        execution.markSuccess("out");

        store.settle(execution);
        store.settle(execution);

        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void aStuckRecordExpiresAfterMaxLifetime() {
        ExecutionStore store = store();
        store.put(executionRecord("stuck-queued")); // never transitions: lost dispatch

        advance(MAX_LIFETIME.plusSeconds(1));

        assertThat(store.getOrNull("stuck-queued")).isNull();
    }

    @Test
    void aFreshNonTerminalRecordSurvives() {
        ExecutionStore store = store();
        store.put(executionRecord("fresh"));

        advance(MAX_LIFETIME.dividedBy(2));

        assertThat(store.getOrNull("fresh")).isNotNull();
    }

    @Test
    void theOutcomeCapBoundsMemoryWhereTheClockCannot() {
        // Retention declared in time lets memory grow with the arrival rate: that is how
        // 2026-08-23 reached 1.05 GB. The capacity cap is the knob that did not exist then.
        ExecutionStore store = store(ExecutionStoreProperties.of(TTL, MAX_LIFETIME, SYNC_TTL, 10));

        for (int i = 0; i < 500; i++) {
            ExecutionRecord execution = executionRecord("exec-" + i);
            store.put(execution);
            execution.markSuccess("out");
            store.settle(execution);
        }

        // The cap is in BYTES (a budget of 10 compact outcomes), so the number retained
        // follows the weight of the individual outcome instead of being exactly 10. What
        // the test asserts - that the cap bounds memory where the clock cannot - holds
        // regardless: 500 insertions, about a dozen retained.
        assertThat(store.size()).isLessThanOrEqualTo(15);
    }

    @Test
    void removeDeletesFromBothStructures() {
        ExecutionStore store = store();
        ExecutionRecord execution = executionRecord("to-remove");
        store.put(execution);
        execution.markSuccess("out");
        store.settle(execution);

        store.remove("to-remove");

        assertThat(store.getOrNull("to-remove")).isNull();
        assertThat(store.outcomeOf("to-remove")).isNull();
    }

    @Test
    void everyTerminalStateSettles() {
        ExecutionStore store = store();

        ExecutionRecord success = executionRecord("success");
        store.put(success);
        success.markSuccess("ok");
        store.settle(success);

        ExecutionRecord error = executionRecord("error");
        store.put(error);
        error.markError(new ErrorInfo("ERR", "boom"));
        store.settle(error);

        ExecutionRecord timeout = executionRecord("timeout");
        store.put(timeout);
        timeout.markTimeout();
        store.settle(timeout);

        assertThat(store.outcomeOf("success").state()).isEqualTo(ExecutionState.SUCCESS);
        assertThat(store.outcomeOf("error").state()).isEqualTo(ExecutionState.ERROR);
        assertThat(store.outcomeOf("timeout").state()).isEqualTo(ExecutionState.TIMEOUT);
        assertThat(store.inFlightCount()).isZero();
    }

    @Test
    void everyTerminalListenerRunsEvenWhenAnEarlierOneThrows() {
        // The listeners are independent collaborators registered by different beans in an
        // order Spring decides. A metrics listener blowing up must not cost the idempotency
        // key its terminal transition — that would silently reopen the re-execution window.
        ExecutionStore store = store();
        List<String> ran = new java.util.ArrayList<>();
        store.onTerminal(executionRecord -> {
            ran.add("first");
            throw new IllegalStateException("listener failed");
        });
        store.onTerminal(executionRecord -> ran.add("second"));

        ExecutionRecord execution = executionRecord("exec");
        store.put(execution);
        execution.markSuccess("ok");
        store.settle(execution);

        assertThat(ran).containsExactly("first", "second");
        assertThat(store.outcomeOf("exec").state()).isEqualTo(ExecutionState.SUCCESS);
    }

    @Test
    void aNotCacheableOutcomeIsDroppedButTheTerminalTransitionStillRuns() {
        // A payload whose size cannot be bounded is declined by settle(): the outcome is
        // not retained, but the terminal notification still runs, because the idempotency
        // key's tombstone - not the payload - is what keeps a replay from re-invoking.
        ExecutionStore store = store();
        List<String> terminal = new java.util.ArrayList<>();
        store.onTerminal(executionRecord -> terminal.add(executionRecord.executionId()));

        ExecutionRecord executionRecord = asyncExecutionRecord("oversized");
        store.put(executionRecord);
        Object payload = "x";
        for (int depth = 0; depth < 5; depth++) {
            payload = List.of(payload);
        }
        executionRecord.markSuccess(payload);
        store.settle(executionRecord);

        assertThat(store.size()).isZero();
        assertThat(store.outcomeOf("oversized")).isNull();
        assertThat(terminal).containsExactly("oversized");
    }

    private static ExecutionRecord executionRecord(String id) {
        return executionRecord(id, InvocationKind.SYNC);
    }

    private static ExecutionRecord asyncExecutionRecord(String id) {
        return executionRecord(id, InvocationKind.ASYNC);
    }

    private static ExecutionRecord executionRecord(String id, InvocationKind kind) {
        FunctionSpec spec = new FunctionSpec("fn", "img", List.of(), Map.of(), null,
                1000, 1, 10, 0, null, ExecutionMode.LOCAL, RuntimeMode.HTTP, null, null, null);
        InvocationTask task = new InvocationTask(id, "fn", spec,
                new InvocationRequest("payload", Map.of()), null, null, Instant.now(), 1,
                kind);
        return new ExecutionRecord(id, task);
    }
}
