package it.unimib.datai.nanofaas.controlplane.deployment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared, cached view of a managed deployment's replica status, so the autoscaler, the concurrency
 * governor and any other periodic consumer read the provider at most once per TTL window instead of
 * once per consumer per cycle.
 *
 * <p>Semantics that keep the periodic consumers safe:</p>
 * <ul>
 *   <li>{@link #read} is <em>non-blocking</em> once data exists: a fresh entry is served from cache,
 *       an expired entry is served as last-known-good while a refresh runs in the background
 *       (stale-while-revalidate), so one slow function can never stall the reads of the others.</li>
 *   <li>Only one refresh runs per function/generation at a time (single-flight); the refresh itself
 *       runs on a bounded executor so slow provider GETs cannot spawn unbounded work.</li>
 *   <li>An expired entry whose refresh fails is <em>not</em> reported as zero replicas — the
 *       last-known-good value is kept. A first fetch that fails is propagated to the caller, never
 *       replaced with {@code (0, 0)}.</li>
 *   <li>{@link #invalidate} bumps the function's generation and drops the cached value; a refresh
 *       that was already in flight for the previous generation is discarded instead of landing
 *       (generation guard), so a stale read can never overwrite a newer target or a re-registered
 *       function's state.</li>
 * </ul>
 */
public final class ReplicaStatusSnapshot implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ReplicaStatusSnapshot.class);

    /** Default freshness window: one autoscaler/governor poll interval. */
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(5);

    /** How many slow provider refreshes may run at once before the rest queue. */
    public static final int DEFAULT_REFRESH_CONCURRENCY = 2;

    /**
     * How far past the TTL a last-known-good value may still be served while refreshes keep
     * failing. Past it the failure is the answer.
     *
     * <p>Deliberately shorter than the autoscaler's progress window: a frozen value replayed for a
     * whole window is indistinguishable from a rollout making no progress, and would be reconciled
     * down as a stuck one. Bounding the age below that window means a dead read path can never
     * masquerade as a stuck rollout — it makes the scaler skip the cycle instead.
     */
    public static final Duration DEFAULT_MAX_STALE = Duration.ofSeconds(15);

    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong generationSequence = new AtomicLong();
    private final InstantSource clock;
    private final Duration ttl;
    private final Duration maxStale;
    private final Executor refreshExecutor;
    private final ExecutorServiceOwner executorOwner;

    public ReplicaStatusSnapshot(InstantSource clock, Duration ttl, Executor refreshExecutor) {
        if (clock == null || refreshExecutor == null) {
            throw new IllegalArgumentException("clock and refreshExecutor are required");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        this.clock = clock;
        this.ttl = ttl;
        this.maxStale = DEFAULT_MAX_STALE;
        this.refreshExecutor = refreshExecutor;
        this.executorOwner = null;
    }

    private ReplicaStatusSnapshot(InstantSource clock, Duration ttl, ThreadPoolExecutor refreshExecutor) {
        if (clock == null) {
            throw new IllegalArgumentException("clock is required");
        }
        this.clock = clock;
        this.ttl = ttl;
        this.maxStale = DEFAULT_MAX_STALE;
        this.refreshExecutor = refreshExecutor;
        this.executorOwner = new ExecutorServiceOwner(refreshExecutor);
    }

    /**
     * Production defaults: system clock, {@link #DEFAULT_TTL}, a daemon refresh pool this
     * snapshot owns and closes.
     *
     * <p>The queue is deliberately unbounded, matching the pre-existing pool's behavior: this
     * task (P09) fixes the owned-executor/entry-removal half of R8, not the queue-bound
     * backpressure policy for slow refreshes, which is P10's own scope (fresh/stale/unavailable
     * observation semantics, a freshness-required deadline path, refresh queue/rejection
     * observability). Bounding this queue and choosing a rejection policy belongs there, with
     * its own tests.
     */
    public static ReplicaStatusSnapshot withDefaults(InstantSource clock) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                DEFAULT_REFRESH_CONCURRENCY,
                DEFAULT_REFRESH_CONCURRENCY,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "nanofaas-replica-snapshot-refresh");
                    thread.setDaemon(true);
                    return thread;
                });
        return new ReplicaStatusSnapshot(clock, DEFAULT_TTL, executor);
    }

    /** Fetches a function's replica status from the deployment provider. */
    @FunctionalInterface
    public interface Fetcher {
        ReplicaStatus fetch(ManagedDeploymentTarget target);
    }

    /**
     * Cached read for periodic consumers. Never blocks on the provider once a value is known: a
     * fresh entry is served from cache, an expired entry is served as last-known-good while a
     * background refresh runs, and only a cold start (no value for this function/generation yet)
     * waits for a first fetch.
     */
    public ReplicaStatus read(ManagedDeploymentTarget target, Fetcher fetcher) {
        Entry entry = entry(target);
        synchronized (entry) {
            if (!target.backendId().equals(entry.backendId)) {
                resetFor(entry, target.backendId());
            }
            ReplicaStatus status = entry.status;
            if (status != null && !isExpired(entry.fetchedAt)) {
                return status;
            }
            if (status != null && !isTooStale(entry.fetchedAt)) {
                startRefreshLocked(entry, target, fetcher);
                return status;
            }
            if (status != null) {
                // Past the stale bound the cached value is no longer a defensible reading of
                // reality. Drop it and let the caller see whatever the provider actually does:
                // a genuine failure is more useful than a confidently wrong replica count.
                log.warn("Replica status for {} is older than the {} stale bound; refusing to serve it",
                        target.functionName(), maxStale);
                entry.status = null;
                entry.fetchedAt = null;
            }
        }
        return refreshSynchronously(entry, target, fetcher);
    }

    /**
     * Forced fresh read for wake-up and lifecycle paths: always reaches the provider (still
     * single-flight per function/generation), ignoring the cached value and its TTL.
     */
    public ReplicaStatus refresh(ManagedDeploymentTarget target, Fetcher fetcher) {
        Entry entry = entry(target);
        synchronized (entry) {
            if (!target.backendId().equals(entry.backendId)) {
                resetFor(entry, target.backendId());
            }
        }
        return refreshSynchronously(entry, target, fetcher);
    }

    /**
     * Invalidates the cached value after a target change, a removal or a re-registration: bumps the
     * generation and drops the value, so the next read re-fetches and any refresh already in flight
     * for the previous generation is discarded on completion.
     */
    public void invalidate(String functionName) {
        Entry entry = entries.remove(functionName);
        if (entry != null) {
            synchronized (entry) {
                entry.generation = nextGeneration();
                entry.backendId = null;
                entry.status = null;
                entry.fetchedAt = null;
                // Do not cancel arbitrary provider work here: adapters may not support
                // cancellation. Removing the entry is the ownership fence; an old completion can
                // only touch this detached Entry and can never reinsert it into entries.
            }
        }
    }

    /**
     * Stops the executor owned by this snapshot. Injected executors belong to their caller and
     * are deliberately left alone. Detached in-flight provider calls may finish, but their
     * generation guard prevents them from publishing state after shutdown.
     */
    @Override
    public void close() {
        entries.forEach((name, entry) -> invalidate(name));
        if (executorOwner != null) {
            executorOwner.shutdown();
        }
    }

    private Entry entry(ManagedDeploymentTarget target) {
        return entries.computeIfAbsent(target.functionName(), name -> new Entry(nextGeneration()));
    }

    private long nextGeneration() {
        return generationSequence.incrementAndGet();
    }

    private void resetFor(Entry entry, String backendId) {
        entry.generation = nextGeneration();
        entry.backendId = backendId;
        entry.status = null;
        entry.fetchedAt = null;
        entry.inFlight = null;
    }

    private void startRefreshLocked(Entry entry, ManagedDeploymentTarget target, Fetcher fetcher) {
        if (entry.inFlight != null && !entry.inFlight.isDone()) {
            return;
        }
        long capturedGeneration = entry.generation;
        CompletableFuture<ReplicaStatus> future = new CompletableFuture<>();
        entry.inFlight = future;
        submitFetch(entry, target, capturedGeneration, future, fetcher);
    }

    /**
     * Runs the fetch on the CALLING thread rather than the shared background pool, still
     * single-flight: a refresh already in flight for this generation is joined instead of
     * duplicated. The callers here are wake-up, lifecycle and cold reads — latency-critical paths
     * that already run on their own bounded executors. Routing them through the small background
     * pool put them behind slow periodic refreshes, and behind the unauthenticated replicas
     * endpoint that shares it, narrowing the cold-start path instead of widening it.
     */
    private ReplicaStatus refreshSynchronously(Entry entry, ManagedDeploymentTarget target, Fetcher fetcher) {
        CompletableFuture<ReplicaStatus> future;
        boolean fetchHere = false;
        long capturedGeneration;
        synchronized (entry) {
            capturedGeneration = entry.generation;
            if (entry.inFlight == null || entry.inFlight.isDone()) {
                future = new CompletableFuture<>();
                entry.inFlight = future;
                fetchHere = true;
            } else {
                future = entry.inFlight;
            }
        }
        if (fetchHere) {
            fetchAndApply(entry, target, capturedGeneration, future, fetcher);
        }
        return joinResult(future);
    }

    private void submitFetch(Entry entry, ManagedDeploymentTarget target, long capturedGeneration,
                             CompletableFuture<ReplicaStatus> result, Fetcher fetcher) {
        try {
            refreshExecutor.execute(() -> fetchAndApply(entry, target, capturedGeneration, result, fetcher));
        } catch (RuntimeException rejected) {
            log.warn("Replica status refresh for {} rejected by the refresh executor", target.functionName());
            result.completeExceptionally(rejected);
        }
    }

    private void fetchAndApply(Entry entry, ManagedDeploymentTarget target, long capturedGeneration,
                               CompletableFuture<ReplicaStatus> result, Fetcher fetcher) {
        ReplicaStatus status;
        try {
            status = fetcher.fetch(target);
        } catch (Throwable failure) {
            // Nobody subscribes to this future on the stale-while-revalidate path, so without a
            // log a provider that has been failing for hours leaves no trace anywhere.
            log.warn("Replica status refresh failed for {}", target.functionName(), failure);
            result.completeExceptionally(failure);
            return;
        }
        boolean applied;
        synchronized (entry) {
            applied = entry.generation == capturedGeneration && target.backendId().equals(entry.backendId);
            if (applied) {
                entry.status = status;
                entry.fetchedAt = clock.instant();
            }
            // Otherwise a newer generation (removal, re-registration or target change) owns this
            // entry now: the stale in-flight refresh must not overwrite it.
        }
        if (!applied) {
            // The guard protected the cache, but a caller blocked on this future would still have
            // been handed the old incarnation's replica count. A forced-fresh read that spans a
            // deprovision and re-registration must fail rather than answer for a function that no
            // longer exists in that form; the caller's next read starts from the new generation.
            result.completeExceptionally(new IllegalStateException(
                    "Replica status for " + target.functionName()
                            + " was superseded by a newer generation while it was being fetched"));
            return;
        }
        result.complete(status);
    }

    private boolean isExpired(Instant fetchedAt) {
        return !clock.instant().isBefore(fetchedAt.plus(ttl));
    }

    private boolean isTooStale(Instant fetchedAt) {
        return !clock.instant().isBefore(fetchedAt.plus(ttl).plus(maxStale));
    }

    private static ReplicaStatus joinResult(CompletableFuture<ReplicaStatus> future) {
        try {
            return future.join();
        } catch (CompletionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw failure;
        }
    }

    private static final class Entry {
        volatile long generation;
        volatile String backendId;
        volatile ReplicaStatus status;
        volatile Instant fetchedAt;
        volatile CompletableFuture<ReplicaStatus> inFlight;

        Entry(long generation) {
            this.generation = generation;
        }
    }

    private static final class ExecutorServiceOwner {
        private final ThreadPoolExecutor executor;

        private ExecutorServiceOwner(ThreadPoolExecutor executor) {
            this.executor = executor;
        }

        private void shutdown() {
            executor.shutdownNow();
        }
    }
}
