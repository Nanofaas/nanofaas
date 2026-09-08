package it.unimib.datai.nanofaas.controlplane.execution;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

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
 *   <li><b>abandoned</b> - the admission was published and then explicitly given up
 *       ({@link #markReclaimable}), so the execution will never run and the binding
 *       may be re-claimed for a fresh one. Only this state is re-claimable: the mere
 *       absence of a live record or an outcome is never proof that a published binding
 *       was abandoned.</li>
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
 * <p>The number of keys is bounded by {@code maxKeys}. The budget is an <b>atomic
 * reservation</b>, not a size read: a new claim reserves its slot before it creates
 * a binding, so distinct keys cannot all observe spare capacity and then all insert
 * (finding R7). A claim that loses its insert race returns the reservation. Replays
 * of a key already held never touch the budget, so saturation never blocks them,
 * and the cap never evicts a live key to make room for a new one either.
 *
 * <p>Every entry owns exactly one slot, tied to the entry's identity (the composed
 * key), not to any one of its states. A pending→published→terminal transition
 * replaces the value in place and keeps the same slot; the slot returns to the
 * budget exactly once, when the entry finally leaves the cache — explicit remove,
 * abandon, expiry or shutdown. The Caffeine removal listener releases the slot on
 * expiry only (its notifications are otherwise buffered, not synchronous); explicit
 * removals release in code, so a replacement is never a release and no slot is ever
 * returned twice.
 */
@Component
public class IdempotencyStore {
    private final Cache<String, StoredKey> cache;
    private final ConcurrentMap<String, StoredKey> keys;
    private final long maxKeys;

    /**
     * How many slots are reserved right now: entries in the cache plus claims that
     * have reserved but not yet inserted. Incremented by {@link #reserve()} before a
     * new binding is created; decremented by {@link #releaseOne()} when a claim loses
     * its insert race or an explicit remove happens in code, and by the removal
     * listener when an entry expires. This counter — not a {@code size()} read — is
     * what {@link #acquireOrGet} compares against {@code maxKeys}.
     */
    private final AtomicLong occupied = new AtomicLong();

    /**
     * How many new keyed admissions were refused because the budget was exhausted.
     * A plain counter, exposed without labels for execution id or user key.
     */
    private final AtomicLong rejections = new AtomicLong();

    public IdempotencyStore() {
        this(ExecutionStoreProperties.of(null, null, null));
    }

    @Autowired
    public IdempotencyStore(ExecutionStoreProperties executions, MeterRegistry registry) {
        this(executions);
        // Occupancy and refusals are otherwise invisible until the heap says so. A
        // supplier gauge, read at scrape time, nothing on the invocation path. Both
        // read the reservation counter directly: no cleanUp() of the whole cache, and
        // no labels that would leak an execution id or a user key.
        Gauge.builder("idempotency_keys_held", occupied, AtomicLong::doubleValue)
                .description("Number of idempotency-key slots currently reserved")
                .register(registry);
        FunctionCounter.builder("idempotency_key_budget_rejections", rejections, AtomicLong::doubleValue)
                .description("Idempotency-key admissions refused because the key budget was exhausted")
                .register(registry);
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
        // expireAfterUpdate recomputes on the state transition, so markTerminal restarts
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
                // Expired entries are evicted on a maintenance schedule, not only when
                // someone happens to touch them: a slot abandoned by a key nobody replays
                // must return to the budget without waiting for traffic on that same key.
                // The removal listener then releases each evicted entry's slot exactly once.
                .scheduler(Scheduler.systemScheduler())
                .ticker(ticker)
                .removalListener((String key, StoredKey value, RemovalCause cause) -> {
                    // The listener handles ONLY expiry. Its notifications for an explicit
                    // remove or a replace are buffered, not synchronous, so those paths
                    // release in code (abandonClaim, clear) or are not a release at all
                    // (REPLACED: the same entry lives on in another state). Expiry has no
                    // code path of its own, so the listener is its one release; it is
                    // flushed by cleanUp() and by the scheduler's maintenance.
                    if (cause == RemovalCause.EXPIRED && value != null) {
                        occupied.decrementAndGet();
                    }
                })
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

    /**
     * Inserts a published binding directly. A <b>new</b> key reserves its slot first,
     * like every other insertion path, so every entry owns exactly one slot and the
     * removal listener can release it exactly once. Replacing an existing key keeps
     * the slot ({@code REPLACED} is not a release).
     */
    public void put(String functionName, String key, String executionId) {
        String composed = compose(functionName, key);
        while (true) {
            StoredKey existing = keys.get(composed);
            if (existing != null) {
                StoredKey published = StoredKey.published(executionId);
                if (keys.replace(composed, existing, published)) {
                    return;
                }
                continue;
            }
            if (!reserve()) {
                throw new IllegalStateException("Idempotency key budget of " + maxKeys + " is exhausted");
            }
            if (keys.putIfAbsent(composed, StoredKey.published(executionId)) == null) {
                return;
            }
            releaseOne();
        }
    }

    public AcquireResult acquireOrGet(String functionName, String key) {
        String composed = compose(functionName, key);
        while (true) {
            StoredKey existing = keys.get(composed);
            if (existing != null) {
                if (existing.pending()) {
                    return AcquireResult.pending();
                }
                return AcquireResult.existing(existing.executionId(), existing.terminal());
            }
            // Reserve the slot atomically BEFORE creating a new binding. The old
            // check-then-insert of a size() read is gone: N distinct keys cannot all
            // observe spare capacity and then all insert, because at most maxKeys
            // reservations succeed. The reservation is returned if the putIfAbsent below
            // loses a race to a concurrent claimant (whose entry already owns the slot).
            if (!reserve()) {
                rejections.incrementAndGet();
                return AcquireResult.budgetExhausted();
            }
            String token = pendingToken();
            StoredKey pending = StoredKey.pending(token);
            if (keys.putIfAbsent(composed, pending) == null) {
                return AcquireResult.claimed(token);
            }
            releaseOne();
            // Lost the race: an association now exists; the loop re-reads it.
        }
    }

    /**
     * Re-claims a key whose binding was explicitly abandoned after publication: the
     * admission was given up with {@link #markReclaimable}, so the execution it named
     * will never run and a fresh execution may take the key. Only an <b>abandoned</b>
     * binding can be re-claimed. A terminal binding is the tombstone, and a
     * still-{@code published} one is a live (or mid-transition) execution: the absence of
     * a live record or an outcome is not proof that a published claim was abandoned, so
     * re-claiming it would reopen the window — closed by {@link #markTerminal} — in which
     * the same key becomes claimable again and the function runs twice (finding R2).
     *
     * <p>The CAS on {@code keys.replace} is the real guard: if the binding changed
     * between the read and the replace (a transition to terminal included), the
     * replace fails and the loop re-reads. Re-claiming replaces the value in place,
     * so it is the same association and does not consume a second slot.
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
            if (!existing.reclaimable()) {
                // Still published and pointing at this execution id, but never explicitly
                // abandoned. The caller must not mint a new claim on the strength of an
                // absent record/outcome: it re-reads, and the in-flight terminal transition
                // (or expiry) will surface the tombstone.
                return AcquireResult.existing(existing.executionId(), false);
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
            // The removal listener is not synchronous for an explicit remove, so the slot
            // is released here, exactly once, in the same step that removes the entry.
            // (A claim already evicted for expiry was released by the EXPIRED listener.)
            if (keys.remove(composed, existing)) {
                releaseOne();
            }
        }
    }

    /**
     * Marks a published binding as explicitly reclaimable: its admission was given up
     * after publication, so the execution it named will never run and a later replay may
     * re-claim the key for a fresh one. This is the only route into the reclaimable
     * state; {@link #claimIfMatches} re-claims nothing else.
     *
     * <p>Only a <b>published</b> binding can become reclaimable, and only when it still
     * points at {@code expectedExecutionId}. A pending claim is still held by its token,
     * a terminal binding is the tombstone, and a binding replaced by a newer execution
     * has a different id. The CAS on {@code keys.replace} keeps the transition atomic
     * against a concurrent terminal transition or re-claim; it is the same association,
     * so no slot is consumed or released.
     */
    public void markReclaimable(String functionName, String key, String expectedExecutionId) {
        String composed = compose(functionName, key);
        while (true) {
            StoredKey existing = keys.get(composed);
            if (existing == null || existing.pending() || existing.terminal() || existing.reclaimable()
                    || !existing.executionId().equals(expectedExecutionId)) {
                return;
            }
            StoredKey abandoned = StoredKey.abandoned(existing.executionId());
            if (keys.replace(composed, existing, abandoned)) {
                return;
            }
        }
    }

    /**
     * The transition to the terminal binding, the dedup-protection step of the
     * {@link ExecutionLifecycle}'s terminal transition (and of the admission path's
     * publish when completion raced ahead of publication). Terminal retention starts
     * here, not at publication, and it closes the window in which the key would be
     * claimable again while its outcome is still (or has just been) servable.
     *
     * <p>Idempotent: on a key that is absent, pending, reclaimable or already terminal
     * it does nothing. A reclaimable (abandoned) binding is not tombstoned here - its
     * execution was given up, and its settle, if any, must not start terminal retention.
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
            if (existing == null || existing.pending() || existing.terminal() || existing.reclaimable()
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

    /** How many slots are reserved right now. Package-private: for the tests in this package. */
    long occupied() {
        return occupied.get();
    }

    /** How many new keyed admissions have been refused. Package-private: for the tests in this package. */
    long rejections() {
        return rejections.get();
    }

    /**
     * Empties the store and returns every slot to the budget. This is the shutdown /
     * drain hook: after it, nothing is occupied and new claims are admitted again.
     * The removal listener does not fire synchronously for {@code invalidateAll()},
     * so the counter is reset here, in the same step that discards the entries.
     */
    @PreDestroy
    public void clear() {
        cache.invalidateAll();
        occupied.set(0);
    }

    private String compose(String functionName, String key) {
        return functionName + ":" + key;
    }

    private String pendingToken() {
        return "pending:" + Instant.now().toEpochMilli() + ":" + System.nanoTime();
    }

    /** Atomically reserves one slot if any is left; false once the budget is exhausted. */
    private boolean reserve() {
        while (true) {
            long current = occupied.get();
            if (current >= maxKeys) {
                return false;
            }
            if (occupied.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void releaseOne() {
        occupied.decrementAndGet();
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

    private record StoredKey(String executionId, boolean pending, boolean terminal, boolean reclaimable) {
        static StoredKey pending(String claimToken) {
            return new StoredKey(claimToken, true, false, false);
        }

        static StoredKey published(String executionId) {
            return new StoredKey(executionId, false, false, false);
        }

        static StoredKey abandoned(String executionId) {
            return new StoredKey(executionId, false, false, true);
        }

        static StoredKey terminal(String executionId) {
            return new StoredKey(executionId, false, true, false);
        }
    }
}
