package it.unimib.datai.nanofaas.modules.runtimeconfig;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record RuntimeConfigSnapshot(long revision, Map<String, Map<String, Object>> namespaces) {
    public RuntimeConfigSnapshot {
        Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
        namespaces.forEach((namespace, values) -> copy.put(namespace, Map.copyOf(values)));
        namespaces = Collections.unmodifiableMap(copy);
    }
}
