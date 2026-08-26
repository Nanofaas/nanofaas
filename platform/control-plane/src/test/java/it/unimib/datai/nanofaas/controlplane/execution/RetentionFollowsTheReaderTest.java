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
 * Cosa lo store conserva, e per chi.
 *
 * <p>Allo stato stazionario lo store tiene `ritenzione x tasso di ammissione`, e
 * un solo orologio per ogni esecuzione fece 270.000 record e 1,05 GB di dati vivi
 * contro una tenured da 1.002 MB il 2026-08-23: il collettore permanentemente al
 * limite, il 50,6% del tempo in GC, pause da 2,851 s, e il container ucciso da un
 * probe di liveness a cui non riusciva piu' a rispondere. Tutto trattenuto per
 * lettori che, per il traffico sincrono semplice, non esistono: la risposta e'
 * tornata sulla connessione che il chiamante aveva in mano.
 *
 * <p>Da qui la seconda regola, misurata il 2026-08-26: chi non ha lettori non si
 * porta dietro nemmeno il payload. Un esito completo pesa 4.916 byte con una
 * risposta da 4 KB; senza payload ne pesa 116, quale che sia la risposta.
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

        // GET /v1/executions/{id} e' la sua unica strada verso il risultato.
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
        ExecutionRecord record = new ExecutionRecord("retried", task("retried", InvocationKind.SYNC, "order-8821", 1));
        store.put(record);

        // ExecutionCompletionHandler costruisce il task di retry SENZA la chiave -
        // il retry e' interno e non deve rivendicarla di nuovo. Letta dal task
        // corrente, la ritenzione declasserebbe proprio le esecuzioni che hanno
        // avuto problemi, e un client che replica la sua chiave non troverebbe
        // nulla e verrebbe addebitato due volte.
        record.resetForRetry(task("retried", InvocationKind.SYNC, null, 2));
        record.markSuccess("done");
        store.settle(record);

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

        // Servire un replay vuoto sarebbe la doppia esecuzione che la chiave
        // esiste per impedire, non una degradazione.
        assertThat(store.outcomeOf("keyed").output()).isEqualTo("done");
    }

    @Test
    void everyOutcomeKeepsItsErrorAndItsTimings() {
        ExecutionStore store = store();
        ExecutionRecord record = new ExecutionRecord("failed", task("failed", InvocationKind.SYNC, null, 1));
        record.markRunning();
        record.markError(new it.unimib.datai.nanofaas.common.model.ErrorInfo("BOOM", "esploso"));
        store.put(record);
        store.settle(record);

        Outcome outcome = store.outcomeOf("failed");
        // Due stringhe: l'unica cosa che ha senso rileggere se la connessione e'
        // caduta prima del corpo della risposta.
        assertThat(outcome.error().code()).isEqualTo("BOOM");
        assertThat(outcome.startedAt()).isNotNull();
        assertThat(outcome.finishedAt()).isNotNull();
    }

    // --- fixture -----------------------------------------------------------

    private static ExecutionRecord settled(ExecutionStore store, String id, InvocationKind kind, String key) {
        ExecutionRecord record = new ExecutionRecord(id, task(id, kind, key, 1));
        store.put(record);
        record.markRunning();
        record.markSuccess("done", 200, Map.of("Content-Type", "application/json"), "json");
        return record;
    }

    private static InvocationTask task(String id, InvocationKind kind, String key, int attempt) {
        FunctionSpec spec = new FunctionSpec("fn", "img", List.of(), Map.of(), null,
                1000, 1, 10, 0, null, ExecutionMode.LOCAL, RuntimeMode.HTTP, null, null, null);
        return new InvocationTask(id, "fn", spec, new InvocationRequest("payload", Map.of()),
                key, null, Instant.now(), attempt, kind);
    }
}
