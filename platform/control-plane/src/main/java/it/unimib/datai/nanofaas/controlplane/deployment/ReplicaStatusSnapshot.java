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
import java.util.concurrent.Executors;
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
public final class ReplicaStatusSnapshot {

    private static final Logger log = LoggerFactory.getLogger(ReplicaStatusSnapshot.class);

    /** Default freshness window: one autoscaler/governor poll interval. */
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(5);

    /** How many slow provider refreshes may run at once before the rest queue. */
    public static final int DEFAULT_REFRESH_CONCURRENCY = 2;

    private static final Executor DEFAULT_REFRESH_EXECUTOR = Executors.newFixedThreadPool(
            DEFAULT_REFRESH_CONCURRENCY, runnable -> {
                Thread thread = new Thread(runnable, "nanofaas-replica-snapshot-refresh");
                thread.setDaemon(true);
                return thread;
            });

    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong generationSequence = new AtomicLong();
    private final InstantSource clock;
    private final Duration ttl;
    private final Executor refreshExecutor;

    public ReplicaStatusSnapshot(InstantSource clock, Duration ttl, Executor refreshExecutor) {
        if (clock == null || refreshExecutor == null) {
            throw new IllegalArgumentException("clock and refreshExecutor are required");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        this.clock = clock;
        this.ttl = ttl;
        this.refreshExecutor = refreshExecutor;
    }

    /** Production defaults: system clock, {@link #DEFAULT_TTL}, bounded daemon refresh pool. */
    public static ReplicaStatusSnapshot withDefaults(InstantSource clock) {
        return new ReplicaStatusSnapshot(clock, DEFAULT_TTL, DEFAULT_REFRESH_EXECUTOR);
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
            if (status != null) {
                startRefreshLocked(entry, target, fetcher);
                return status;
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
        Entry entry = entries.get(functionName);
        if (entry == null) {
            return;
        }
        synchronized (entry) {
            entry.generation = nextGeneration();
            entry.backendId = null;
            entry.status = null;
            entry.fetchedAt = null;
            entry.inFlight = null;
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

    private ReplicaStatus refreshSynchronously(Entry entry, ManagedDeploymentTarget target, Fetcher fetcher) {
        CompletableFuture<ReplicaStatus> future;
        synchronized (entry) {
            long capturedGeneration = entry.generation;
            if (entry.inFlight == null || entry.inFlight.isDone()) {
                CompletableFuture<ReplicaStatus> fresh = new CompletableFuture<>();
                entry.inFlight = fresh;
                submitFetch(entry, target, capturedGeneration, fresh, fetcher);
            }
            future = entry.inFlight;
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
            result.completeExceptionally(failure);
            return;
        }
        synchronized (entry) {
            if (entry.generation == capturedGeneration && target.backendId().equals(entry.backendId)) {
                entry.status = status;
                entry.fetchedAt = clock.instant();
            }
            // Otherwise a newer generation (removal, re-registration or target change) owns this
            // entry now: the stale in-flight refresh must not overwrite it.
        }
        result.complete(status);
    }

    private boolean isExpired(Instant fetchedAt) {
        return !clock.instant().isBefore(fetchedAt.plus(ttl));
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
}
