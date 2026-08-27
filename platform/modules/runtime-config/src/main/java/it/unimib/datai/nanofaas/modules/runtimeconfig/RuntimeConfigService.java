package it.unimib.datai.nanofaas.modules.runtimeconfig;

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
            try {
                sample.stop(applyTimer);
            } catch (Exception ignored) {
                // Metrics must not change runtime-config transaction semantics.
            }
        }
    }

    private RuntimeConfigExtension extension(String namespace) {
        return registry.extension(namespace)
                .orElseThrow(() -> new UnknownRuntimeConfigNamespaceException(namespace));
    }
}
