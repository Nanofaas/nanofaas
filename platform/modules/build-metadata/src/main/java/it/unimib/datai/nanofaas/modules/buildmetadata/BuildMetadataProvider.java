package it.unimib.datai.nanofaas.modules.buildmetadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Resolves this artifact's {@link BuildMetadata} once at construction from the
 * generated build properties, the environment, and JVM introspection, then
 * hands back the same immutable instance on every call. Never touches Docker
 * or Kubernetes, and never exposes raw JVM arguments.
 */
final class BuildMetadataProvider {

    private static final String PROPERTIES_RESOURCE = "META-INF/nanofaas-build.properties";
    private static final Logger LOG = LoggerFactory.getLogger(BuildMetadataProvider.class);

    private final BuildMetadata metadata;

    BuildMetadataProvider() {
        this(BuildMetadataProvider.class.getClassLoader());
    }

    // Package-private: lets a test drive resource LOADING (as opposed to the
    // Properties-injecting constructor below, which only exercises parsing) with a
    // classloader that can't see META-INF/nanofaas-build.properties.
    BuildMetadataProvider(ClassLoader resourceClassLoader) {
        this(loadBuildProperties(resourceClassLoader), System.getenv(), systemPropertiesAsMap(), garbageCollectorNames());
    }

    // Package-private: tests supply Properties/env/system-properties/GC names directly
    // instead of depending on the classpath resource or the live JVM.
    BuildMetadataProvider(Properties buildProperties, Map<String, String> env,
                           Map<String, String> systemProperties, List<String> garbageCollectors) {
        this.metadata = new BuildMetadata(
                nullIfBlank(buildProperties.getProperty("version")),
                nullIfBlank(buildProperties.getProperty("revision")),
                parseDirty(buildProperties.getProperty("dirty")),
                parseModules(buildProperties.getProperty("modules")),
                new BuildMetadata.Build(
                        resolveBuildType(buildProperties, systemProperties),
                        nullIfBlank(buildProperties.getProperty("variant")),
                        nullIfBlank(buildProperties.getProperty("optimization")),
                        new BuildMetadata.BaseImages(
                                nullIfBlank(env.get("NANOFAAS_BUILD_BASE_IMAGE")),
                                nullIfBlank(env.get("NANOFAAS_RUNTIME_BASE_IMAGE")))),
                new BuildMetadata.Runtime(
                        normalizeArchitecture(systemProperties.get("os.arch")),
                        nullIfBlank(systemProperties.get("os.version")),
                        nullIfBlank(systemProperties.get("java.version")),
                        nullIfBlank(systemProperties.get("java.vm.name")),
                        sortedOrNull(garbageCollectors)));
    }

    BuildMetadata get() {
        return metadata;
    }

    private static Properties loadBuildProperties(ClassLoader resourceClassLoader) {
        Properties properties = new Properties();
        try (InputStream in = resourceClassLoader.getResourceAsStream(PROPERTIES_RESOURCE)) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException | IllegalArgumentException e) {
            LOG.warn("Could not load {}; build metadata fields will be null", PROPERTIES_RESOURCE, e);
        }
        return properties;
    }

    private static Map<String, String> systemPropertiesAsMap() {
        Map<String, String> map = new HashMap<>();
        System.getProperties().forEach((key, value) -> map.put(String.valueOf(key), String.valueOf(value)));
        return map;
    }

    private static List<String> garbageCollectorNames() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(GarbageCollectorMXBean::getName)
                .toList();
    }

    private static String nullIfBlank(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    // null is the third state, not a mistake: "the build did not say". Reporting
    // false for an artifact whose dirty flag is missing or malformed would claim a
    // clean tree nobody verified, which is exactly the claim this endpoint exists
    // to make checkable. Tests assert the null (BuildMetadataProviderTest).
    @SuppressWarnings("java:S2447")
    private static Boolean parseDirty(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if ("true".equalsIgnoreCase(raw)) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return false;
        }
        LOG.warn("Malformed 'dirty' build property value '{}'; reporting dirty as null", raw);
        return null;
    }

    // null distinguishes "the build recorded no module list" from the empty list,
    // which would claim a control plane compiled with no modules at all.
    @SuppressWarnings("java:S1168")
    private static List<String> parseModules(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        List<String> modules = new ArrayList<>();
        for (String module : raw.split(",", -1)) {
            String trimmed = module.trim();
            if (!trimmed.isEmpty()) {
                modules.add(trimmed);
            }
        }
        return sortedOrNull(modules);
    }

    @SuppressWarnings("java:S1168")   // see parseModules: null means unknown, empty means none
    private static List<String> sortedOrNull(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.stream().sorted().toList();
    }

    private static String normalizeArchitecture(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "aarch64", "arm64" -> "arm64";
            case "amd64", "x86_64", "x64" -> "x86_64";
            default -> null;
        };
    }

    // A property-sourced 'type' always wins; the imagecode marker is only a fallback
    // for builds whose generated properties don't carry it, and it's an honest
    // observation (the process really is running as a native image), not a sentinel.
    private static String resolveBuildType(Properties buildProperties, Map<String, String> systemProperties) {
        String fromProperties = nullIfBlank(buildProperties.getProperty("type"));
        if (fromProperties != null) {
            return fromProperties;
        }
        return systemProperties.get("org.graalvm.nativeimage.imagecode") != null ? "native" : null;
    }
}
