package it.unimib.datai.nanofaas.controlplane.deployment;

import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
 * @param periodicConcurrency    slow provider refreshes allowed to run at once for {@link ReplicaStatusSnapshot#observe}
 * @param periodicQueueCapacity  pending periodic refreshes before submissions are rejected
 * @param freshnessConcurrency   provider reads allowed to run at once for {@link ReplicaStatusSnapshot#refresh}
 * @param freshnessQueueCapacity pending forced-fresh reads before submissions are rejected
 * @param freshnessDeadline      how long {@link ReplicaStatusSnapshot#refresh} waits before releasing its caller
 */
public record ReplicaRefreshLimits(int periodicConcurrency,
                            int periodicQueueCapacity,
                            int freshnessConcurrency,
                            int freshnessQueueCapacity,
                            Duration freshnessDeadline) {

    /**
     * Production defaults. The 5s freshness deadline sits well inside the wake-up gate's own
     * 30s budget, so a hung provider frees the wake-up worker long before the gate gives up.
     */
    public static final ReplicaRefreshLimits DEFAULTS =
            new ReplicaRefreshLimits(2, 64, 4, 32, Duration.ofSeconds(5));

    public ReplicaRefreshLimits {
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

    ThreadPoolExecutor periodicPool() {
        return pool(periodicConcurrency, periodicQueueCapacity, "replica-snapshot-refresh");
    }

    ThreadPoolExecutor freshnessPool() {
        return pool(freshnessConcurrency, freshnessQueueCapacity, "replica-snapshot-fresh");
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
}
