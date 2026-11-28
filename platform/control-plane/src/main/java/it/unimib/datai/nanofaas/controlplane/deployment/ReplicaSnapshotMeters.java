package it.unimib.datai.nanofaas.controlplane.deployment;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatusSnapshot.RefreshPath;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The replica snapshot's refresh counters, one set per {@link RefreshPath}, and the meters that
 * publish them: queue depth, active tasks, rejections, failures, refresh durations, plus the
 * snapshot's entry count and oldest observation age. Tagged only by the path, two constant values,
 * so no per-function or per-generation label can grow the registry's cardinality.
 */
final class ReplicaSnapshotMeters {

    /** Per-path counters; every field is monotonic except the two depth gauges. */
    static final class PathStats {
        final AtomicInteger queued = new AtomicInteger();
        final AtomicInteger active = new AtomicInteger();
        final AtomicLong rejected = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
        final AtomicLong completed = new AtomicLong();
        final AtomicLong durationNanos = new AtomicLong();
    }

    private final PathStats periodic = new PathStats();
    private final PathStats freshness = new PathStats();

    PathStats of(RefreshPath path) {
        return path == RefreshPath.PERIODIC ? periodic : freshness;
    }

    void bindTo(MeterRegistry registry, ReplicaStatusSnapshot snapshot) {
        Gauge.builder("replica_snapshot_entries", snapshot, ReplicaStatusSnapshot::entryCount)
                .description("Functions currently tracked by the replica status snapshot")
                .register(registry);
        // No baseUnit: Prometheus appends one to every name that does not already
        // end with it, and a name ending in `_max` never does, so declaring the
        // unit here exports this as `..._age_seconds_max_seconds`. The release
        // queries `replica_snapshot_observation_age_seconds_max` and finds nothing.
        Gauge.builder("replica_snapshot_observation_age_seconds_max", snapshot,
                        s -> s.maxObservationAge().toNanos() / 1_000_000_000.0)
                .description("Age of the oldest cached replica observation")
                .register(registry);
        for (RefreshPath path : RefreshPath.values()) {
            PathStats stats = of(path);
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
}
