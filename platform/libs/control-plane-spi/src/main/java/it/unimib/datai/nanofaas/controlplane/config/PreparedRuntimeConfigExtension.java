package it.unimib.datai.nanofaas.controlplane.config;

import java.util.Map;

/**
 * A {@link RuntimeConfigExtension} whose update is a two-phase, prepare-then-commit
 * transaction rather than an in-place {@code apply}/{@code restore} pair.
 *
 * <p>Some namespaces back a change that cannot be undone once activated (for example,
 * switching the active scheduling strategy: the new index is published and is the new
 * truth from that instant on). For those, the service must do every fallible step
 * &mdash; validation, precomputing the resulting snapshot &mdash; strictly before
 * {@link PreparedRuntimeConfigChange#commit()} runs, since nothing after commit may
 * pretend the switch did not happen.</p>
 */
public interface PreparedRuntimeConfigExtension extends RuntimeConfigExtension {

    /**
     * Validates and precomputes the patch into a {@link PreparedRuntimeConfigChange},
     * without activating anything yet. May throw if the patch cannot be prepared.
     */
    PreparedRuntimeConfigChange prepare(Map<String, Object> patch);
}
