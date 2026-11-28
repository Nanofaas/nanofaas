package it.unimib.datai.nanofaas.controlplane.execution;

import com.github.benmanes.caffeine.cache.Scheduler;
import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.InvocationCapacity;
import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore.AcquireResult;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Builds the execution and key stores through their one production constructor, for tests, and
 * observes them only through what production exposes: their meters and their per-id API.
 *
 * <p>A store on the system ticker runs on real time, exactly as in production. A store on any other
 * ticker runs its maintenance on the calling thread and its timers on a {@link ManualScheduler}: a
 * test that moves its fake clock calls {@link #fire} to let the timers that wall time would have
 * fired run, and every effect of that maintenance has happened when it returns.
 */
public final class ExecutionFixtures {
    private ExecutionFixtures() {
    }

    /** What a store was built with: the meters to read and, on a fake clock, the timers to fire. */
    private record Harness(MeterRegistry registry, ManualScheduler timers, IdempotencyStore keys) {
    }

    private static final Map<Object, Harness> HARNESSES = Collections.synchronizedMap(new WeakHashMap<>());

    // ---- Properties

    public static ExecutionStoreProperties defaults() {
        return properties(null, null, null);
    }

    /** Nulls take the defaults, as the bound configuration does. */
    public static ExecutionStoreProperties properties(Duration ttl, Duration maxLifetime, Duration syncTtl) {
        return new ExecutionStoreProperties(ttl, maxLifetime, syncTtl, 0, 0, 0);
    }

    /** The outcome cap alone; the byte budget derives from it. */
    public static ExecutionStoreProperties properties(Duration ttl, Duration maxLifetime, Duration syncTtl,
                                                      long maxOutcomes) {
        return new ExecutionStoreProperties(ttl, maxLifetime, syncTtl, maxOutcomes, 0, 0);
    }

    // ---- Key stores

    public static IdempotencyStore keys() {
        return keys(defaults(), Ticker.systemTicker());
    }

    /** One lifetime for both the live and the terminal phase. */
    public static IdempotencyStore keys(Duration ttl) {
        return keys(ttl, Ticker.systemTicker());
    }

    public static IdempotencyStore keys(Duration ttl, Ticker ticker) {
        return keys(properties(ttl, ttl, ttl), ticker);
    }

    public static IdempotencyStore keys(ExecutionStoreProperties properties, Ticker ticker) {
        return keys(properties, new SimpleMeterRegistry(), ticker);
    }

    public static IdempotencyStore keys(ExecutionStoreProperties properties, MeterRegistry registry) {
        return keys(properties, registry, Ticker.systemTicker());
    }

    public static IdempotencyStore keys(ExecutionStoreProperties properties, MeterRegistry registry, Ticker ticker) {
        ManualScheduler timers = realTime(ticker) ? null : new ManualScheduler();
        IdempotencyStore keys = new IdempotencyStore(properties, registry, scheduler(timers), ticker, executor(timers));
        HARNESSES.put(keys, new Harness(registry, timers, null));
        return keys;
    }

    // ---- Execution stores

    public static ExecutionStore store() {
        return store(defaults());
    }

    public static ExecutionStore store(ExecutionStoreProperties properties) {
        return store(properties, Ticker.systemTicker());
    }

    public static ExecutionStore store(ExecutionStoreProperties properties, Ticker ticker) {
        return store(properties, ticker, keys(properties, ticker));
    }

    public static ExecutionStore store(ExecutionStoreProperties properties, MeterRegistry registry) {
        return store(properties, registry, Ticker.systemTicker(), keys(properties, Ticker.systemTicker()));
    }

    /** A store on the default properties and the system ticker that settles keys into {@code keys}. */
    public static ExecutionStore store(IdempotencyStore keys) {
        return store(defaults(), Ticker.systemTicker(), keys);
    }

    public static ExecutionStore store(ExecutionStoreProperties properties, IdempotencyStore keys) {
        return store(properties, Ticker.systemTicker(), keys);
    }

    public static ExecutionStore store(ExecutionStoreProperties properties, Ticker ticker, IdempotencyStore keys) {
        return store(properties, new SimpleMeterRegistry(), ticker, keys);
    }

    public static ExecutionStore store(ExecutionStoreProperties properties, MeterRegistry registry, Ticker ticker,
                                       IdempotencyStore keys) {
        ManualScheduler timers = realTime(ticker) ? null : new ManualScheduler();
        ExecutionStore store = new ExecutionStore(properties, registry, scheduler(timers), ticker, executor(timers), keys);
        HARNESSES.put(store, new Harness(registry, timers, keys));
        return store;
    }

    /** Makes a store built elsewhere (a test double's base) known here, with its meters and keys. */
    static void track(ExecutionStore store, MeterRegistry registry, IdempotencyStore keys) {
        HARNESSES.put(store, new Harness(registry, null, keys));
    }

    /**
     * The key store a fixture-built execution store settles keys into; null for a store this class
     * did not build.
     */
    public static IdempotencyStore keysOf(ExecutionStore store) {
        Harness harness = HARNESSES.get(store);
        return harness == null ? null : harness.keys();
    }

    /**
     * Runs the timers wall time would have fired by now, for a store on a fake ticker: after the
     * clock moved, this is what evicts what expired and notifies its listeners.
     */
    public static void fire(Object store) {
        ManualScheduler timers = harness(store).timers();
        if (timers == null) {
            throw new IllegalStateException("this store runs on real time: wait for it instead");
        }
        timers.fire();
    }

    // ---- Observation through the meters production exposes

    /** {@code idempotency_keys_held}: the key slots reserved right now, as the meter reads it. */
    public static long keysHeld(IdempotencyStore keys) {
        return (long) harness(keys).registry().get("idempotency_keys_held").gauge().value();
    }

    /** {@code idempotency_key_budget_rejections}: new keyed admissions refused by the budget. */
    public static long keyRejections(IdempotencyStore keys) {
        return (long) harness(keys).registry().get("idempotency_key_budget_rejections").functionCounter().count();
    }

    /**
     * The key slots held once expiry has caught up: on a fake clock, the timers wall time would
     * have fired run first, so a key whose time is up no longer counts.
     */
    public static long keyCount(IdempotencyStore keys) {
        catchUp(keys);
        return keysHeld(keys);
    }

    /** {@code execution_store_size}: the outcomes retained, once expiry has caught up on a fake clock. */
    public static long outcomesHeld(ExecutionStore store) {
        catchUp(store);
        return (long) harness(store).registry().get("execution_store_size").gauge().value();
    }

    /** {@code execution_in_flight_records}: the live records, once expiry has caught up on a fake clock. */
    public static long inFlight(ExecutionStore store) {
        catchUp(store);
        return (long) harness(store).registry().get("execution_in_flight_records").gauge().value();
    }

    // ---- Keys through the store's own API

    /** Binds a fresh key to {@code executionId}, as an admission does: claim, then publish. */
    public static void bind(IdempotencyStore keys, String functionName, String key, String executionId) {
        AcquireResult claim = keys.acquireOrGet(functionName, key);
        if (claim.state() != AcquireResult.State.CLAIMED) {
            throw new IllegalStateException("key " + key + " is not free: " + claim);
        }
        keys.publishClaim(functionName, key, claim.executionIdOrToken(), executionId);
    }

    /**
     * The execution a key is bound to, empty when it is absent or still pending. Read through the
     * store's API: an absent key is claimed and released again, leaving the store as it was.
     */
    public static Optional<String> boundExecutionId(IdempotencyStore keys, String functionName, String key) {
        AcquireResult result = keys.acquireOrGet(functionName, key);
        return switch (result.state()) {
            case EXISTING -> Optional.of(result.executionIdOrToken());
            case CLAIMED -> {
                keys.abandonClaim(functionName, key, result.executionIdOrToken());
                yield Optional.empty();
            }
            case PENDING, BUDGET_EXHAUSTED, MISSING -> Optional.empty();
        };
    }

    // ---- Records

    /**
     * An execution admitted on a capacity of its own, for a test where the record meets no other
     * capacity: one that dispatches or fences on generations passes the coordinator's registry.
     */
    public static ExecutionRecord record(String executionId, InvocationTask task) {
        return record(executionId, task, TimeSource.system());
    }

    public static ExecutionRecord record(String executionId, InvocationTask task, TimeSource timeSource) {
        return record(executionId, task, timeSource, new FunctionCapacityRegistry());
    }

    public static ExecutionRecord record(String executionId, InvocationTask task, FunctionCapacityRegistry generations) {
        return record(executionId, task, TimeSource.system(), generations);
    }

    public static ExecutionRecord record(String executionId, InvocationTask task, TimeSource timeSource,
                                         FunctionCapacityRegistry generations) {
        return new ExecutionRecord(executionId, task, timeSource, admit(executionId, task, generations));
    }

    /**
     * Admits the execution as the factory does, under the function's active generation in
     * {@code generations} (registered there first if it has none), and publishes the admission.
     * The quotas are unbounded: a test about a limit goes through the factory.
     */
    static ExecutionInputResources admit(String executionId, InvocationTask task, FunctionCapacityRegistry generations) {
        if (generations.activeGeneration(task.functionName()) == null) {
            Integer concurrency = task.functionSpec() == null ? null : task.functionSpec().concurrency();
            generations.register(task.functionName(), concurrency == null ? 1 : concurrency);
        }
        InvocationCapacity.Admission admission = new InvocationCapacity(generations,
                Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE)
                .reserve(task.functionName(), executionId, 1);
        admission.publish();
        return new ExecutionInputResources(admission);
    }

    /** Times the record out, as a queue-wait deadline does, without settling it. */
    public static void timeOut(ExecutionRecord executionRecord) {
        executionRecord.markTimeout(null);
    }

    // ----

    // The system ticker is a singleton: identity is the question.
    @SuppressWarnings("ReferenceEquality")
    private static boolean realTime(Ticker ticker) {
        return ticker == Ticker.systemTicker();
    }

    private static void catchUp(Object store) {
        ManualScheduler timers = harness(store).timers();
        if (timers != null) {
            timers.fire();
        }
    }

    private static Scheduler scheduler(ManualScheduler timers) {
        return timers == null ? Scheduler.systemScheduler() : timers;
    }

    private static Executor executor(ManualScheduler timers) {
        return timers == null ? ForkJoinPool.commonPool() : Runnable::run;
    }

    private static Harness harness(Object store) {
        Harness harness = HARNESSES.get(store);
        if (harness == null) {
            throw new IllegalStateException("not built by ExecutionFixtures: " + store);
        }
        return harness;
    }
}
