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
public class ExecutionStore {
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
    private final Cache<String, Outcome> outcomes;

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
     * Who is told when a terminal record is archived with {@link #settle} - the instant
     * from which the terminal retention of the idempotency key starts. Nobody by
     * default. {@code InvocationExecutionFactory} registers {@code IdempotencyStore}
     * here, because the key's binding must move from the "live" state to the "terminal"
     * one exactly when the outcome leaves the living, with no window in which the same
     * key becomes claimable again. {@code ExecutionCompletionHandler} registers the
     * end-to-end conclusion here, because archiving is the only event common to EVERY
     * terminal policy.
     */
    private final List<Consumer<ExecutionRecord>> terminalListeners = new CopyOnWriteArrayList<>();

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
                // is estimated here, once; no later access recomputes it. At the default
                // value the budget is worth maxOutcomes compact outcomes, so for those
                // nothing changes.
                .maximumWeight(properties.maxOutcomeBytes())
                .weigher((String id, Outcome outcome) -> OutcomeWeigher.weigh(outcome))
                .expireAfter(Expiry.creating((String id, Outcome outcome) ->
                        outcome.readable() ? properties.ttl() : properties.syncTtl()))
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
        return outcomes.getIfPresent(executionId);
    }

    /**
     * The terminal transition: this is where the apparatus of the living dies, not
     * 152 seconds later.
     *
     * <p>Idempotent, because there are nine call sites spread over three Gradle
     * modules, and some of them overlap (a dispatch that completes after the sync path
     * has already timed out). Calling it on a non-terminal record does nothing: the
     * record is still alive and {@code inFlight} must keep finding it.
     */
    public void settle(ExecutionRecord executionRecord) {
        if (!executionRecord.isTerminal()) {
            return;
        }
        String executionId = executionRecord.executionId();
        outcomes.put(executionId, executionRecord.toOutcome());
        inFlight.invalidate(executionId);
        // After the archiving, and not before: only now is the outcome servable, and it
        // is from here that the key's terminal retention starts. Reversing the order
        // would reopen the window in which the key is already terminal while the outcome
        // is not yet visible to replays.
        notifyAll(terminalListeners, executionRecord, "Terminal");
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
