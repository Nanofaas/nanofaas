package it.unimib.datai.nanofaas.controlplane.execution;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Optional;

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
        Gauge.builder("execution_store_size", () -> outcomes.estimatedSize()).register(registry);
        Gauge.builder("execution_in_flight_records", () -> inFlight.estimatedSize()).register(registry);
    }

    ExecutionStore(ExecutionStoreProperties properties) {
        this(properties, Ticker.systemTicker());
    }

    /** Pacchetto-privato: le prove di sfratto muovono l'orologio invece di dormire. */
    ExecutionStore(ExecutionStoreProperties properties, Ticker ticker) {
        this.inFlight = Caffeine.newBuilder()
                .expireAfterWrite(properties.maxLifetime())
                .ticker(ticker)
                .build();
        this.outcomes = Caffeine.newBuilder()
                .maximumSize(properties.maxOutcomes())
                .expireAfter(Expiry.creating((String id, Outcome outcome) ->
                        outcome.readable() ? properties.ttl() : properties.syncTtl()))
                .ticker(ticker)
                .build();
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
