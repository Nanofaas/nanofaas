package it.unimib.datai.nanofaas.controlplane.deployment;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared, cached view of a managed deployment's replica status, so the autoscaler, the concurrency
 * governor and any other periodic consumer read the provider at most once per TTL window instead of
 * once per consumer per cycle.
 *
 * <p>Two paths, deliberately different in what they may cost the caller:</p>
 * <ul>
 *   <li>{@link #observe} is the <em>periodic</em> path and never blocks on the provider, not even on
 *       a cold start: it answers immediately with a {@link ReplicaObservation} — FRESH from cache,
 *       STALE (last-known-good, refresh scheduled) or UNAVAILABLE — and schedules the refresh in the
 *       background. One slow function can therefore never stall the loop's other functions, and a
 *       read error can never reach a consumer as {@code readyReplicas = 0} (invariant I9).</li>
 *   <li>{@link #refresh} is the <em>freshness-required</em> path for wake-up and lifecycle: it
 *       always reaches the provider and waits, but only up to
 *       {@link RefreshLimits#freshnessDeadline()}. Past that the caller's thread is released with a
 *       {@link ReplicaStatusUnavailableException}, so a hung provider cannot occupy wake-up workers
 *       indefinitely (invariant I8).</li>
 * </ul>
 *
 * <p>Other semantics that keep the consumers safe:</p>
 * <ul>
 *   <li>At most one refresh per function/generation is outstanding (single-flight). Refreshes run on
 *       bounded executors with bounded queues: past the bound a submission is <em>rejected</em> and
 *       the refresh fails, which the stale-while-revalidate path already tolerates. Running the
 *       provider call on the caller's thread as a fallback ({@code CallerRunsPolicy}) is explicitly
 *       not an option — that is exactly the blocking this class exists to prevent.</li>
 *   <li>An expired entry whose refresh fails keeps its last-known-good value until
 *       {@link #DEFAULT_MAX_STALE}; past that the observation becomes UNAVAILABLE rather than an
 *       invented zero.</li>
 *   <li>{@link #invalidate} bumps the function's generation, cancels the owned task and drops the
 *       entry; a refresh already in flight for the previous generation is discarded instead of
 *       landing, so a stale response can never overwrite a newer target, answer for a
 *       re-registered function, or reinsert a removed entry.</li>
 * </ul>
 */
public final class ReplicaStatusSnapshot implements AutoCloseable, MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(ReplicaStatusSnapshot.class);

    /** Default freshness window: one autoscaler/governor poll interval. */
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(5);

    /**
     * How far past the TTL a last-known-good value may still be served while refreshes keep
     * failing. Past it the unavailability is the answer.
     *
     * <p>Deliberately shorter than the autoscaler's progress window: a frozen value replayed for a
     * whole window is indistinguishable from a rollout making no progress, and would be reconciled
     * down as a stuck one. Bounding the age below that window means a dead read path can never
     * masquerade as a stuck rollout — it makes the scaler skip the cycle instead.
     */
    public static final Duration DEFAULT_MAX_STALE = Duration.ofSeconds(15);

    /** Which of the two refresh paths a task belongs to; also the only metric tag value set. */
    public enum RefreshPath {
        PERIODIC("periodic"),
        FRESHNESS("freshness");

        private final String tag;

        RefreshPath(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    /**
     * Bounds for the two refresh executors, validated on construction.
     *
     * <p>Queue capacities are sized against what can legitimately be outstanding: single-flight
     * means at most one pending refresh per function, so a capacity of {@code 64} covers far more
     * managed functions than a single control-plane pod is expected to host while still bounding
     * the work (and the retained {@link ManagedDeploymentTarget}s) if the provider stops answering.
     * Past the bound submissions are rejected rather than queued, which degrades a reader to STALE
     * or UNAVAILABLE — never to a blocked loop thread.
     *
     * <p>The freshness pool is separate from the periodic one on purpose: wake-up reads are
     * latency-critical and must not queue behind slow periodic refreshes.
     *
     * @param periodicConcurrency    slow provider refreshes allowed to run at once for {@link #observe}
     * @param periodicQueueCapacity  pending periodic refreshes before submissions are rejected
     * @param freshnessConcurrency   provider reads allowed to run at once for {@link #refresh}
     * @param freshnessQueueCapacity pending forced-fresh reads before submissions are rejected
     * @param freshnessDeadline      how long {@link #refresh} waits before releasing its caller
     */
    public record RefreshLimits(int periodicConcurrency,
                                int periodicQueueCapacity,
                                int freshnessConcurrency,
                                int freshnessQueueCapacity,
                                Duration freshnessDeadline) {

        /**
         * Production defaults. The 5s freshness deadline sits well inside the wake-up gate's own
         * 30s budget, so a hung provider frees the wake-up worker long before the gate gives up.
         */
        public static final RefreshLimits DEFAULTS =
                new RefreshLimits(2, 64, 4, 32, Duration.ofSeconds(5));

        public RefreshLimits {
            requirePositive(periodicConcurrency, "periodicConcurrency");
            requirePositive(periodicQueueCapacity, "periodicQueueCapacity");
            requirePositive(freshnessConcurrency, "freshnessConcurrency");
            requirePositive(freshnessQueueCapacity, "freshnessQueueCapacity");
            if (freshnessDeadline == null || freshnessDeadline.isZero() || freshnessDeadline.isNegative()) {
                throw new IllegalArgumentException("freshnessDeadline must be positive");
            }
        }

        private static void requirePositive(int value, String name) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        }
    }

    /**
     * Raised when there is no replica reading to hand back: the freshness deadline elapsed, the
     * refresh was rejected by a saturated executor, or a newer generation superseded the fetch.
     * Extends {@link IllegalStateException} so existing callers that already treat a failed
     * forced-fresh read as an illegal state keep working unchanged.
     */
    public static final class ReplicaStatusUnavailableException extends IllegalStateException {
        public ReplicaStatusUnavailableException(String message) {
            super(message);
        }
    }

    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong generationSequence = new AtomicLong();
    private final InstantSource clock;
    private final Duration ttl;
    private final Duration maxStale;
    private final Duration freshnessDeadline;
    private final Executor periodicExecutor;
    private final Executor freshnessExecutor;
    private final List<ExecutorService> ownedExecutors;
    private final PathStats periodicStats = new PathStats();
    private final PathStats freshnessStats = new PathStats();

    /**
     * Test/embedding constructor: one caller-owned executor serves both paths. Useful with
     * {@code Runnable::run} for deterministic tests; production wiring should prefer
     * {@link #withDefaults} or {@link #ReplicaStatusSnapshot(InstantSource, Duration, RefreshLimits)},
     * which own bounded pools and shut them down.
     */
    public ReplicaStatusSnapshot(InstantSource clock, Duration ttl, Executor refreshExecutor) {
        this(clock, ttl, refreshExecutor, refreshExecutor, RefreshLimits.DEFAULTS.freshnessDeadline(), List.of());
    }

    /** Test/embedding constructor with a distinct executor per path and an explicit deadline. */
    public ReplicaStatusSnapshot(InstantSource clock, Duration ttl, Executor periodicExecutor,
                                 Executor freshnessExecutor, Duration freshnessDeadline) {
        this(clock, ttl, periodicExecutor, freshnessExecutor, freshnessDeadline, List.of());
    }

    /**
     * Owns and bounds both refresh pools itself, and shuts them down in {@link #close()}.
     */
    public ReplicaStatusSnapshot(InstantSource clock, Duration ttl, RefreshLimits limits) {
        this(clock, ttl,
                pool(limits.periodicConcurrency(), limits.periodicQueueCapacity(), "replica-snapshot-refresh"),
                pool(limits.freshnessConcurrency(), limits.freshnessQueueCapacity(), "replica-snapshot-fresh"),
                limits.freshnessDeadline());
    }

    private ReplicaStatusSnapshot(InstantSource clock, Duration ttl, ThreadPoolExecutor periodicExecutor,
                                  ThreadPoolExecutor freshnessExecutor, Duration freshnessDeadline) {
        this(clock, ttl, periodicExecutor, freshnessExecutor, freshnessDeadline,
                List.of(periodicExecutor, freshnessExecutor));
    }

    private ReplicaStatusSnapshot(InstantSource clock, Duration ttl, Executor periodicExecutor,
                                  Executor freshnessExecutor, Duration freshnessDeadline,
                                  List<ExecutorService> ownedExecutors) {
        if (clock == null || periodicExecutor == null || freshnessExecutor == null) {
            throw new IllegalArgumentException("clock and refresh executors are required");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        if (freshnessDeadline == null || freshnessDeadline.isZero() || freshnessDeadline.isNegative()) {
            throw new IllegalArgumentException("freshnessDeadline must be positive");
        }
        this.clock = clock;
        this.ttl = ttl;
        this.maxStale = DEFAULT_MAX_STALE;
        this.freshnessDeadline = freshnessDeadline;
        this.periodicExecutor = periodicExecutor;
        this.freshnessExecutor = freshnessExecutor;
        this.ownedExecutors = ownedExecutors;
    }

    /**
     * Production defaults: system clock, {@link #DEFAULT_TTL} and {@link RefreshLimits#DEFAULTS}
     * bounded daemon pools this snapshot owns and closes.
     */
    public static ReplicaStatusSnapshot withDefaults(InstantSource clock) {
        return new ReplicaStatusSnapshot(clock, DEFAULT_TTL, RefreshLimits.DEFAULTS);
    }

    /**
     * Wiring for a control plane with no managed deployment provider: no refresh pool is created,
     * because no refresh could ever have a provider to call. Every refresh attempt is rejected, so
     * every observation is UNAVAILABLE — the honest answer to "there is no backend to read", and
     * the one invariant I9 already requires every consumer to handle. Owning no executor, this
     * snapshot has nothing to shut down, and {@link #close()} is a no-op beyond invalidation.
     */
    public static ReplicaStatusSnapshot withoutRefreshCapacity(InstantSource clock) {
        return new ReplicaStatusSnapshot(clock, DEFAULT_TTL, task -> {
            throw new RejectedExecutionException("no managed deployment provider is configured");
        });
    }

    private static ThreadPoolExecutor pool(int concurrency, int queueCapacity, String threadName) {
        // AbortPolicy (the default): a rejected refresh fails fast and is counted. CallerRunsPolicy
        // would hand the provider call to the loop thread the bound exists to protect.
        return new ThreadPoolExecutor(
                concurrency,
                concurrency,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "nanofaas-" + threadName);
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /** Fetches a function's replica status from the deployment provider. */
    @FunctionalInterface
    public interface Fetcher {
        ReplicaStatus fetch(ManagedDeploymentTarget target);
    }

    /**
     * Non-blocking read for periodic consumers: answers from what is already known and schedules a
     * background refresh when the value is expired or missing. It never performs a synchronous
     * provider GET and never joins a refresh, so the caller's loop period is its own.
     *
     * <p>The first call for a function is therefore UNAVAILABLE rather than a blocking first fetch:
     * a periodic consumer skips one cycle and reads the value on the next one, which is strictly
     * better than holding the loop thread on a provider that may not answer.</p>
     */
    public ReplicaObservation observe(ManagedDeploymentTarget target, Fetcher fetcher) {
        Entry entry = entry(target);
        ReplicaObservation observation;
        Refresh refresh;
        synchronized (entry) {
            if (!target.backendId().equals(entry.backendId)) {
                resetFor(entry, target.backendId());
            }
            Instant now = clock.instant();
            ReplicaStatus status = entry.status;
            if (status != null && !isExpired(entry.fetchedAt, now)) {
                return ReplicaObservation.fresh(status, entry.fetchedAt);
            }
            if (status != null && !isTooStale(entry.fetchedAt, now)) {
                observation = ReplicaObservation.stale(status, entry.fetchedAt);
            } else {
                String reason = entry.failureReason;
                if (status != null) {
                    // Past the stale bound the cached value is no longer a defensible reading of
                    // reality. Drop it and report unavailability: a consumer that skips a cycle is
                    // more useful than one that acts on a confidently wrong replica count.
                    log.warn("Replica status for {} is older than the {} stale bound; dropping it",
                            target.functionName(), maxStale);
                    entry.status = null;
                    entry.fetchedAt = null;
                    if (reason == null) {
                        reason = "last reading was older than the " + maxStale + " stale bound";
                    }
                } else if (reason == null) {
                    reason = "no reading from the deployment provider yet";
                }
                observation = ReplicaObservation.unavailable(now, reason);
            }
            refresh = startOrJoinLocked(entry, target, fetcher, RefreshPath.PERIODIC);
        }
        // Submitted outside the entry lock: an inline executor would otherwise run the provider
        // call while holding it, blocking invalidate() and every other reader of this function.
        submitIfOwned(entry, refresh, target, RefreshPath.PERIODIC);
        return observation;
    }

    /**
     * Forced fresh read for wake-up and lifecycle paths: always reaches the provider (still
     * single-flight per function/generation), ignoring the cached value and its TTL, and waits at
     * most the configured freshness deadline.
     *
     * @throws ReplicaStatusUnavailableException if the deadline elapses, the refresh is rejected by
     *                                           a saturated executor, or a newer generation
     *                                           supersedes the fetch
     */
    public ReplicaStatus refresh(ManagedDeploymentTarget target, Fetcher fetcher) {
        Entry entry = entry(target);
        Refresh refresh;
        synchronized (entry) {
            if (!target.backendId().equals(entry.backendId)) {
                resetFor(entry, target.backendId());
            }
            refresh = startOrJoinLocked(entry, target, fetcher, RefreshPath.FRESHNESS);
        }
        submitIfOwned(entry, refresh, target, RefreshPath.FRESHNESS);
        return awaitWithinDeadline(entry, refresh, target);
    }

    /**
     * Invalidates the cached value after a target change, a removal or a re-registration.
     *
     * <p>Order matters: the entry is detached from the map first so no new reader can attach to a
     * doomed generation, then — under the entry lock — the generation is bumped, the owned task is
     * cancelled and any caller waiting on it is released. A refresh that completes afterwards finds
     * a superseded generation on a detached entry: it can neither publish its value nor reinsert
     * the entry into the map.</p>
     */
    public void invalidate(String functionName) {
        Entry entry = entries.remove(functionName);
        if (entry == null) {
            return;
        }
        FutureTask<Void> task;
        CompletableFuture<ReplicaStatus> pending;
        synchronized (entry) {
            entry.generation = nextGeneration();
            entry.backendId = null;
            entry.status = null;
            entry.fetchedAt = null;
            entry.failureReason = null;
            task = entry.task;
            entry.task = null;
            pending = entry.inFlight;
            entry.inFlight = null;
        }
        if (pending != null) {
            // Release any waiter before cancelling, so a task cancelled before it ever ran cannot
            // leave a caller waiting for a completion that will never come.
            pending.completeExceptionally(new ReplicaStatusUnavailableException(
                    "Replica status for " + functionName + " was invalidated while it was being fetched"));
        }
        if (task != null) {
            // Cancellation is best effort and is all the Fetcher contract supports: a queued task is
            // really dropped, a running one is interrupted, and a provider adapter that ignores
            // interrupts simply finishes into the generation guard.
            task.cancel(true);
        }
    }

    /**
     * Stops the executors owned by this snapshot and retires every entry. Injected executors belong
     * to their caller and are deliberately left alone. Detached in-flight provider calls may finish,
     * but their generation guard prevents them from publishing state after shutdown.
     */
    @Override
    public void close() {
        entries.forEach((name, entry) -> invalidate(name));
        for (ExecutorService executor : ownedExecutors) {
            // shutdownNow drains the queue without ever running what it discards, so those tasks
            // never get to account for their own dequeue: release their slots here instead.
            for (Runnable drained : executor.shutdownNow()) {
                if (drained instanceof RefreshTask task) {
                    task.releaseQueueSlot();
                }
            }
        }
    }

    /** Whether every executor this snapshot owns has terminated (nothing to check when injected). */
    public boolean isTerminated() {
        return ownedExecutors.stream().allMatch(ExecutorService::isTerminated);
    }

    // ---------------------------------------------------------------- observability (item 5)

    /** Refreshes submitted but not yet started, per path. */
    public int queueDepth(RefreshPath path) {
        return stats(path).queued.get();
    }

    /** Refresh tasks currently executing, per path. */
    public int activeRefreshes(RefreshPath path) {
        return stats(path).active.get();
    }

    /** Refreshes a saturated executor refused, per path. */
    public long rejectedRefreshes(RefreshPath path) {
        return stats(path).rejected.get();
    }

    /** Refreshes whose provider call threw, per path. */
    public long failedRefreshes(RefreshPath path) {
        return stats(path).failed.get();
    }

    /** Refreshes that ran to completion (successfully or not), per path. */
    public long completedRefreshes(RefreshPath path) {
        return stats(path).completed.get();
    }

    /** Total time spent inside refresh tasks, per path. */
    public Duration refreshDuration(RefreshPath path) {
        return Duration.ofNanos(stats(path).durationNanos.get());
    }

    /** Functions currently held in the snapshot. */
    public int entryCount() {
        return entries.size();
    }

    /** Age of the oldest cached reading, or {@link Duration#ZERO} when nothing is cached. */
    public Duration maxObservationAge() {
        Instant now = clock.instant();
        Duration oldest = Duration.ZERO;
        for (Entry entry : entries.values()) {
            Instant fetchedAt = entry.fetchedAt;
            if (fetchedAt != null) {
                Duration age = Duration.between(fetchedAt, now);
                if (age.compareTo(oldest) > 0) {
                    oldest = age;
                }
            }
        }
        return oldest.isNegative() ? Duration.ZERO : oldest;
    }

    /**
     * Publishes the queue depth, active tasks, rejections, observation age and refresh durations.
     * Tagged only by {@link RefreshPath} — two constant values — so no per-function or
     * per-generation label can grow the cardinality of the registry.
     */
    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("replica_snapshot_entries", this, ReplicaStatusSnapshot::entryCount)
                .description("Functions currently tracked by the replica status snapshot")
                .register(registry);
        // No baseUnit: Prometheus appends one to every name that does not already
        // end with it, and a name ending in `_max` never does, so declaring the
        // unit here exports this as `..._age_seconds_max_seconds`. The release
        // queries `replica_snapshot_observation_age_seconds_max` and finds nothing.
        Gauge.builder("replica_snapshot_observation_age_seconds_max", this,
                        snapshot -> snapshot.maxObservationAge().toNanos() / 1_000_000_000.0)
                .description("Age of the oldest cached replica observation")
                .register(registry);
        for (RefreshPath path : RefreshPath.values()) {
            PathStats stats = stats(path);
            Gauge.builder("replica_snapshot_refresh_queue_depth", stats, s -> s.queued.get())
                    .description("Replica refreshes submitted but not yet started")
                    .tag("path", path.tag())
                    .register(registry);
            Gauge.builder("replica_snapshot_refresh_active", stats, s -> s.active.get())
                    .description("Replica refreshes currently executing")
                    .tag("path", path.tag())
                    .register(registry);
            FunctionCounter.builder("replica_snapshot_refresh_rejected_total", stats, s -> s.rejected.get())
                    .description("Replica refreshes refused by a saturated refresh executor")
                    .tag("path", path.tag())
                    .register(registry);
            FunctionCounter.builder("replica_snapshot_refresh_failed_total", stats, s -> s.failed.get())
                    .description("Replica refreshes whose provider call failed")
                    .tag("path", path.tag())
                    .register(registry);
            FunctionTimer.builder("replica_snapshot_refresh_seconds", stats,
                            s -> s.completed.get(), s -> s.durationNanos.get(), TimeUnit.NANOSECONDS)
                    .description("Time spent inside replica refresh tasks")
                    .tag("path", path.tag())
                    .register(registry);
        }
    }

    // ---------------------------------------------------------------- internals

    private Entry entry(ManagedDeploymentTarget target) {
        return entries.computeIfAbsent(target.functionName(), name -> new Entry(nextGeneration()));
    }

    private long nextGeneration() {
        return generationSequence.incrementAndGet();
    }

    private PathStats stats(RefreshPath path) {
        return path == RefreshPath.PERIODIC ? periodicStats : freshnessStats;
    }

    private Executor executor(RefreshPath path) {
        return path == RefreshPath.PERIODIC ? periodicExecutor : freshnessExecutor;
    }

    private void resetFor(Entry entry, String backendId) {
        entry.generation = nextGeneration();
        entry.backendId = backendId;
        entry.status = null;
        entry.fetchedAt = null;
        entry.failureReason = null;
        entry.inFlight = null;
        entry.task = null;
    }

    /**
     * Single-flight: returns the refresh already in flight for this function/generation, or a new
     * one whose task the caller must submit outside the entry lock.
     */
    private Refresh startOrJoinLocked(Entry entry, ManagedDeploymentTarget target,
                                      Fetcher fetcher, RefreshPath path) {
        if (entry.inFlight != null && !entry.inFlight.isDone()) {
            return new Refresh(entry.inFlight, null);
        }
        long capturedGeneration = entry.generation;
        CompletableFuture<ReplicaStatus> result = new CompletableFuture<>();
        RefreshTask task = new RefreshTask(stats(path),
                () -> fetchAndApply(entry, target, capturedGeneration, result, fetcher, path));
        entry.inFlight = result;
        entry.task = task;
        return new Refresh(result, task);
    }

    private void submitIfOwned(Entry entry, Refresh refresh, ManagedDeploymentTarget target, RefreshPath path) {
        if (refresh.task() == null) {
            return;
        }
        PathStats stats = stats(path);
        stats.queued.incrementAndGet();
        try {
            executor(path).execute(refresh.task());
        } catch (RuntimeException rejected) {
            refresh.task().releaseQueueSlot();
            stats.rejected.incrementAndGet();
            log.warn("Replica status refresh for {} rejected by the {} refresh executor: {}",
                    target.functionName(), path.tag(), rejected.toString());
            forget(entry, refresh);
            // Completing exceptionally is what keeps single-flight honest under a saturated queue:
            // the shared future is done, so the next cycle may try again instead of waiting forever
            // on a task nobody will ever run.
            refresh.result().completeExceptionally(new ReplicaStatusUnavailableException(
                    "Replica status refresh for " + target.functionName()
                            + " was rejected by the " + path.tag() + " refresh executor"));
        }
    }

    /** Waits for a forced-fresh result, releasing the caller's thread once the deadline elapses. */
    private ReplicaStatus awaitWithinDeadline(Entry entry, Refresh refresh, ManagedDeploymentTarget target) {
        try {
            return refresh.result().get(freshnessDeadline.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timedOut) {
            ReplicaStatusUnavailableException failure = new ReplicaStatusUnavailableException(
                    "Replica status for " + target.functionName() + " was not available within the "
                            + freshnessDeadline + " freshness deadline");
            if (refresh.result().completeExceptionally(failure)) {
                // Nobody can still be served by this fetch: every waiter shares the future that has
                // just failed, so cancelling is free of collateral damage and frees a pool slot.
                cancel(entry, refresh);
            }
            throw failure;
        } catch (ExecutionException failure) {
            throw rethrow(failure.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ReplicaStatusUnavailableException(
                    "Interrupted while reading the replica status of " + target.functionName());
        }
    }

    private static RuntimeException rethrow(Throwable cause) {
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(cause);
    }

    private void cancel(Entry entry, Refresh refresh) {
        forget(entry, refresh);
        if (refresh.task() != null) {
            refresh.task().cancel(true);
        }
    }

    /** Detaches this refresh from the entry, but only while the entry still owns it. */
    @SuppressWarnings("ReferenceEquality") // Identity is the ownership token for an in-flight refresh.
    private void forget(Entry entry, Refresh refresh) {
        synchronized (entry) {
            if (entry.inFlight == refresh.result()) {
                entry.inFlight = null;
            }
            if (entry.task == refresh.task()) {
                entry.task = null;
            }
        }
    }

    private void fetchAndApply(Entry entry, ManagedDeploymentTarget target, long capturedGeneration,
                               CompletableFuture<ReplicaStatus> result, Fetcher fetcher, RefreshPath path) {
        PathStats stats = stats(path);
        stats.active.incrementAndGet();
        long startedAt = System.nanoTime();
        try {
            ReplicaStatus status;
            try {
                status = fetcher.fetch(target);
            } catch (Throwable failure) {
                // Nobody subscribes to this future on the stale-while-revalidate path, so without a
                // log a provider that has been failing for hours leaves no trace anywhere.
                log.warn("Replica status refresh failed for {}", target.functionName(), failure);
                stats.failed.incrementAndGet();
                recordFailure(entry, capturedGeneration, target, summarise(failure));
                result.completeExceptionally(failure);
                return;
            }
            boolean applied;
            synchronized (entry) {
                applied = entry.generation == capturedGeneration && target.backendId().equals(entry.backendId);
                if (applied) {
                    // A late completion still lands here: it is a real reading for the generation
                    // that asked for it, even if its caller has already given up on the deadline.
                    entry.status = status;
                    entry.fetchedAt = clock.instant();
                    entry.failureReason = null;
                }
                // Otherwise a newer generation (removal, re-registration or target change) owns this
                // entry now: the stale in-flight refresh must not overwrite it.
            }
            if (!applied) {
                // The guard protected the cache, but a caller blocked on this future would still have
                // been handed the old incarnation's replica count. A forced-fresh read that spans a
                // deprovision and re-registration must fail rather than answer for a function that no
                // longer exists in that form; the caller's next read starts from the new generation.
                result.completeExceptionally(new ReplicaStatusUnavailableException(
                        "Replica status for " + target.functionName()
                                + " was superseded by a newer generation while it was being fetched"));
                return;
            }
            result.complete(status);
        } finally {
            stats.active.decrementAndGet();
            stats.completed.incrementAndGet();
            stats.durationNanos.addAndGet(System.nanoTime() - startedAt);
        }
    }

    private void recordFailure(Entry entry, long capturedGeneration, ManagedDeploymentTarget target, String reason) {
        synchronized (entry) {
            if (entry.generation == capturedGeneration && target.backendId().equals(entry.backendId)) {
                entry.failureReason = reason;
            }
        }
    }

    private static String summarise(Throwable failure) {
        String message = failure.getMessage();
        String type = failure.getClass().getSimpleName();
        return message == null || message.isBlank() ? type : type + ": " + message;
    }

    private boolean isExpired(Instant fetchedAt, Instant now) {
        return !now.isBefore(fetchedAt.plus(ttl));
    }

    private boolean isTooStale(Instant fetchedAt, Instant now) {
        return !now.isBefore(fetchedAt.plus(ttl).plus(maxStale));
    }

    /** The shared result of one refresh, plus the task to submit when this caller started it. */
    private record Refresh(CompletableFuture<ReplicaStatus> result, RefreshTask task) {
    }

    /**
     * A refresh task that releases its own queue slot. {@link java.util.concurrent.ThreadPoolExecutor}
     * calls {@code run()} on every task it dequeues, cancelled ones included (where {@code run()}
     * returns without executing the body), so accounting for the dequeue here keeps the depth gauge
     * accurate whether the task ran, was cancelled while queued, or was interrupted mid-flight.
     */
    private static final class RefreshTask extends FutureTask<Void> {
        private final PathStats stats;
        private final AtomicBoolean dequeued = new AtomicBoolean();

        private RefreshTask(PathStats stats, Runnable body) {
            super(body, null);
            this.stats = stats;
        }

        @Override
        public void run() {
            releaseQueueSlot();
            super.run();
        }

        private void releaseQueueSlot() {
            if (dequeued.compareAndSet(false, true)) {
                stats.queued.decrementAndGet();
            }
        }
    }

    private static final class Entry {
        volatile long generation;
        volatile String backendId;
        volatile ReplicaStatus status;
        volatile Instant fetchedAt;
        volatile CompletableFuture<ReplicaStatus> inFlight;
        volatile RefreshTask task;
        /** Short summary of the last failed fetch; a string, so no stack trace is retained. */
        volatile String failureReason;

        Entry(long generation) {
            this.generation = generation;
        }
    }

    /** Per-path counters; every field is monotonic except the two depth gauges. */
    private static final class PathStats {
        final AtomicInteger queued = new AtomicInteger();
        final AtomicInteger active = new AtomicInteger();
        final AtomicLong rejected = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
        final AtomicLong completed = new AtomicLong();
        final AtomicLong durationNanos = new AtomicLong();
    }
}
