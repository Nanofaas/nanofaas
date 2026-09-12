package it.unimib.datai.nanofaas.modules.runtimeconfig;

import it.unimib.datai.nanofaas.controlplane.config.RuntimeConfigExtension;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class RuntimeConfigRegistry {
    private final Map<String, RuntimeConfigExtension> extensions;

    public RuntimeConfigRegistry(List<RuntimeConfigExtension> extensions) {
        Map<String, RuntimeConfigExtension> ordered = new java.util.TreeMap<>();
        for (RuntimeConfigExtension extension : extensions) {
            String namespace = extension.namespace();
            if (namespace == null || namespace.isBlank()) {
                throw new IllegalStateException("Runtime config namespace must not be blank");
            }
            if (ordered.put(namespace, extension) != null) {
                throw new IllegalStateException("Duplicate runtime config namespace: " + namespace);
            }
        }
        this.extensions = Collections.unmodifiableMap(new LinkedHashMap<>(ordered));
    }

    public Optional<RuntimeConfigExtension> extension(String namespace) {
        return Optional.ofNullable(extensions.get(namespace));
    }

    public Map<String, Map<String, Object>> snapshot() {
        Map<String, Map<String, Object>> snapshot = new LinkedHashMap<>();
        extensions.forEach((namespace, extension) ->
                snapshot.put(namespace, Map.copyOf(extension.snapshot())));
        return Collections.unmodifiableMap(snapshot);
    }
}
