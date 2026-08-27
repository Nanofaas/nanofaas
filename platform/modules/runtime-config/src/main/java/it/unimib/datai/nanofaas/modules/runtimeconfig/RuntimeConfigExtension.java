package it.unimib.datai.nanofaas.modules.runtimeconfig;

import java.util.List;
import java.util.Map;

/** A namespaced, hot-updatable portion of runtime configuration. */
public interface RuntimeConfigExtension {
    String namespace();

    Map<String, Object> snapshot();

    List<String> validate(Map<String, Object> patch);

    void apply(Map<String, Object> patch);

    void restore(Map<String, Object> snapshot);
}
