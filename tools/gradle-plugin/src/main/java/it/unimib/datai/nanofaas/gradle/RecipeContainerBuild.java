package it.unimib.datai.nanofaas.gradle;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The pure parts of a containerized native build: the Gradle arguments the builder runs with, the docker build command
 * that exports the executable, and the containerd precondition. The container gets explicit -P flags, never -Precipe:
 * the recipe file may live outside the build context.
 */
final class RecipeContainerBuild {

    static final String DOCKERFILE = "tools/native-java/Dockerfile";
    static final String TARGET = "native-executable";
    static final String EMPTY_MAVEN_REPOSITORY = "tools/native-java/empty-maven-repo";
    static final String CONTAINERD_MODULE = "containerd-deployment-provider";
    /** Builder-sizing flags of the invocation, passed into the container too. */
    static final List<String> PASS_THROUGH = List.of("nativeBuildMemory", "nativeParallelism");
    private static final List<String> NATIVE_FLAGS = List.of("nativeOptimization", "nativeGc", "nativeMonitoring");

    private RecipeContainerBuild() {
    }

    /** @param source the report's source node (revision and dirty state), or a null node when Git is unavailable */
    static List<String> gradleArgs(Path recipeSource, JsonNode data, String projectPath, List<String> modules,
                                   JsonNode source, Map<String, String> passThrough) {
        Map<String, Map<String, String>> byProject = RecipeBuildProperties.byProject(data);
        List<String> args = new ArrayList<>(List.of("-PnanofaasBuildType=native"));
        Map<String, String> own = byProject.getOrDefault(projectPath, Map.of());
        NATIVE_FLAGS.stream().filter(own::containsKey).forEach(key -> args.add("-P" + key + "=" + own.get(key)));
        if (projectPath.equals(RecipeBuildProperties.CONTROL_PLANE)) {
            // A blank selector falls back to the default modules; none is the explicit empty selection.
            args.add("-PcontrolPlaneModules=" + (modules.isEmpty() ? "none" : String.join(",", modules)));
            new TreeMap<>(byProject.getOrDefault(RecipeBuildProperties.BUILD_METADATA_PROJECT, Map.of()))
                    .forEach((key, value) -> args.add("-P" + key + "=" + value));
            // The build context has no .git: the host's revision and dirty state go in explicitly.
            if (source != null && source.isObject()) {
                args.add("-PnanofaasBuildRevision=" + source.get("revision").asText());
                if (source.path("dirty").isBoolean()) {
                    args.add("-PnanofaasBuildDirty=" + source.get("dirty").asBoolean());
                }
            }
            if (modules.contains(CONTAINERD_MODULE)) {
                args.add("-PcontainerdMavenLocal=true");
                args.add("-Dmaven.repo.local=/tmp/containerd-m2");
            }
        }
        PASS_THROUGH.stream().filter(passThrough::containsKey)
                .forEach(key -> args.add("-P" + key + "=" + passThrough.get(key)));
        for (String arg : args) {
            if (arg.chars().anyMatch(Character::isWhitespace)) {
                throw RecipeReader.failure(recipeSource, "container builder argument '" + arg + "' contains whitespace;"
                        + " the builder's GRADLE_ARGS is word-split, so the value would be broken apart");
            }
        }
        return List.copyOf(args);
    }

    static List<String> command(String docker, Path rootDir, Path destination, String nativeTask, String nativeBinary,
                                String distribution, List<String> gradleArgs, Path containerdRepository,
                                String aotConfig) {
        List<String> command = new ArrayList<>(List.of(docker, "build", "-f", rootDir.resolve(DOCKERFILE).toString(),
                "--target", TARGET, "--output", "type=local," + csvField("dest=" + destination)));
        command.addAll(builderArguments(rootDir, nativeTask, nativeBinary, distribution, gradleArgs,
                containerdRepository, aotConfig));
        command.add(rootDir.toString());
        return List.copyOf(command);
    }

    /**
     * The builder stage's inputs, shared by the executable export and the multi-architecture recipe-native build.
     * @param aotConfig the control plane's configuration for Spring AOT, or null: base64, because a build argument
     *                  would not keep its quotes and line breaks
     */
    static List<String> builderArguments(Path rootDir, String nativeTask, String nativeBinary, String distribution,
                                         List<String> gradleArgs, Path containerdRepository, String aotConfig) {
        Path repository = containerdRepository != null ? containerdRepository : rootDir.resolve(EMPTY_MAVEN_REPOSITORY);
        List<String> arguments = new ArrayList<>(List.of("--build-context", "containerd_maven_repo=" + repository,
                "--build-arg", "NATIVE_TASK=" + nativeTask,
                "--build-arg", "NATIVE_BINARY=" + nativeBinary,
                "--build-arg", "GRAALVM_DISTRIBUTION=" + distribution,
                "--build-arg", "GRADLE_ARGS=" + String.join(" ", gradleArgs)));
        if (aotConfig != null) {
            arguments.addAll(List.of("--build-arg", "NATIVE_AOT_CONFIG="
                    + Base64.getEncoder().encodeToString(aotConfig.getBytes(StandardCharsets.UTF_8))));
        }
        return List.copyOf(arguments);
    }

    /** docker reads --output as one CSV record: a field holding a comma or a quote is quoted, its quotes doubled. */
    private static String csvField(String field) {
        return field.contains(",") || field.contains("\"") ? "\"" + field.replace("\"", "\"\"") + "\"" : field;
    }

    static void requireContainerdRepository(Path recipeSource, List<String> modules, Object containerdMavenLocal,
                                            String mavenRepoLocal) {
        if (modules.contains(CONTAINERD_MODULE)
                && (!"true".equals(String.valueOf(containerdMavenLocal)) || mavenRepoLocal == null)) {
            throw RecipeReader.failure(recipeSource, "controlPlane.modules: " + CONTAINERD_MODULE
                    + " with builder: container needs -PcontainerdMavenLocal=true and -Dmaven.repo.local=<the staged"
                    + " repository from scripts/bootstrap-containerd-dependencies.sh>");
        }
        if (modules.contains(CONTAINERD_MODULE) && !Files.isDirectory(Path.of(mavenRepoLocal))) {
            throw RecipeReader.failure(recipeSource, "controlPlane.modules: " + CONTAINERD_MODULE + " with builder:"
                    + " container: -Dmaven.repo.local=" + mavenRepoLocal + " is not a directory; stage it with"
                    + " scripts/bootstrap-containerd-dependencies.sh");
        }
    }
}
