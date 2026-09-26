package it.unimib.datai.nanofaas.gradle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The assembly output directory. An assembly deletes and regenerates it, so it only takes a directory that is absent,
 * empty, or visibly one of its own: a valid ownership marker, or a report of an earlier assembly.
 */
final class RecipeOutput {

    static final String MARKER = ".nanofaas-recipe-output";
    static final String MARKER_CONTENT = "nanofaas-recipe-output-v1\n";

    private RecipeOutput() {
    }

    /** {@code -PrecipeOutput} resolved against the repository root, like -Precipe; else the default. */
    static Path resolve(Path rootDir, Object property, Path defaultDir) {
        return property == null ? defaultDir : rootDir.resolve(property.toString()).toAbsolutePath().normalize();
    }

    static void claim(Path source, Path output, Path rootDir) {
        Path real = requireOwned(source, output, rootDir);
        try {
            if (Files.isDirectory(output)) {
                deleteContents(real);
            }
            Files.createDirectories(output);
            Files.writeString(output.resolve(MARKER), MARKER_CONTENT, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Fails unless the directory may be emptied: absent, empty, or visibly an assembly output. Every task that deletes
     * inside it checks this, so excluding cleanRecipe (-x) cannot route around the rule. Returns the real path.
     */
    static Path requireOwned(Path source, Path output, Path rootDir) {
        Path real = realPathOfExistingPrefix(output);
        if (rootDir.startsWith(real)) {
            throw RecipeReader.failure(source, "-PrecipeOutput: " + output + " is the repository or one of its ancestors");
        }
        if (Files.exists(output) && !Files.isDirectory(output)) {
            throw RecipeReader.failure(source, "-PrecipeOutput: " + output + " exists and is not a directory");
        }
        if (Files.isDirectory(output) && !isEmpty(output) && !owned(output)) {
            throw RecipeReader.failure(source, "-PrecipeOutput: " + output + " is not empty and holds no recipe output"
                    + " (no valid " + MARKER + " and no distribution.json of an earlier assembly); nothing was deleted");
        }
        return real;
    }

    /** What an assembly writes at the top of its output; a report counts as evidence only among these. */
    private static final Set<String> ASSEMBLY_ENTRIES = Set.of(RecipeArtifacts.REPORT, RecipeArtifacts.REPORT + ".tmp",
            MARKER, "control-plane", "functions", "services");

    private static boolean owned(Path dir) {
        try {
            Path marker = dir.resolve(MARKER);
            if (Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                    && Files.readString(marker, StandardCharsets.UTF_8).equals(MARKER_CONTENT)) {
                return true;
            }
            Path report = dir.resolve(RecipeArtifacts.REPORT);
            if (!Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            // A report copied next to other files (an evidence directory, say) was not written by an assembly there.
            try (Stream<Path> entries = Files.list(dir)) {
                if (!entries.allMatch(entry -> ASSEMBLY_ENTRIES.contains(entry.getFileName().toString()))) {
                    return false;
                }
            }
            JsonNode json = new ObjectMapper().readTree(report.toFile());
            return validReport(json);
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    /** Validate the required report structure before using a legacy report as evidence of ownership. */
    static boolean validReport(JsonNode json) {
        if (json == null || !json.isObject() || !json.path("schemaVersion").isIntegralNumber()
                || !json.path("schemaVersion").canConvertToInt()) {
            return false;
        }
        int version = json.get("schemaVersion").intValue();
        JsonNode recipe = json.path("recipe");
        if ((version != 1 && version != 2) || !recipe.isObject()
                || !matches(recipe.path("name"), "[a-z0-9]+(?:-[a-z0-9]+)*")
                || !matches(recipe.path("sha256"), "[0-9a-f]{64}")
                || !matches(json.path("tag"), "[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}")) {
            return false;
        }
        JsonNode source = json.path("source");
        if (!source.isNull() && !(source.isObject()
                && matches(source.path("revision"), "[0-9a-f]{40}|[0-9a-f]{64}")
                && (source.path("dirty").isBoolean() || source.path("dirty").isNull()))) {
            return false;
        }
        JsonNode modules = json.path("modules");
        if (!modules.isArray()) {
            return false;
        }
        for (JsonNode module : modules) {
            if (!matches(module, "[a-z0-9]+(?:-[a-z0-9]+)*")) {
                return false;
            }
        }
        JsonNode components = json.path("components");
        if (!components.isArray() || components.isEmpty()) {
            return false;
        }
        for (JsonNode component : components) {
            if (!component.isObject() || !matches(component.path("name"), "[a-z0-9]+(?:-[a-z0-9]+)*")
                    || !matches(component.path("sdk"), "java|java-lite|python|javascript|go|bash|dockerfile")
                    || !matches(component.path("mode"), "jvm|native|container")
                    || (version == 2 && !matches(component.path("kind"), "control-plane|function|service"))) {
                return false;
            }
            JsonNode artifact = component.path("artifact");
            boolean containerOnly = component.path("mode").asText().equals("container");
            if (containerOnly ? !artifact.isNull() : !artifact.isTextual() || artifact.asText().isBlank()) {
                return false;
            }
            JsonNode image = component.path("image");
            if (image.isNull()) {
                if (containerOnly) {
                    return false;
                }
                continue;
            }
            if (!image.isObject() || !image.path("reference").isTextual() || image.path("reference").asText().isBlank()
                    || !image.path("status").isTextual()
                    || !Set.of("built", "published", "published-unverified", "failed").contains(image.path("status").asText())) {
                return false;
            }
            JsonNode platforms = image.path("platforms");
            // The buildx path keeps no local image, hence no id: its images are pinned by digest once published.
            boolean buildx = platforms.isArray() && !platforms.isEmpty();
            boolean published = image.path("status").asText().equals("published");
            if ((version == 2 && !buildx && !matches(image.path("id"), "sha256:[0-9a-f]{64}"))
                    || (published && !matches(image.path("digest"), "sha256:[0-9a-f]{64}"))
                    || (buildx && published && !coversPlatforms(image.path("manifests"), platforms))) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(JsonNode value, String pattern) {
        return value.isTextual() && value.asText().matches(pattern);
    }

    /** A published multi-architecture image records one digest for exactly each of its platforms. */
    private static boolean coversPlatforms(JsonNode manifests, JsonNode platforms) {
        if (!manifests.isObject() || manifests.size() != platforms.size()) {
            return false;
        }
        for (JsonNode platform : platforms) {
            if (!platform.isTextual() || !matches(manifests.path(platform.asText()), "sha256:[0-9a-f]{64}")) {
                return false;
            }
        }
        return true;
    }

    private static boolean isEmpty(Path dir) {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findAny().isEmpty();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Deletes what is inside {@code dir}; symbolic links are removed, never followed. */
    private static void deleteContents(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                if (!directory.equals(dir)) {
                    Files.delete(directory);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** The real path of the longest existing prefix, followed by the rest: symlinks cannot hide the repository. */
    private static Path realPathOfExistingPrefix(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        try {
            return existing == null ? absolute : existing.toRealPath().resolve(existing.relativize(absolute));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
