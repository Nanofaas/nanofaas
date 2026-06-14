package it.unimib.datai.nanofaas.controlplane;

import it.unimib.datai.nanofaas.common.controlplane.ControlPlaneModule;
import org.springframework.context.annotation.DeferredImportSelector;
import org.springframework.core.type.AnnotationMetadata;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Ensures optional module @Configuration classes are imported for every bootstrap path,
 * including tests that do not execute ControlPlaneApplication.main().
 */
public final class ControlPlaneModuleImportSelector implements DeferredImportSelector {

    @Override
    public String[] selectImports(AnnotationMetadata importingClassMetadata) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Set<String> moduleConfigurations = new LinkedHashSet<>();
        ServiceLoader.load(ControlPlaneModule.class, classLoader).forEach(module -> module.configurationClasses().stream()
                .filter(Objects::nonNull)
                .map(Class::getName)
                .forEach(moduleConfigurations::add));
        return moduleConfigurations.toArray(String[]::new);
    }
}
