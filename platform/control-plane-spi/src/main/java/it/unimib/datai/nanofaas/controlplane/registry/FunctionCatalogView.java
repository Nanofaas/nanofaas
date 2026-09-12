package it.unimib.datai.nanofaas.controlplane.registry;

import java.util.Collection;

/**
 * Read-only access to the registered functions, for the control loops that iterate over them.
 *
 * <p>This is the whole surface the autoscaler and the concurrency governor consume from the
 * catalog. They observe and decide; registration, update and removal stay with the core
 * registry, so a control loop cannot mutate the catalog it is reading.</p>
 */
public interface FunctionCatalogView {

    /** Every currently registered function, as an immutable snapshot safe to iterate. */
    Collection<RegisteredFunction> listRegistered();
}
