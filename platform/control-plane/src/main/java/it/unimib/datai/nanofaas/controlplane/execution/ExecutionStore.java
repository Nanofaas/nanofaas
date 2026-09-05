package it.unimib.datai.nanofaas.controlplane.execution;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Chi sta eseguendo, e cosa ne resta dopo.
 *
 * <p>Erano una struttura sola, e le due vite non si somigliano. Il 2026-08-26, in
 * un run a 1.093 ammissioni al secondo, {@code function_inFlight} arrivava a
 * <b>2</b> mentre lo store ne teneva <b>165.786</b>: il 99,999% di cio' che
 * conservava era postumo, e si portava dietro l'apparato del vivo - la future, il
 * task, la richiesta, il set dei tentativi rilasciati. Trentacinque oggetti per
 * record, cinque milioni e ottocentomila in tutto, che ogni raccolta completa
 * doveva attraversare.
 *
 * <p>Da qui in poi sono due. {@link #inFlight} tiene i record mutabili finche'
 * servono; {@link #outcomes} tiene {@link Outcome}, piatti e immutabili, per chi
 * puo' ancora chiederli.
 *
 * <p>Entrambe sono Caffeine, come {@link IdempotencyStore} un file piu' in la'.
 * Al posto del janitor che ogni minuto attraversava l'intera mappa - 12,4 ms su
 * 166.000 record, e ogni oggetto della vecchia generazione toccato per nulla -
 * lo sfratto e' ammortizzato sulle scritture. E {@code maximumSize} e' il tetto
 * in spazio che qui e' sempre mancato: prima la ritenzione era dichiarata nel
 * tempo e illimitata nel numero, quindi la memoria cresceva col tasso di arrivo.
 * Il 2026-08-23 questo significava 1,05 GB, il 50,6% del tempo in GC e un probe
 * di liveness mancato tre volte di fila.
 */
@Component
public class ExecutionStore {
    private static final Logger log = LoggerFactory.getLogger(ExecutionStore.class);

    /**
     * Chi sta ancora eseguendo. Scade da solo dopo {@code maxLifetime}: e' cio'
     * che sostituisce il ramo omonimo del vecchio janitor per le esecuzioni
     * incastrate (dispatch perso, callback mai arrivata), ed e' anche la rete di
     * sicurezza se una {@link #settle} venisse dimenticata su un percorso nuovo -
     * il record scade invece di restare per sempre.
     */
    private final Cache<String, ExecutionRecord> inFlight;

    /** Cosa ne resta. Limitato in numero, e con scadenza decisa per voce. */
    private final Cache<String, Outcome> outcomes;

    /**
     * Chi va avvertito quando {@link #inFlight} sfratta un record da solo, per
     * {@code maxLifetime} scaduto - non perche' qualcuno lo ha archiviato con
     * {@link #settle}.
     *
     * <p>Di default nessuno: uno store usato senza registrare un ascoltatore si
     * comporta come prima, sfratto silenzioso. {@code ExecutionCompletionHandler}
     * si registra qui in produzione, perche' e' lui a sapere come chiudere un
     * dispatch abbandonato - completare la future condivisa, rilasciare lo slot,
     * archiviare l'esito - non lo store, che di slot e future condivise non sa
     * nulla al di fuori del record stesso.
     */
    private volatile Consumer<ExecutionRecord> expiryListener = record -> { };

    /**
     * Chi va avvertito quando un record terminale viene archiviato con
     * {@link #settle} - l'istante da cui parte la ritenzione terminale della chiave
     * di idempotenza. Di default nessuno. {@code InvocationExecutionFactory}
     * registra qui {@code IdempotencyStore}, perche' il vincolo della chiave deve
     * passare dallo stato "vivo" a quello "terminale" esattamente quando l'esito
     * esce dai vivi, senza finestre in cui la stessa chiave torni acquisibile;
     * {@code ExecutionCompletionHandler} registra la conclusione end-to-end, perche'
     * archiviarsi e' l'unico evento comune a OGNI politica terminale.
     */
    private final List<Consumer<ExecutionRecord>> terminalListeners = new CopyOnWriteArrayList<>();

    public ExecutionStore() {
        this(ExecutionStoreProperties.of(null, null, null));
    }

    // @Autowired e' necessario: con due costruttori Spring sceglierebbe quello
    // senza argomenti e ignorerebbe in silenzio le proprieta' configurate.
    @Autowired
    public ExecutionStore(ExecutionStoreProperties properties, MeterRegistry registry) {
        this(properties);
        // Quanto la piattaforma sta ricordando, e quanto sta davvero eseguendo.
        // Fu la distanza fra questi due numeri a mostrare il problema: senza il
        // secondo, il primo si poteva ancora scambiare per lavoro in corso.
        Gauge.builder("execution_store_size", outcomes::estimatedSize).register(registry);
        Gauge.builder("execution_in_flight_records", inFlight::estimatedSize).register(registry);
    }

    ExecutionStore(ExecutionStoreProperties properties) {
        this(properties, Ticker.systemTicker());
    }

    /** Le prove di sfratto muovono l'orologio invece di dormire; public per i test nel package service. */
    public ExecutionStore(ExecutionStoreProperties properties, Ticker ticker) {
        this.inFlight = Caffeine.newBuilder()
                .expireAfterWrite(properties.maxLifetime())
                .ticker(ticker)
                // Senza questo, Caffeine controlla la scadenza solo quando qualcosa
                // tocca la cache - una get, una put, una cleanUp() esplicita. Un
                // record incastrato per un dispatch perso, con nessuno che lo
                // rilegge mai (un chiamante ASYNC che non interroga piu', o nessun
                // chiamante affatto), restava scaduto ma vivo indefinitamente: lo
                // slot di concorrenza che teneva non tornava mai al budget. Lo
                // scheduler pianifica lo sfratto sul tempo reale, indipendente da
                // qualunque traffico successivo sulla cache.
                .scheduler(Scheduler.systemScheduler())
                .removalListener((String executionId, ExecutionRecord executionRecord, RemovalCause cause) -> {
                    // EXPLICIT e' settle()/remove() - gia' gestito da chi le chiama.
                    // REPLACED non si applica: nessun path fa put() due volte sullo
                    // stesso id. Solo EXPIRED e' lo sfratto che nessuno ha deciso.
                    if (cause == RemovalCause.EXPIRED && executionRecord != null) {
                        expiryListener.accept(executionRecord);
                    }
                })
                .build();
        this.outcomes = Caffeine.newBuilder()
                .maximumSize(properties.maxOutcomes())
                .expireAfter(Expiry.creating((String id, Outcome outcome) ->
                        outcome.readable() ? properties.ttl() : properties.syncTtl()))
                .ticker(ticker)
                .build();
    }

    /**
     * Registra chi chiude un dispatch abbandonato quando {@code maxLifetime}
     * scade da solo. Non additivo: l'ultima registrazione vince, come per ogni
     * singleton Spring che si registra una volta sola all'avvio.
     */
    public void onAdministrativeExpiry(Consumer<ExecutionRecord> listener) {
        this.expiryListener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Registra chi va avvertito quando un'esecuzione si archivia. Additivo: ogni
     * collaboratore interessato al momento terminale si aggiunge, e nessuno puo'
     * silenziare l'altro. Prima era uno slot singolo "l'ultimo vince", e bastava un
     * secondo costruttore a far sparire in silenzio la transizione della chiave.
     */
    public void onTerminal(Consumer<ExecutionRecord> listener) {
        terminalListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** Quanti esiti sono archiviati adesso. */
    public int size() {
        outcomes.cleanUp();
        return (int) outcomes.estimatedSize();
    }

    /** Quanti record stanno ancora eseguendo. */
    public int inFlightCount() {
        inFlight.cleanUp();
        return (int) inFlight.estimatedSize();
    }

    public void put(ExecutionRecord executionRecord) {
        inFlight.put(executionRecord.executionId(), executionRecord);
    }

    public Optional<ExecutionRecord> get(String executionId) {
        return Optional.ofNullable(inFlight.getIfPresent(executionId));
    }

    /** Lettura sul percorso caldo, senza allocare un Optional. */
    @Nullable
    public ExecutionRecord getOrNull(String executionId) {
        return inFlight.getIfPresent(executionId);
    }

    /** L'esito archiviato, se l'esecuzione e' finita e qualcuno puo' ancora leggerla. */
    @Nullable
    public Outcome outcomeOf(String executionId) {
        return outcomes.getIfPresent(executionId);
    }

    /**
     * La transizione terminale: qui l'apparato del vivo muore, non 152 secondi dopo.
     *
     * <p>Idempotente, perche' le sedi che la chiamano sono nove e sparse su tre
     * moduli Gradle, e alcune si sovrappongono (un dispatch che completa dopo che
     * il percorso sincrono e' gia' andato in timeout). Chiamarla su un record non
     * terminale non fa nulla: il record e' ancora vivo e {@code inFlight} deve
     * continuare a trovarlo.
     */
    public void settle(ExecutionRecord executionRecord) {
        if (!executionRecord.isTerminal()) {
            return;
        }
        String executionId = executionRecord.executionId();
        outcomes.put(executionId, executionRecord.toOutcome());
        inFlight.invalidate(executionId);
        // Dopo l'archivio, e non prima: solo adesso l'esito e' servibile, ed e' da
        // qui che parte la ritenzione terminale della chiave. Invertire l'ordine
        // riaprirebbe la finestra in cui la chiave e' gia' terminale mentre l'esito
        // non e' ancora visibile ai replay.
        for (Consumer<ExecutionRecord> listener : terminalListeners) {
            try {
                listener.accept(executionRecord);
            } catch (RuntimeException ex) {
                // I listener sono collaboratori indipendenti registrati da bean diversi, in un
                // ordine che decide Spring. Se uno fallisce, gli altri devono comunque girare:
                // perdere la transizione terminale della chiave di idempotenza perche' e' saltata
                // una metrica riaprirebbe in silenzio la finestra di riesecuzione.
                log.warn("Terminal listener failed for execution {}", executionRecord.executionId(), ex);
            }
        }
    }

    public void remove(String executionId) {
        inFlight.invalidate(executionId);
        outcomes.invalidate(executionId);
    }

    /** Le prove deterministiche forzano la manutenzione invece di aspettarla. */
    void cleanUp() {
        inFlight.cleanUp();
        outcomes.cleanUp();
    }
}
