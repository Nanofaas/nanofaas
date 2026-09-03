package it.unimib.datai.nanofaas.controlplane.registry;

import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * FunctionCatalog.save() serializes the snapshot through an ObjectMapper the AOT engine cannot
 * see through, so no binding hints were generated for it. The native image started, then died in
 * the catalog restorer: Jackson asks each record for its components and GraalVM refuses unless
 * the component accessors are in the reflection configuration.
 *
 * Registering the snapshot walks the whole persisted graph, so a new field on any of those
 * records is covered without touching this class.
 */
public class FunctionCatalogRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        new BindingReflectionHintsRegistrar()
                .registerReflectionHints(hints.reflection(), FunctionCatalogSnapshot.class);
    }
}
