package it.unimib.datai.nanofaas.controlplane.config;

import java.util.List;
import java.util.Map;

/**
 * A namespaced, hot-updatable portion of runtime configuration.
 *
 * <p>The contract lives in the control-plane namespace, not in the package of the module that
 * serves it: both the runtime-config module and the sync-queue bridge implement it, and a
 * contract named after one optional module would make the other's dependency look like a
 * cross-module dependency when it is a dependency on the SPI.</p>
 */
public interface RuntimeConfigExtension {
    String namespace();

    Map<String, Object> snapshot();

    List<String> validate(Map<String, Object> patch);

    void apply(Map<String, Object> patch);

    void restore(Map<String, Object> snapshot);
}
