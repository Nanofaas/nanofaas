package it.unimib.datai.nanofaas.modules.runtimeconfig;

import it.unimib.datai.nanofaas.controlplane.config.PreparedRuntimeConfigChange;
import it.unimib.datai.nanofaas.controlplane.config.PreparedRuntimeConfigExtension;
import it.unimib.datai.nanofaas.controlplane.config.RuntimeConfigExtension;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class RuntimeConfigService {
    private final RuntimeConfigRegistry registry;
    private final MeterRegistry meterRegistry;
    private final AtomicLong revision = new AtomicLong();
    private final Timer applyTimer;

    public RuntimeConfigService(RuntimeConfigRegistry registry, MeterRegistry meterRegistry) {
        this.registry = registry;
        this.meterRegistry = meterRegistry;
        Gauge.builder("controlplane_runtime_config_revision", revision, AtomicLong::get).register(meterRegistry);
        applyTimer = Timer.builder("controlplane_runtime_config_apply_duration_seconds").register(meterRegistry);
    }

    public synchronized RuntimeConfigSnapshot getSnapshot() {
        return new RuntimeConfigSnapshot(revision.get(), registry.snapshot());
    }

    public List<String> validate(String namespace, Map<String, Object> patch) {
        return extension(namespace).validate(Map.copyOf(patch));
    }

    public synchronized RuntimeConfigSnapshot update(long expectedRevision,
                                                      String namespace,
                                                      Map<String, Object> patch) {
        long currentRevision = revision.get();
        if (currentRevision != expectedRevision) {
            throw new RevisionMismatchException(expectedRevision, currentRevision);
        }
        RuntimeConfigExtension extension = extension(namespace);
        if (extension instanceof PreparedRuntimeConfigExtension prepared) {
            List<String> errors = prepared.validate(Map.copyOf(patch));
            if (!errors.isEmpty()) {
                throw new RuntimeConfigValidationException(errors);
            }
            return updatePrepared(prepared, namespace, patch, currentRevision);
        }
        return updateLegacy(extension, namespace, patch, currentRevision);
    }

    /**
     * Two-phase path: every fallible step (validation already happened, then preparing
     * the change and computing the resulting snapshot) runs BEFORE {@code commit()},
     * because commit is the irreversible activation point and nothing after it may
     * pretend the change did not happen. {@code registry.snapshot()} is deliberately not
     * re-read after commit: a post-commit snapshot failure would leave an activated
     * change with no coherent snapshot to return.
     */
    private RuntimeConfigSnapshot updatePrepared(PreparedRuntimeConfigExtension extension,
                                                  String namespace,
                                                  Map<String, Object> patch,
                                                  long currentRevision) {
        Timer.Sample sample = Timer.start(meterRegistry);
        PreparedRuntimeConfigChange change = null;
        RuntimeConfigSnapshot next = null;
        boolean success = false;
        RuntimeConfigApplyException failure = null;
        try {
            change = extension.prepare(Map.copyOf(patch));
            next = new RuntimeConfigSnapshot(currentRevision + 1,
                    registry.snapshotReplacing(namespace, change.snapshotAfterCommit()));
            change.commit();
            revision.set(next.revision());
            success = true;
        } catch (Exception prepareOrCommitFailure) {
            failure = new RuntimeConfigApplyException("Failed to apply runtime config", prepareOrCommitFailure);
        } finally {
            if (change != null) {
                try {
                    change.close();
                } catch (Exception closeFailure) {
                    // A cleanup failure after a successful commit must not turn success
                    // into failure: the change is already active. Before commit, fold it
                    // in so callers can see it, but the prepare/commit failure still wins.
                    if (!success && failure != null) {
                        failure.addSuppressed(closeFailure);
                    }
                }
            }
            recordOutcomeMetric(namespace, success, failure);
            stopTimerSafely(sample);
        }
        if (failure != null) {
            throw failure;
        }
        return next;
    }

    private RuntimeConfigSnapshot updateLegacy(RuntimeConfigExtension extension,
                                                String namespace,
                                                Map<String, Object> patch,
                                                long currentRevision) {
        Map<String, Object> previous = Map.copyOf(extension.snapshot());
        List<String> errors = extension.validate(Map.copyOf(patch));
        if (!errors.isEmpty()) {
            throw new RuntimeConfigValidationException(errors);
        }
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            extension.apply(Map.copyOf(patch));
            RuntimeConfigSnapshot updated = new RuntimeConfigSnapshot(currentRevision + 1, registry.snapshot());
            meterRegistry.counter("controlplane_runtime_config_updates_total", "status", "success", "namespace", namespace).increment();
            revision.set(currentRevision + 1);
            return updated;
        } catch (Exception applyFailure) {
            try {
                extension.restore(previous);
            } catch (Exception restoreFailure) {
                applyFailure.addSuppressed(restoreFailure);
            }
            try {
                meterRegistry.counter("controlplane_runtime_config_updates_total", "status", "failure", "namespace", namespace).increment();
            } catch (Exception metricFailure) {
                applyFailure.addSuppressed(metricFailure);
            }
            throw new RuntimeConfigApplyException("Failed to apply runtime config", applyFailure);
        } finally {
            stopTimerSafely(sample);
        }
    }

    private void recordOutcomeMetric(String namespace, boolean success, RuntimeConfigApplyException failure) {
        try {
            meterRegistry.counter("controlplane_runtime_config_updates_total",
                    "status", success ? "success" : "failure", "namespace", namespace).increment();
        } catch (Exception metricFailure) {
            // Metrics must not change runtime-config transaction semantics: a successful
            // commit stays successful, and a pre-commit failure keeps its own cause.
            if (!success && failure != null) {
                failure.addSuppressed(metricFailure);
            }
        }
    }

    private void stopTimerSafely(Timer.Sample sample) {
        try {
            sample.stop(applyTimer);
        } catch (Exception _) {
            // Metrics must not change runtime-config transaction semantics.
        }
    }

    private RuntimeConfigExtension extension(String namespace) {
        return registry.extension(namespace)
                .orElseThrow(() -> new UnknownRuntimeConfigNamespaceException(namespace));
    }
}
