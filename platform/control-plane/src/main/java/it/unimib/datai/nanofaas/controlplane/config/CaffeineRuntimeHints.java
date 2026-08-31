package it.unimib.datai.nanofaas.controlplane.config;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Caffeine does not ship one cache class: it builds a name out of the options each builder
 * uses ({@code SSW} for strong keys/values plus expireAfterWrite, {@code SSMSA} once
 * maximumSize and a variable expiry join in) and loads that class reflectively. Native-image
 * sees no reference to any of them and drops all 500-odd, so the control plane dies building
 * its first cache.
 *
 * <p>This used to name the one variant in use, and the day {@code ExecutionStore} added
 * {@code maximumSize} the native build started crashing at startup — the list said it would.
 * Registering every generated class instead costs image size once and cannot fall behind a
 * builder change, which is the trade the failure mode argues for.
 */
public class CaffeineRuntimeHints implements RuntimeHintsRegistrar {

    private static final String GENERATED_CACHE_CLASSES =
            "classpath*:com/github/benmanes/caffeine/cache/*.class";

    /** Generated variants are named only with the option letters; the hand-written API is not. */
    private static final String GENERATED_NAME = "[A-Z]+";

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        for (String className : generatedCacheClasses(classLoader)) {
            hints.reflection().registerType(
                    TypeReference.of(className),
                    builder -> builder
                            .withMembers(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS)
                            .withField("FACTORY"));
        }
    }

    private static List<String> generatedCacheClasses(ClassLoader classLoader) {
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver(classLoader)
                    .getResources(GENERATED_CACHE_CLASSES);
        } catch (IOException e) {
            // Failing the build beats shipping a native image that dies on its first cache.
            throw new IllegalStateException("Cannot scan Caffeine's cache classes", e);
        }

        List<String> classNames = new ArrayList<>();
        for (Resource resource : resources) {
            String filename = resource.getFilename();
            if (filename == null) {
                continue;
            }
            String simpleName = filename.substring(0, filename.length() - ".class".length());
            if (simpleName.matches(GENERATED_NAME)) {
                classNames.add("com.github.benmanes.caffeine.cache." + simpleName);
            }
        }
        if (classNames.isEmpty()) {
            throw new IllegalStateException(
                    "Found no Caffeine cache classes to register; the native image would not start");
        }
        return classNames;
    }
}
