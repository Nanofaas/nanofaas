package it.unimib.datai.nanofaas.gradle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The pure parts of the multi-architecture path, which a recipe takes when it sets registry.platforms: the buildx
 * commands, the builder's platforms, and the digests read back after a push. A multi-architecture image cannot live in
 * the classic local image store, so assembly builds into the builder's cache and only publication pushes.
 */
final class RecipeBuildx {

    /** The deploy/native-java/Dockerfile stage that compiles and packages a container-built native image in one build. */
    static final String NATIVE_TARGET = "recipe-native";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DIGEST = "sha256:[0-9a-f]{64}";

    private RecipeBuildx() {
    }

    /** registry.platforms, or null when the recipe takes the classic docker path. */
    static List<String> platforms(JsonNode data) {
        JsonNode platforms = data.path("registry").path("platforms");
        if (!platforms.isArray()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        platforms.forEach(platform -> values.add(platform.asText()));
        return List.copyOf(values);
    }

    static boolean provenance(JsonNode data) {
        return data.path("registry").path("provenance").asBoolean(false);
    }

    /** The platform of an executable compiled on this host, or null for an architecture no image targets. */
    static String hostPlatform(String osArch) {
        return switch (osArch) {
            case "aarch64", "arm64" -> "linux/arm64";
            case "amd64", "x86_64" -> "linux/amd64";
            default -> null;
        };
    }

    /** GraalVM cannot cross-compile: a host-built native image can target only the host's own platform. */
    static String platformProblem(String osArch, List<String> platforms, RecipeTasks.Target target) {
        if (platforms == null || !target.mode().equals("native") || target.image() == null || target.containerBuilt()) {
            return null;
        }
        String host = hostPlatform(osArch);
        if (host != null && platforms.equals(List.of(host))) {
            return null;
        }
        return "registry.platforms " + platforms + " needs an executable for each platform, but builder: host compiles"
                + " only for " + (host == null ? osArch : host) + "; use build.builder: container";
    }

    /** --bootstrap: an inactive builder (created, or stopped) lists no platforms until it is started. */
    static List<String> inspect(String docker, String builder) {
        List<String> command = new ArrayList<>(List.of(docker, "buildx", "inspect", "--bootstrap"));
        if (builder != null) {
            command.addAll(List.of("--builder", builder));
        }
        return List.copyOf(command);
    }

    /** Every platform on the Platforms: lines of docker buildx inspect, one line per node; '*' marks a configured one. */
    static Set<String> builderPlatforms(String inspectOutput) {
        Set<String> platforms = new LinkedHashSet<>();
        for (String line : inspectOutput.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("Platforms:")) {
                Arrays.stream(trimmed.substring("Platforms:".length()).split(","))
                        .map(platform -> platform.strip().replace("*", ""))
                        .filter(platform -> !platform.isEmpty())
                        .forEach(platforms::add);
            }
        }
        return platforms;
    }

    /**
     * @param source       the Dockerfile arguments (-f, --target, --build-context, --build-arg), ending with the context
     * @param metadataFile where --push writes the build metadata, or null to build into the builder's cache only
     */
    static List<String> build(String docker, String builder, List<String> platforms, boolean provenance,
                              String reference, List<String> source, Path metadataFile) {
        List<String> command = new ArrayList<>(List.of(docker, "buildx", "build"));
        if (builder != null) {
            command.addAll(List.of("--builder", builder));
        }
        command.addAll(List.of("--platform", String.join(",", platforms),
                "--provenance=" + (provenance ? "mode=max" : "false"), "-t", reference));
        if (metadataFile != null) {
            command.addAll(List.of("--push", "--metadata-file", metadataFile.toString()));
        }
        command.addAll(source);
        return List.copyOf(command);
    }

    /** Reads back what was pushed, by digest: the reference's repository (a registry port included) without its tag. */
    static List<String> imagetools(String docker, String reference, String digest) {
        return List.of(docker, "buildx", "imagetools", "inspect",
                reference.substring(0, reference.lastIndexOf(':')) + "@" + digest, "--raw");
    }

    /** containerimage.digest from a --metadata-file, or null when it is missing or unreadable. */
    static String pushedDigest(String metadataJson) {
        try {
            JsonNode digest = JSON.readTree(metadataJson).path("containerimage.digest");
            return digest.isTextual() && digest.asText().matches(DIGEST) ? digest.asText() : null;
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    /**
     * Platform to manifest digest, from imagetools inspect --raw of what was pushed: an index (attestation manifests
     * skipped) or, for one platform without provenance, a single manifest. Null when the document cannot be read.
     */
    static Map<String, String> manifests(String raw, String digest, List<String> platforms) {
        JsonNode json;
        try {
            json = JSON.readTree(raw);
        } catch (IOException | RuntimeException exception) {
            return null;
        }
        if (json == null || !json.isObject()) {
            return null;
        }
        Map<String, String> manifests = new LinkedHashMap<>();
        JsonNode entries = json.path("manifests");
        if (!entries.isArray()) {
            // A single manifest, not an index: only one platform can have been built, and the digest is its own.
            if (!json.has("config") || platforms.size() != 1) {
                return null;
            }
            manifests.put(platforms.getFirst(), digest);
            return manifests;
        }
        for (JsonNode entry : entries) {
            if (entry.path("annotations").path("vnd.docker.reference.type").asText().equals("attestation-manifest")) {
                continue;
            }
            // os/architecture only: BuildKit may add a variant (arm64 v8) that the recipe's platforms do not name.
            manifests.put(entry.at("/platform/os").asText() + "/" + entry.at("/platform/architecture").asText(),
                    entry.path("digest").asText());
        }
        return manifests;
    }

    /** True when the pushed manifests are exactly the requested platforms, each with a registry digest. */
    static boolean covers(Map<String, String> manifests, List<String> platforms) {
        return manifests != null && manifests.keySet().equals(Set.copyOf(platforms))
                && manifests.values().stream().allMatch(digest -> digest.matches(DIGEST));
    }
}
