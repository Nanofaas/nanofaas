package it.unimib.datai.nanofaas.gradle;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a recipe tells the build scripts: per-project extra properties read through project.findProperty
 * (the same names the -P flags use), the control plane's build identity, and each native component's
 * effective native-image options. Pure functions of the validated v2 model.
 */
final class RecipeBuildProperties {

    static final String CONTROL_PLANE = ":control-plane";
    static final String BUILD_METADATA_PROJECT = ":control-plane-modules:build-metadata";
    static final String BUILD_METADATA_MODULE = "build-metadata";
    static final String SERVICE_MODE_PROPERTY = "nanofaasRecipeBuildMode";

    /** -P flag -> the recipe field that replaces it. */
    private static final Map<String, String> OWNED_FLAGS = ownedFlags();

    record NativeOptions(String optimization, String gc, List<String> monitoring) {
    }

    record Identity(String variant, String optimization) {
    }

    private RecipeBuildProperties() {
    }

    private static Map<String, String> ownedFlags() {
        Map<String, String> flags = new LinkedHashMap<>();
        flags.put("nativeOptimization", "build.native.optimization");
        flags.put("nativeGc", "build.native.gc");
        flags.put("nativeMonitoring", "build.native.monitoring");
        flags.put("nanofaasBuildVariant", "controlPlane.build.variant");
        flags.put("nanofaasBuildOptimization", "controlPlane.build.native.optimization (derived from jvm.args on the JVM)");
        return flags;
    }

    static Map<String, Map<String, String>> byProject(JsonNode data) {
        Map<String, Map<String, String>> projects = new LinkedHashMap<>();
        putNative(projects, CONTROL_PLANE, data.get("controlPlane"));
        JsonNode functions = data.path("functions");
        for (JsonNode function : functions) {
            String sdk = function.get("sdk").asText();
            if (sdk.equals("java") || sdk.equals("java-lite")) {
                String name = function.get("name").asText();
                putNative(projects, ":functions:java:" + name + (sdk.equals("java-lite") ? "-lite" : ""), function);
            }
        }
        for (JsonNode service : data.path("services")) {
            if (service.get("sdk").asText().equals("java")) {
                String path = ":services:java:" + service.get("name").asText();
                putNative(projects, path, service);
                projects.computeIfAbsent(path, ignored -> new LinkedHashMap<>())
                        .put(SERVICE_MODE_PROPERTY, service.at("/build/mode").asText());
            }
        }
        Identity identity = identity(data);
        if (identity != null) {
            Map<String, String> metadata = projects.computeIfAbsent(BUILD_METADATA_PROJECT, ignored -> new LinkedHashMap<>());
            if (identity.variant() != null) {
                metadata.put("nanofaasBuildVariant", identity.variant());
            }
            metadata.put("nanofaasBuildOptimization", identity.optimization());
        }
        return projects;
    }

    /** Only the fields the recipe sets: the build scripts keep their own defaults for the rest. */
    private static void putNative(Map<String, Map<String, String>> projects, String path, JsonNode component) {
        JsonNode options = component.path("build").path("native");
        if (options.isMissingNode() || options.isEmpty()) {
            return;
        }
        Map<String, String> properties = projects.computeIfAbsent(path, ignored -> new LinkedHashMap<>());
        if (options.has("optimization")) {
            properties.put("nativeOptimization", options.get("optimization").asText());
        }
        if (options.has("gc")) {
            properties.put("nativeGc", options.get("gc").asText());
        }
        if (options.has("monitoring")) {
            List<String> values = new ArrayList<>();
            options.get("monitoring").forEach(value -> values.add(value.asText()));
            properties.put("nativeMonitoring", String.join(",", values));
        }
    }

    /** The options the root build.gradle actually passes to native-image, G1's jfr included. */
    static NativeOptions effectiveNative(JsonNode component) {
        if (!component.at("/build/mode").asText().equals("native")) {
            return null;
        }
        JsonNode options = component.path("build").path("native");
        String gc = options.path("gc").asText("serial");
        List<String> monitoring = new ArrayList<>();
        options.path("monitoring").forEach(value -> monitoring.add(value.asText()));
        if (gc.equals("G1") && !monitoring.contains("jfr")) {
            monitoring.add("jfr");
        }
        return new NativeOptions(options.path("optimization").asText("3"), gc, List.copyOf(monitoring));
    }

    /** Recorded with a variant, or with an explicit native optimization, as -PnativeOptimization alone records it. */
    static Identity identity(JsonNode data) {
        JsonNode build = data.at("/controlPlane/build");
        String variant = build.has("variant") ? build.get("variant").asText() : null;
        JsonNode optimization = build.path("native").path("optimization");
        if (variant == null && optimization.isMissingNode()) {
            return null;
        }
        if (build.get("mode").asText().equals("native")) {
            return new Identity(variant, optimization.asText("3"));
        }
        List<String> args = new ArrayList<>();
        data.at("/controlPlane/jvm/args").forEach(arg -> args.add(arg.asText()));
        return new Identity(variant, jvmTier(args));
    }

    /**
     * nanolab's rule: c1 when the effective options stop tiering at level 1. The control plane's fixed flags and its
     * default tuning never set TieredStopAtLevel, so jvm.args alone decides; the JVM keeps the last value.
     */
    static String jvmTier(List<String> args) {
        String level = null;
        for (String arg : args == null ? List.<String>of() : args) {
            if (arg.startsWith("-XX:TieredStopAtLevel=")) {
                level = arg.substring("-XX:TieredStopAtLevel=".length());
            }
        }
        return "1".equals(level) ? "c1" : "c2";
    }

    static void rejectOwnedFlags(Path source, Set<String> projectProperties) {
        for (Map.Entry<String, String> flag : OWNED_FLAGS.entrySet()) {
            if (projectProperties.contains(flag.getKey())) {
                throw RecipeReader.failure(source, "-P" + flag.getKey() + " cannot be combined with -Precipe; set "
                        + flag.getValue() + " in the recipe");
            }
        }
    }

    static void requireBuildMetadata(Path source, JsonNode data, List<String> modules) {
        if (modules.contains(BUILD_METADATA_MODULE)) {
            return;
        }
        JsonNode build = data.at("/controlPlane/build");
        String field = build.has("variant") ? "controlPlane.build.variant"
                : build.path("native").has("optimization") ? "controlPlane.build.native.optimization" : null;
        if (field != null) {
            throw RecipeReader.failure(source, field + " requires the build-metadata module in controlPlane.modules");
        }
    }
}
