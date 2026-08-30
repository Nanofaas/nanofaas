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
import java.util.function.Supplier;

/**
 * Resolves this artifact's {@link BuildMetadata} once at construction from the
 * generated build properties, the environment, and JVM introspection, then
 * hands back the same immutable instance on every call. Never touches Docker
 * or Kubernetes, and never exposes raw JVM arguments.
 */
final class BuildMetadataProvider implements Supplier<BuildMetadata> {

    private static final String PROPERTIES_RESOURCE = "META-INF/nanofaas-build.properties";
    private static final Logger LOG = LoggerFactory.getLogger(BuildMetadataProvider.class);

    private final BuildMetadata metadata;

    BuildMetadataProvider() {
        this(loadBuildProperties(), System.getenv(), systemPropertiesAsMap(), garbageCollectorNames());
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
                        nullIfBlank(buildProperties.getProperty("type")),
                        nullIfBlank(buildProperties.getProperty("variant")),
                        nullIfBlank(buildProperties.getProperty("optimization")),
                        new BuildMetadata.BaseImages(
                                nullIfBlank(env.get("NANOFAAS_BUILD_BASE_IMAGE")),
                                nullIfBlank(env.get("NANOFAAS_RUNTIME_BASE_IMAGE")))),
                new BuildMetadata.Runtime(
                        normalizeArchitecture(systemProperties.get("os.arch")),
                        nullIfBlank(systemProperties.get("os.version")),
                        nullIfBlank(systemProperties.get("java.version")),
                        resolveVm(systemProperties),
                        sortedOrNull(garbageCollectors)));
    }

    @Override
    public BuildMetadata get() {
        return metadata;
    }

    private static Properties loadBuildProperties() {
        Properties properties = new Properties();
        try (InputStream in = BuildMetadataProvider.class.getClassLoader().getResourceAsStream(PROPERTIES_RESOURCE)) {
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

    private static Boolean parseDirty(String raw) {
        return raw == null || raw.isBlank() ? null : Boolean.parseBoolean(raw);
    }

    private static List<String> parseModules(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        List<String> modules = new ArrayList<>();
        for (String module : raw.split(",")) {
            String trimmed = module.trim();
            if (!trimmed.isEmpty()) {
                modules.add(trimmed);
            }
        }
        return sortedOrNull(modules);
    }

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

    // ponytail: java.vm.name is already accurate for a native image at real runtime;
    // this fallback only covers unit tests that don't set it explicitly.
    private static String resolveVm(Map<String, String> systemProperties) {
        String vmName = nullIfBlank(systemProperties.get("java.vm.name"));
        if (vmName != null) {
            return vmName;
        }
        return systemProperties.get("org.graalvm.nativeimage.imagecode") != null ? "GraalVM Native Image" : null;
    }
}
