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
 * The binding between an idempotency key and the execution that answers for it.
 *
 * <p>A key lives in three states, each with its own expiry:
 * <ul>
 *   <li><b>pending</b> - a request has claimed it and not yet published it.
 *       Expires after {@code maxLifetime}: the cap on an abandoned claim, no
 *       different from the cap on a stuck execution.</li>
 *   <li><b>published</b> - bound to an execution that is still live. Expires after
 *       {@code maxLifetime}, the same horizon as {@code ExecutionStore.inFlight}:
 *       key and execution die together when the dispatch never returns.</li>
 *   <li><b>terminal</b> - the execution has archived ({@link #markTerminal}).
 *       Terminal retention starts here: {@code ttl} from completion, not from
 *       publication. This is the state that acts as a tombstone when the outcome
 *       is evicted for capacity: the binding stays, the payload does not.</li>
 * </ul>
 *
 * <p>The per-state expiry is derived from the execution store's properties and
 * never configured on its own: a key is useful only while the answer it names
 * exists, and a key that expires before its outcome silently runs the function
 * twice — exactly the failure the key exists to prevent.
 *
 * <p>The number of keys is bounded by {@code maxKeys}. With the budget exhausted a
 * <b>new</b> keyed admission is refused ({@code acquireOrGet} does not claim), but
 * replays of keys already held stay servable: the cap never evicts a live key to
 * make room for a new one, or a later byte budget could silently evict the
 * deduplication guarantee itself.
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
        // Per state, not one duration: a published key lives as long as the execution
        // can (maxLifetime), a terminal one as long as the outcome stays readable (ttl).
        // expireAfterUpdate ricomputa al passaggio di stato, cosi' markTerminal riparte
        // the clock from completion instead of inheriting the rest of the live phase.
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
                // The budget is checked BEFORE claiming, and never by evicting a live key:
                // with the budget exhausted a new keyed admission is refused, while replays
                // of keys already held keep finding their outcome.
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
     * Re-claims a published key whose binding points at an execution that has
     * vanished without ever concluding (an admission abandoned after publication, a
     * dispatch that never started). Only a <b>published</b> binding can be
     * re-claimed: a terminal binding is the tombstone, and re-claiming it would
     * reopen the window — closed by {@link #markTerminal} — in which the same key
     * becomes claimable again and the function runs twice.
     *
     * <p>The CAS on {@code keys.replace} is the real guard: if the binding changed
     * between the read and the replace (a transition to terminal included), the
     * replace fails and the loop re-reads.
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
     * The transition to the terminal binding, invoked by the execution store when a
     * record archives. Terminal retention starts here, not at publication, and it
     * closes the window in which the key would be claimable again while its outcome
     * is still (or has just been) servable.
     *
     * <p>Idempotent: on a key that is absent, pending or already terminal it does nothing.
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
