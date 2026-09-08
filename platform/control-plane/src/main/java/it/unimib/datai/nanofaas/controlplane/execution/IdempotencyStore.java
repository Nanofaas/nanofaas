package it.unimib.datai.nanofaas.controlplane.execution;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;

/**
 * Il vincolo fra una chiave di idempotenza e l'esecuzione che ne risponde.
 *
 * <p>Una chiave vive in tre stati, ognuno con la sua scadenza:
 * <ul>
 *   <li><b>pending</b> - una richiesta l'ha rivendicata e non l'ha ancora
 *       pubblicata. Scade dopo {@code maxLifetime}: e' il tetto di una
 *       rivendicazione abbandonata, non diverso da quello di un'esecuzione
 *       incastrata.</li>
 *   <li><b>published</b> - vincolata a un'esecuzione ancora viva. Scade dopo
 *       {@code maxLifetime}, lo stesso orizzonte di {@code ExecutionStore.inFlight}:
 *       la chiave e l'esecuzione muoiono insieme se il dispatch non torna.</li>
 *   <li><b>terminal</b> - l'esecuzione si e' archiviata ({@link #markTerminal}).
 *       Da qui parte la ritenzione terminale: {@code ttl} dal completamento, non
 *       dalla pubblicazione. E' questo stato a fare da tombstone quando l'esito
 *       viene espulso per capacita': il vincolo resta, il payload no.</li>
 * </ul>
 *
 * <p>La scadenza per stato e' derivata dalle proprieta' dello store delle
 * esecuzioni, mai configurata per conto suo: la chiave e' utile solo finche' la
 * risposta che indica esiste, e una chiave che scade prima dell'esito fa girare la
 * funzione due volte in silenzio - esattamente il fallimento che la chiave esiste
 * per impedire.
 *
 * <p>Il numero di chiavi e' limitato da {@code maxKeys}. A budget esaurito una
 * <b>nuova</b> ammissione con chiave viene rifiutata ({@code acquireOrGet} non
 * rivendica), ma i replay delle chiavi gia' presenti restano servibili: il tetto
 * non sfratta mai una chiave viva per fare spazio a una nuova, altrimenti un
 * successivo budget in byte potrebbe espellere silenziosamente la protezione di
 * deduplicazione.
 */
@Component
public class IdempotencyStore {
    private final Cache<String, StoredKey> cache;
    private final ConcurrentMap<String, StoredKey> keys;
    private final long maxKeys;

    public IdempotencyStore() {
        this(ExecutionStoreProperties.of(null, null, null));
    }

    @Autowired
    public IdempotencyStore(ExecutionStoreProperties executions, MeterRegistry registry) {
        this(executions);
        // Whether keys are released on schedule is otherwise invisible until the heap
        // says so: a run at 843 requests a second with 5% of them keyed files roughly
        // 19,000. A supplier gauge, read at scrape time, nothing on the invocation path.
        Gauge.builder("idempotency_keys_held", this::size).register(registry);
    }

    /** Test convenience: one lifetime for both the live and terminal phases. */
    public IdempotencyStore(Duration ttl) {
        this(ttl, Ticker.systemTicker());
    }

    IdempotencyStore(Duration ttl, Ticker ticker) {
        this(ExecutionStoreProperties.of(ttl, ttl, ttl), ticker);
    }

    public IdempotencyStore(ExecutionStoreProperties executions, Ticker ticker) {
        this.maxKeys = executions.maxKeys();
        long liveNanos = executions.maxLifetime().toNanos();
        long terminalNanos = executions.ttl().toNanos();
        // Per stato, non una sola durata: una chiave pubblicata vive quanto puo' vivere
        // l'esecuzione (maxLifetime), una terminale quanto l'esito resta leggibile (ttl).
        // expireAfterUpdate ricomputa al passaggio di stato, cosi' markTerminal riparte
        // l'orologio dal completamento invece di ereditare il resto della fase viva.
        this.cache = Caffeine.newBuilder()
                .expireAfter(new Expiry<String, StoredKey>() {
                    @Override
                    public long expireAfterCreate(String key, StoredKey value, long currentTime) {
                        return value.terminal() ? terminalNanos : liveNanos;
                    }

                    @Override
                    public long expireAfterUpdate(String key, StoredKey value, long currentTime, long currentDuration) {
                        return value.terminal() ? terminalNanos : liveNanos;
                    }

                    @Override
                    public long expireAfterRead(String key, StoredKey value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .ticker(ticker)
                .build();
        this.keys = cache.asMap();
    }

    private IdempotencyStore(ExecutionStoreProperties executions) {
        this(executions, Ticker.systemTicker());
    }

    public Optional<String> getExecutionId(String functionName, String key) {
        StoredKey stored = keys.get(compose(functionName, key));
        if (stored == null || stored.pending()) {
            return Optional.empty();
        }
        return Optional.of(stored.executionId());
    }

    public void put(String functionName, String key, String executionId) {
        keys.put(compose(functionName, key), StoredKey.published(executionId));
    }

    public AcquireResult acquireOrGet(String functionName, String key) {
        String composed = compose(functionName, key);
        while (true) {
            StoredKey existing = keys.get(composed);
            if (existing == null) {
                // Il budget si controlla PRIMA di rivendicare, e mai sfrattando una chiave
                // viva: a budget esaurito una nuova ammissione con chiave viene rifiutata,
                // ma i replay delle chiavi gia' presenti continuano a trovare il loro esito.
                if (size() >= maxKeys) {
                    return AcquireResult.budgetExhausted();
                }
                String token = pendingToken();
                StoredKey pending = StoredKey.pending(token);
                if (keys.putIfAbsent(composed, pending) == null) {
                    return AcquireResult.claimed(token);
                }
                continue;
            }
            if (existing.pending()) {
                return AcquireResult.pending();
            }
            return AcquireResult.existing(existing.executionId(), existing.terminal());
        }
    }

    /**
     * Rivendica una chiave pubblicata il cui vincolo punta a un'esecuzione ormai
     * sparita senza essersi mai conclusa (ammissione abbandonata dopo la
     * pubblicazione, dispatch mai partito). Solo un vincolo <b>pubblicato</b> puo'
     * essere rivendicato: un vincolo terminale e' il tombstone, e rivendicarlo
     * riaprirebbe la finestra - chiusa da {@link #markTerminal} - in cui la stessa
     * chiave torna acquisibile e la funzione gira due volte.
     *
     * <p>Il CAS su {@code keys.replace} e' la vera guardia: se il vincolo e'
     * cambiato fra la lettura e il replace (transito a terminale incluso), il
     * replace fallisce e il loop rilegge.
     */
    public AcquireResult claimIfMatches(String functionName, String key, String expectedExecutionId) {
        String composed = compose(functionName, key);
        while (true) {
            StoredKey existing = keys.get(composed);
            if (existing == null) {
                return AcquireResult.missing();
            }
            if (existing.pending()) {
                return AcquireResult.pending();
            }
            if (existing.terminal() || !existing.executionId().equals(expectedExecutionId)) {
                return AcquireResult.existing(existing.executionId(), existing.terminal());
            }
            String token = pendingToken();
            StoredKey pending = StoredKey.pending(token);
            if (keys.replace(composed, existing, pending)) {
                return AcquireResult.claimed(token);
            }
        }
    }

    public void publishClaim(String functionName, String key, String claimToken, String executionId) {
        String composed = compose(functionName, key);
        while (true) {
            StoredKey existing = keys.get(composed);
            if (existing == null || !existing.pending() || !existing.executionId().equals(claimToken)) {
                throw new IllegalStateException("Missing idempotency claim for " + composed);
            }
            StoredKey published = StoredKey.published(executionId);
            if (keys.replace(composed, existing, published)) {
                return;
            }
        }
    }

    public void abandonClaim(String functionName, String key, String claimToken) {
        String composed = compose(functionName, key);
        StoredKey existing = keys.get(composed);
        if (existing != null && existing.pending() && existing.executionId().equals(claimToken)) {
            keys.remove(composed, existing);
        }
    }

    /**
     * La transizione al vincolo terminale, invocata dallo store delle esecuzioni
     * quando un record si archivia. La ritenzione terminale parte da qui, non dalla
     * pubblicazione, e chiude la finestra in cui la chiave sarebbe di nuovo
     * acquisibile mentre l'esito e' ancora (o appena stato) servibile.
     *
     * <p>Idempotente: su una chiave assente, pending o gia' terminale non fa nulla.
     *
     * <p>The transition happens only if the binding still points at
     * {@code expectedExecutionId}. Without that check, a replaced execution
     * (abandoned after publication and then re-claimed by a replay) settling late
     * would mark the NEW execution's binding terminal: terminal retention would
     * start at the wrong instant and never restart, because the new execution's
     * {@code settle()} would find the key already terminal. The key could then
     * expire while its outcome is still servable, reopening the re-execution
     * window the tombstone closes.
     */
    public void markTerminal(String functionName, String idempotencyKey, String expectedExecutionId) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || expectedExecutionId == null) {
            return;
        }
        String composed = compose(functionName, idempotencyKey);
        while (true) {
            StoredKey existing = keys.get(composed);
            if (existing == null || existing.pending() || existing.terminal()
                    || !existing.executionId().equals(expectedExecutionId)) {
                return;
            }
            StoredKey terminal = StoredKey.terminal(existing.executionId());
            if (keys.replace(composed, existing, terminal)) {
                return;
            }
        }
    }

    public int size() {
        cache.cleanUp();
        return keys.size();
    }

    private String compose(String functionName, String key) {
        return functionName + ":" + key;
    }

    private String pendingToken() {
        return "pending:" + Instant.now().toEpochMilli() + ":" + System.nanoTime();
    }

    public record AcquireResult(State state, String executionIdOrToken, boolean terminal) {
        static AcquireResult claimed(String token) {
            return new AcquireResult(State.CLAIMED, token, false);
        }

        static AcquireResult existing(String executionId, boolean terminal) {
            return new AcquireResult(State.EXISTING, executionId, terminal);
        }

        static AcquireResult pending() {
            return new AcquireResult(State.PENDING, null, false);
        }

        static AcquireResult budgetExhausted() {
            return new AcquireResult(State.BUDGET_EXHAUSTED, null, false);
        }

        static AcquireResult missing() {
            return new AcquireResult(State.MISSING, null, false);
        }

        public enum State {
            CLAIMED,
            EXISTING,
            PENDING,
            BUDGET_EXHAUSTED,
            MISSING
        }
    }

    private record StoredKey(String executionId, boolean pending, boolean terminal) {
        static StoredKey pending(String claimToken) {
            return new StoredKey(claimToken, true, false);
        }

        static StoredKey published(String executionId) {
            return new StoredKey(executionId, false, false);
        }

        static StoredKey terminal(String executionId) {
            return new StoredKey(executionId, false, true);
        }
    }
}
