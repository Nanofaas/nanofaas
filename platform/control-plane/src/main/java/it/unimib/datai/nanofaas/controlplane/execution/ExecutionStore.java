package it.unimib.datai.nanofaas.controlplane.execution;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import it.unimib.datai.nanofaas.common.model.ErrorInfo;
import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.QueueLifecycle;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * What is executing, and what is left of it afterwards.
 *
 * <p>These used to be one structure, and the two lives do not resemble each other.
 * On 2026-08-26, in a run at 1,093 admissions per second, {@code function_inFlight}
 * peaked at <b>2</b> while the store held <b>165,786</b>: 99.999% of what it kept
 * was posthumous, and it dragged the apparatus of the living along with it - the
 * future, the task, the request, the set of released attempts. Thirty-five objects
 * per record, five million eight hundred thousand in total, every one of which a
 * full collection had to walk.
 *
 * <p>From here on they are two. {@link #inFlight} holds the mutable records for as
 * long as they are needed; {@link #outcomes} holds {@link Outcome}, flat and
 * immutable, for whoever can still ask for them.
 *
 * <p>Both are Caffeine, like {@link IdempotencyStore} one file over. In place of the
 * janitor that walked the whole map every minute - 12.4 ms over 166,000 records, and
 * every old-generation object touched for nothing - eviction is amortised over the
 * writes. And {@code maximumWeight} is the ceiling in space that was always missing
 * here: retention used to be declared in time and unbounded in number, so memory grew
 * with the arrival rate. On 2026-08-23 that meant 1.05 GB, 50.6% of the time in GC,
 * and a liveness probe missed three times in a row.
 */
@Component
public class ExecutionStore implements QueueLifecycle {
    private static final Logger log = LoggerFactory.getLogger(ExecutionStore.class);

    /**
     * What is still executing. Expires on its own after {@code maxLifetime}: this is
     * what replaces the like-named branch of the old janitor for stuck executions
     * (lost dispatch, callback that never arrived), and it is also the safety net if
     * a {@link #settle} were forgotten on some new path - the record expires instead
     * of staying forever.
     */
    private final Cache<String, ExecutionRecord> inFlight;

    /** What is left of it. Bounded in space, with an expiry decided per entry. */
    private final Cache<String, OutcomeWeigher.FreezeResult> outcomes;

    /**
     * The byte budget the {@link #outcomes} cache is capped at. Kept alongside the cache
     * because {@link #settle} must compare an outcome's conservative weight against it
     * before inserting: an outcome whose weight alone exceeds the budget is declined
     * rather than admitted only to be evicted an instant later.
     */
    private final long maximumOutcomeBytes;

    /**
     * Who is told when {@link #inFlight} evicts a record on its own, because
     * {@code maxLifetime} elapsed - not because someone archived it with
     * {@link #settle}.
     *
     * <p>Nobody by default: a store used without registering a listener behaves as
     * before, silent eviction. {@code ExecutionCompletionHandler} registers here in
     * production, because it is the one that knows how to close an abandoned dispatch
     * - complete the shared future, release the slot, archive the outcome - not the
     * store, which knows nothing of slots and shared futures beyond the record itself.
     */
    private final List<Consumer<ExecutionRecord>> expiryListeners = new CopyOnWriteArrayList<>();

    /**
     * Who is told after a terminal record has archived - the observers of the terminal
     * moment, not its owners. Best-effort by design: the deduplication protection (the
     * key's move to its terminal binding) is {@link ExecutionLifecycle}'s first-class step,
     * before archiving, so no listener in this chain can reopen the re-execution window.
     * {@code ExecutionCompletionHandler} registers the end-to-end conclusion here, because
     * archiving is the only event common to EVERY terminal policy.
     */
    private final List<Consumer<ExecutionRecord>> terminalListeners = new CopyOnWriteArrayList<>();

    /**
     * The owner of the terminal transition, attached by {@link ExecutionLifecycle} when the
     * store and the idempotency store are wired together (in production, by
     * {@code InvocationExecutionFactory}). {@link #settle} fails fast when a <b>keyed</b>
     * record is settled without one: that transition would otherwise silently leave the key
     * binding reclaimable, which is exactly the re-execution window the owner closes.
     */
    private ExecutionLifecycle lifecycle;

    public ExecutionStore() {
        this(ExecutionStoreProperties.of(null, null, null));
    }

    // @Autowired is required: with two constructors Spring would pick the no-argument
    // one and silently ignore the configured properties.
    @Autowired
    public ExecutionStore(ExecutionStoreProperties properties, MeterRegistry registry) {
        this(properties);
        // How much the platform is remembering, and how much it is actually executing.
        // It was the distance between these two numbers that exposed the problem:
        // without the second, the first could still be mistaken for work in progress.
        Gauge.builder("execution_store_size", outcomes::estimatedSize).register(registry);
        Gauge.builder("execution_in_flight_records", inFlight::estimatedSize).register(registry);
    }

    ExecutionStore(ExecutionStoreProperties properties) {
        this(properties, Ticker.systemTicker());
    }

    /** Eviction tests move the clock instead of sleeping; public for the tests in the service package. */
    public ExecutionStore(ExecutionStoreProperties properties, Ticker ticker) {
        this.maximumOutcomeBytes = properties.maxOutcomeBytes();
        this.inFlight = Caffeine.newBuilder()
                .expireAfterWrite(properties.maxLifetime())
                .ticker(ticker)
                // Without this, Caffeine checks expiry only when something touches the
                // cache - a get, a put, an explicit cleanUp(). A record stuck on a lost
                // dispatch, with nobody ever reading it again (an ASYNC caller that stops
                // polling, or no caller at all), stayed expired but alive indefinitely:
                // the concurrency slot it held never returned to the budget. The scheduler
                // plans the eviction on wall-clock time, independent of any later traffic
                // on the cache.
                .scheduler(Scheduler.systemScheduler())
                .removalListener((String executionId, ExecutionRecord executionRecord, RemovalCause cause) -> {
                    // EXPLICIT is settle()/remove() - already handled by their callers.
                    // REPLACED does not apply: no path calls put() twice on the same id.
                    // Only EXPIRED is the eviction nobody decided.
                    if (cause == RemovalCause.EXPIRED && executionRecord != null) {
                        notifyExpiry(executionRecord);
                    }
                })
                .build();
        this.outcomes = Caffeine.newBuilder()
                // A ceiling in BYTES, not in count: a count assumes every outcome weighs
                // the same, and a readable outcome retains the caller's payload. The weight
                // is estimated once, at insertion; no later access recomputes it. The
                // weigher is conservative: an outcome whose size cannot be bounded is
                // declined by settle() before it ever reaches this cache, so the weigher
                // only ever prices outcomes the store already decided to retain.
                .maximumWeight(properties.maxOutcomeBytes())
                .weigher((String id, OutcomeWeigher.FreezeResult frozen) ->
                        OutcomeWeigher.weightAsInt(frozen.weight()))
                .expireAfter(Expiry.creating((String id, OutcomeWeigher.FreezeResult frozen) ->
                        frozen.outcome().readable() ? properties.ttl() : properties.syncTtl()))
                .ticker(ticker)
                .build();
    }

    /**
     * Registers who closes an abandoned dispatch when {@code maxLifetime} elapses on
     * its own. Additive, like {@link #onTerminal}: every collaborator interested in
     * administrative expiry adds itself, and none can silence another.
     */
    public void onAdministrativeExpiry(Consumer<ExecutionRecord> listener) {
        expiryListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Registers a listener to be told when an execution archives. Additive: every
     * collaborator interested in the terminal moment adds itself, and none can
     * silence another. It used to be a single last-wins slot, where a second
     * constructor was enough to make the key transition disappear in silence.
     */
    public void onTerminal(Consumer<ExecutionRecord> listener) {
        terminalListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** How many outcomes are archived right now. */
    public int size() {
        outcomes.cleanUp();
        return (int) outcomes.estimatedSize();
    }

    /** How many records are still executing. */
    public int inFlightCount() {
        inFlight.cleanUp();
        return (int) inFlight.estimatedSize();
    }

    /**
     * Snapshots the live executions owned by one function. Lifecycle consumers use this only
     * on function removal, never on invocation admission; the returned ids let them retain a
     * removal fence only while concrete executions can still deliver stale work.
     */
    public Set<String> inFlightExecutionIds(String functionName) {
        return inFlight.asMap().values().stream()
                .filter(record -> functionName.equals(record.task().functionName()))
                .map(ExecutionRecord::executionId)
                .collect(Collectors.toUnmodifiableSet());
    }

    public void put(ExecutionRecord executionRecord) {
        inFlight.put(executionRecord.executionId(), executionRecord);
    }

    public Optional<ExecutionRecord> get(String executionId) {
        return Optional.ofNullable(inFlight.getIfPresent(executionId));
    }

    /** A read on the hot path, without allocating an Optional. */
    @Nullable
    public ExecutionRecord getOrNull(String executionId) {
        return inFlight.getIfPresent(executionId);
    }

    /** The archived outcome, if the execution is over and someone can still read it. */
    @Nullable
    public Outcome outcomeOf(String executionId) {
        OutcomeWeigher.FreezeResult frozen = outcomes.getIfPresent(executionId);
        return frozen == null ? null : frozen.outcome();
    }

    @Override
    public void expired(InvocationTask task) {
        concludeQueued(task, null);
    }

    @Override
    public void removed(InvocationTask task) {
        concludeQueued(task, new ErrorInfo(
                "FUNCTION_REMOVED", "Function '%s' was removed before queued execution could run"
                        .formatted(task.functionName())));
    }

    @Override
    public void rejected(InvocationTask task, Throwable failure) {
        // Throwable.getMessage() is routinely null (NullPointerException and most
        // transport wrappers); a rejected outcome must still say something.
        String message = failure.getMessage() != null ? failure.getMessage() : failure.toString();
        concludeQueued(task, new ErrorInfo("DISPATCH_REJECTED", message));
    }

    private void concludeQueued(InvocationTask task,
                                ErrorInfo error) {
        task.releaseQueuedInput();
        ExecutionRecord record = getOrNull(task.executionId());
        if (record == null) return;
        synchronized (record) {
            if (record.task().attempt() != task.attempt()) return;
            if (!record.isTerminal()) {
                if (error == null) record.markTimeout(new ErrorInfo(
                        "QUEUE_TIMEOUT", "Queue wait exceeded"));
                else record.markError(error);
            }
        }
        // The attached lifecycle protects the key, publishes the canonical answer,
        // archives, releases logical resources and notifies observers exactly once.
        settle(record);
    }

    @Override
    public void onExecutionGone(java.util.function.BiConsumer<String, String> listener) {
        java.util.function.Consumer<ExecutionRecord> notification =
                record -> listener.accept(record.task().functionName(), record.executionId());
        onTerminal(notification);
        onAdministrativeExpiry(notification);
    }

    /**
     * The terminal transition called by core completion and queue event adapters. It delegates to the attached {@link ExecutionLifecycle}, which
     * owns the key protection as well as the archive and removal.
     *
     * <p>There is no silent downgrade for a <b>keyed</b> record: settling one without an
     * owner would leave its key binding reclaimable (dropping {@code markTerminal}), so it
     * fails fast instead of losing the dedup guarantee. A keyless record has nothing to
     * protect, so its archive-and-remove half is the whole transition and needs no owner.
     * The record must be terminal; a live record is ignored.
     */
    public void settle(ExecutionRecord executionRecord) {
        ExecutionLifecycle owner = lifecycle;
        if (owner != null) {
            owner.settle(executionRecord);
            return;
        }
        String key = executionRecord.idempotencyKey();
        if (key != null && !key.isBlank()) {
            throw new IllegalStateException(
                    "No ExecutionLifecycle attached to ExecutionStore: a keyed terminal "
                    + "transition cannot protect its idempotency key. InvocationExecutionFactory "
                    + "attaches the owner in production.");
        }
        if (!executionRecord.beginSettlement()) return;
        executionRecord.publishTerminal();
        archiveAndRemove(executionRecord);
        notifyTerminal(executionRecord);
    }

    /**
     * The store half of the terminal transition: archive the outcome (when its
     * conservative weight fits the budget) and invalidate the live record. Retention of
     * the payload is decided here and separated from the caller's response; an outcome
     * whose size cannot be bounded - or whose weight alone exceeds the whole budget - is
     * simply not retained. The tombstone is the owner's business, not this half's.
     */
    void archiveAndRemove(ExecutionRecord executionRecord) {
        if (!executionRecord.isTerminal()) {
            return;
        }
        String executionId = executionRecord.executionId();
        try {
            OutcomeWeigher.FreezeResult frozen = OutcomeWeigher.freeze(executionId, executionRecord.toOutcome(),
                    Math.min(maximumOutcomeBytes, Integer.MAX_VALUE));
            if (frozen != null && frozen.weight() <= maximumOutcomeBytes) {
                outcomes.put(executionId, frozen);
            }
        } catch (RuntimeException failure) {
            // User containers can throw during traversal (including concurrent mutation).
            // Declining retention must not interrupt terminal cleanup or dedup protection.
            log.warn("Cannot retain outcome for execution {}", executionId, failure);
        } finally {
            inFlight.invalidate(executionId);
        }
    }

    /** Notifies the terminal observers, best-effort, after the invariants already hold. */
    void notifyTerminal(ExecutionRecord executionRecord) {
        notifyAll(terminalListeners, executionRecord, "Terminal");
    }

    /** Called by {@link ExecutionLifecycle} to become the owner of the terminal transition. */
    void attachLifecycle(ExecutionLifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    private void notifyExpiry(ExecutionRecord executionRecord) {
        notifyAll(expiryListeners, executionRecord, "Administrative-expiry");
    }

    /**
     * The listeners are independent collaborators registered by different beans, in an
     * order Spring decides. If one fails, the others must still run: losing the terminal
     * transition of the idempotency key because a metric threw would silently reopen the
     * re-execution window.
     */
    private static void notifyAll(List<Consumer<ExecutionRecord>> listeners,
                                  ExecutionRecord executionRecord, String kind) {
        for (Consumer<ExecutionRecord> listener : listeners) {
            try {
                listener.accept(executionRecord);
            } catch (RuntimeException ex) {
                log.warn("{} listener failed for execution {}", kind, executionRecord.executionId(), ex);
            }
        }
    }

    public void remove(String executionId) {
        inFlight.invalidate(executionId);
        outcomes.invalidate(executionId);
    }

    /** Deterministic tests force maintenance instead of waiting for it. */
    void cleanUp() {
        inFlight.cleanUp();
        outcomes.cleanUp();
    }
}
