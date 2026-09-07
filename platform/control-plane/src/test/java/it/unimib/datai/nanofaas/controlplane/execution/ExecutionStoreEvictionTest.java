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

/** La meccanica delle due strutture: chi sta dove, e quando se ne va. */
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
        return new ExecutionStore(props, ticker);
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

        // E' questo il punto di tutto il lavoro: l'apparato del vivo - la future,
        // il task, la richiesta, il set dei tentativi - smette di essere raggiungibile.
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
        // Le sedi che la chiamano sono nove su tre moduli, e alcune si sovrappongono:
        // un dispatch che completa dopo che il percorso sincrono e' gia' andato in timeout.
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
        store.put(executionRecord("stuck-queued")); // non transita mai: dispatch perso

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
        // La ritenzione dichiarata nel tempo lascia crescere la memoria col tasso
        // di arrivo: e' cosi' che il 2026-08-23 si arrivo' a 1,05 GB. Il tetto in
        // numero e' la manopola che allora non esisteva.
        ExecutionStore store = store(ExecutionStoreProperties.of(TTL, MAX_LIFETIME, SYNC_TTL, 10));

        for (int i = 0; i < 500; i++) {
            ExecutionRecord execution = executionRecord("exec-" + i);
            store.put(execution);
            execution.markSuccess("out");
            store.settle(execution);
        }

        // Il tetto e' in BYTE (10 esiti compatti di budget), quindi il numero trattenuto
        // segue il peso del singolo esito invece di essere esattamente 10. Cio' che il
        // test afferma - che il tetto limita la memoria dove l'orologio non arriva -
        // vale comunque: 500 inserimenti, una dozzina trattenuti.
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
        store.onTerminal(record -> {
            ran.add("first");
            throw new IllegalStateException("listener failed");
        });
        store.onTerminal(record -> ran.add("second"));

        ExecutionRecord record = executionRecord("exec");
        store.put(record);
        record.markSuccess("ok");
        store.settle(record);

        assertThat(ran).containsExactly("first", "second");
        assertThat(store.outcomeOf("exec").state()).isEqualTo(ExecutionState.SUCCESS);
    }

    private static ExecutionRecord executionRecord(String id) {
        FunctionSpec spec = new FunctionSpec("fn", "img", List.of(), Map.of(), null,
                1000, 1, 10, 0, null, ExecutionMode.LOCAL, RuntimeMode.HTTP, null, null, null);
        InvocationTask task = new InvocationTask(id, "fn", spec,
                new InvocationRequest("payload", Map.of()), null, null, Instant.now(), 1,
                InvocationKind.SYNC);
        return new ExecutionRecord(id, task);
    }
}
