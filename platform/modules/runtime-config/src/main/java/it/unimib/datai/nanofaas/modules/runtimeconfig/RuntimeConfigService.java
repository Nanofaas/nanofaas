package it.unimib.datai.nanofaas.modules.runtimeconfig;

import io.micrometer.core.instrument.MeterRegistry;

import java.util.List;
import java.util.Map;

public class RuntimeConfigService {
    private final RuntimeConfigRegistry registry;
    private final MeterRegistry meterRegistry;
    private long revision;

    public RuntimeConfigService(RuntimeConfigRegistry registry, MeterRegistry meterRegistry) {
        this.registry = registry;
        this.meterRegistry = meterRegistry;
    }

    public synchronized RuntimeConfigSnapshot getSnapshot() {
        return new RuntimeConfigSnapshot(revision, registry.snapshot());
    }

    public List<String> validate(String namespace, Map<String, Object> patch) {
        return extension(namespace).validate(Map.copyOf(patch));
    }

    public synchronized RuntimeConfigSnapshot update(long expectedRevision,
                                                      String namespace,
                                                      Map<String, Object> patch) {
        if (revision != expectedRevision) {
            throw new RevisionMismatchException(expectedRevision, revision);
        }
        RuntimeConfigExtension extension = extension(namespace);
        Map<String, Object> previous = Map.copyOf(extension.snapshot());
        List<String> errors = extension.validate(Map.copyOf(patch));
        if (!errors.isEmpty()) {
            throw new RuntimeConfigValidationException(errors);
        }
        try {
            extension.apply(Map.copyOf(patch));
            revision++;
            meterRegistry.counter("controlplane_runtime_config_updates_total", "status", "success", "namespace", namespace).increment();
            return getSnapshot();
        } catch (Exception applyFailure) {
            try {
                extension.restore(previous);
            } catch (Exception restoreFailure) {
                applyFailure.addSuppressed(restoreFailure);
            }
            meterRegistry.counter("controlplane_runtime_config_updates_total", "status", "failure", "namespace", namespace).increment();
            throw new RuntimeConfigApplyException("Failed to apply runtime config", applyFailure);
        }
    }

    private RuntimeConfigExtension extension(String namespace) {
        return registry.extension(namespace)
                .orElseThrow(() -> new UnknownRuntimeConfigNamespaceException(namespace));
    }
}
