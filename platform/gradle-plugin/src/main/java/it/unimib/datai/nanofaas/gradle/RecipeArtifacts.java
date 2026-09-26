package it.unimib.datai.nanofaas.gradle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Exec;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.process.ExecOperations;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import javax.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stages the Java outputs of a resolved recipe, writes their runtime files, builds the images and writes
 * {@code distribution.json}. Everything under {@code build/recipes/<name>/} is regenerated on each assembly.
 */
final class RecipeArtifacts {

    static final String REPORT = "distribution.json";

    /** Default tuning group of platform/control-plane/Dockerfile (JVM_TUNING); explicit jvm.args replace it. */
    private static final List<String> CONTROL_PLANE_TUNING = List.of("-XX:+UseSerialGC");

    /**
     * Control-plane flags from the ENTRYPOINT of platform/control-plane/Dockerfile (kept in step by hand). They head
     * jvm.options so that the recipe's jvm.args, later on the command line, override them: the JVM keeps the last value.
     */
    private static final List<String> CONTROL_PLANE_FLAGS = List.of("-XX:MaxRAMPercentage=70", "-Xss256k",
            "-Dspring.main.banner-mode=off", "-Dspring.jmx.enabled=false", "-Dspring.devtools.restart.enabled=false",
            "-Dmanagement.endpoints.enabled-by-default=false");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PUSH_DIGEST = Pattern.compile("(?m)^(\\S+): digest: (sha256:[0-9a-f]{64}) size: \\d+$");

    /** Gives a plain task action the injected {@link ExecOperations} service. */
    public interface Services {
        @Inject
        ExecOperations getExec();
    }

    private RecipeArtifacts() {
    }

    static void register(Project root, RecipeReader.Document recipe, List<RecipeTasks.Target> targets,
                         List<String> modules) {
        Path rootDir = realPath(root.getRootDir().toPath());
        Path output = RecipeOutput.resolve(rootDir, root.findProperty("recipeOutput"),
                root.getLayout().getBuildDirectory().dir("recipes/" + recipe.data().get("name").asText())
                        .get().getAsFile().toPath());
        Object dockerProperty = root.findProperty("recipeDocker");
        String docker = dockerProperty == null ? "docker" : dockerProperty.toString();
        Services services = root.getObjects().newInstance(Services.class);
        List<String> platforms = RecipeBuildx.platforms(recipe.data());
        boolean provenance = RecipeBuildx.provenance(recipe.data());
        Object builderProperty = root.findProperty("recipeBuilder");
        String builder = builderProperty == null ? null : builderProperty.toString();
        if (builder != null && platforms == null) {
            throw RecipeReader.failure(recipe.source(), "-PrecipeBuilder selects the buildx builder of"
                    + " registry.platforms, which this recipe does not set");
        }

        TaskProvider<Task> clean = root.getTasks().register("cleanRecipe", task -> {
            task.setDescription("Empties the recipe output directory it owns and marks it; refuses any other directory.");
            task.doLast(ignored -> RecipeOutput.claim(recipe.source(), output, rootDir));
        });
        if (platforms != null && targets.stream().anyMatch(target -> target.image() != null)) {
            TaskProvider<Task> check = root.getTasks().register("checkRecipeBuilder", task -> {
                task.setDescription("Checks that the buildx builder can build every platform of registry.platforms.");
                task.doLast(ignored -> requireBuilderPlatforms(services.getExec(), docker, builder, platforms));
            });
            // Before the output is emptied, and so before anything compiles (compiles run after cleanRecipe).
            clean.configure(task -> task.dependsOn(check));
        }
        TaskProvider<Sync> stage = root.getTasks().register("stageRecipe", Sync.class, sync -> {
            sync.dependsOn(clean);
            sync.into(output);
            sync.preserve(filter -> filter.include(RecipeOutput.MARKER));
            // Sync deletes whatever it did not copy: check ownership here too, for -x cleanRecipe.
            sync.doFirst(ignored -> RecipeOutput.requireOwned(recipe.source(), output, rootDir));
            targets.stream().filter(target -> target.task() != null && !target.containerBuilt())
                    .forEach(target -> stageJava(root, sync, target));
        });
        // Not a doLast of the Sync: with every Java component built in the container, the Sync has no source, is
        // skipped as NO-SOURCE, and would take the control plane's config/recipe.yaml with it.
        TaskProvider<Task> runtimeFiles = root.getTasks().register("writeRecipeRuntimeFiles", task -> {
            task.setDescription("Writes jvm.options, launch.args and config/recipe.yaml into the staged components.");
            task.dependsOn(stage);
            // A NO-SOURCE Sync also skips its own ownership check, so -x cleanRecipe must be refused here too.
            task.doFirst(ignored -> RecipeOutput.requireOwned(recipe.source(), output, rootDir));
            task.doLast(ignored -> targets.stream().filter(target -> target.task() != null)
                    .forEach(target -> writeRuntimeFiles(output.resolve(target.stagingDir()), target, recipe.data())));
        });
        // A compile that fails after the clean must not leave the previous distribution's report behind.
        targets.stream().filter(target -> target.task() != null && !target.containerBuilt())
                .forEach(target -> producer(root, target).configure(task -> task.mustRunAfter(clean)));

        Map<String, String> imageIds = new java.util.concurrent.ConcurrentHashMap<>();
        Map<String, String> passThrough = new java.util.LinkedHashMap<>();
        for (String key : RecipeContainerBuild.PASS_THROUGH) {
            Object value = root.findProperty(key);
            if (value != null) {
                passThrough.put(key, value.toString());
            }
        }
        String mavenRepoLocal = System.getProperty("maven.repo.local");
        if (targets.stream().anyMatch(target -> target.controlPlane() && target.containerBuilt())) {
            RecipeContainerBuild.requireContainerdRepository(recipe.source(), modules,
                    root.findProperty("containerdMavenLocal"), mavenRepoLocal);
        }
        Path containerdRepository = modules.contains(RecipeContainerBuild.CONTAINERD_MODULE) && mavenRepoLocal != null
                ? Path.of(mavenRepoLocal) : null;
        Map<RecipeTasks.Target, TaskProvider<Exec>> containerBuilds = new java.util.LinkedHashMap<>();
        for (RecipeTasks.Target target : targets) {
            if (!target.containerBuilt()) {
                continue;
            }
            String projectPath = target.task().substring(0, target.task().lastIndexOf(':'));
            // Checked now: a whitespace value must fail before anything is built, not inside the builder.
            RecipeContainerBuild.gradleArgs(recipe.source(), recipe.data(), projectPath, modules, NullNode.getInstance(),
                    passThrough);
            if (platforms != null && target.image() != null) {
                continue; // recipe-native compiles it inside the image build, once per platform
            }
            containerBuilds.put(target, root.getTasks().register("recipeNativeBuild" + containerBuilds.size(), Exec.class,
                    exec -> {
                        exec.setDescription("Compiles " + target.name() + " natively inside the builder container");
                        exec.dependsOn(runtimeFiles);
                        exec.environment("DOCKER_BUILDKIT", "1");
                        exec.setWorkingDir(rootDir.toFile());
                        exec.doFirst(ignored -> exec.commandLine(RecipeContainerBuild.command(docker, rootDir,
                                output.resolve(target.stagingDir()), target.task(), nativeBinary(root, target),
                                target.nativeOptions().distribution(),
                                RecipeContainerBuild.gradleArgs(recipe.source(), recipe.data(), projectPath, modules,
                                        source(services.getExec(), rootDir, output), passThrough),
                                containerdRepository)));
                    }));
        }
        List<TaskProvider<Exec>> images = new ArrayList<>();
        Map<String, Function<Path, List<String>>> buildxCommands = new LinkedHashMap<>();
        for (RecipeTasks.Target target : targets) {
            if (target.image() == null) {
                continue;
            }
            if (platforms == null) {
                images.add(root.getTasks().register("recipeImage" + images.size(), Exec.class, exec -> {
                    exec.setDescription("Builds " + target.image());
                    exec.dependsOn(runtimeFiles);
                    if (containerBuilds.containsKey(target)) {
                        exec.dependsOn(containerBuilds.get(target));
                    }
                    exec.commandLine(dockerBuild(docker, rootDir, output, target));
                    exec.doLast(ignored -> imageIds.put(target.image(),
                            imageId(services.getExec(), docker, target.image())));
                }));
                continue;
            }
            Function<Path, List<String>> command = metadata -> RecipeBuildx.build(docker, builder, platforms,
                    provenance, target.image(), buildxSource(root, recipe, modules, services, rootDir, output, target,
                            passThrough, containerdRepository), metadata);
            buildxCommands.put(target.image(), command);
            images.add(root.getTasks().register("recipeImage" + images.size(), Exec.class, exec -> {
                exec.setDescription("Builds " + target.image() + " for " + String.join(", ", platforms));
                exec.dependsOn(runtimeFiles);
                exec.setWorkingDir(rootDir.toFile());
                // Set at execution: a container-built native image takes the source revision then.
                exec.doFirst(ignored -> exec.commandLine(command.apply(null)));
            }));
        }

        root.getTasks().named("assembleRecipe", task -> {
            task.dependsOn(runtimeFiles, images, containerBuilds.values());
            task.doLast(ignored -> writeReport(output.resolve(REPORT),
                    report(recipe, targets, modules, source(services.getExec(), rootDir, output), imageIds)));
        });
        root.getTasks().named("publishRecipe", task -> {
            task.dependsOn("assembleRecipe");
            task.doLast(ignored -> publish(services.getExec(), docker, output.resolve(REPORT)));
        });
        // Checked once the graph is known and before any task runs, so a doomed publish builds nothing.
        root.getGradle().getTaskGraph().whenReady(graph -> {
            if (graph.hasTask(root.getPath().equals(":") ? ":publishRecipe" : root.getPath() + ":publishRecipe")) {
                if (recipe.data().path("registry").isMissingNode()) {
                    throw RecipeReader.failure(recipe.source(), "publishRecipe requires a registry section");
                }
                if (targets.stream().allMatch(target -> target.image() == null)) {
                    throw RecipeReader.failure(recipe.source(), "publishRecipe found no images: add container.image");
                }
            }
        });
    }

    /**
     * Pushes each image in report order, rewriting the report after every push so that a later failure never hides
     * an earlier success. No retry and no rollback: a registry offers no transaction across images.
     */
    static void publish(ExecOperations exec, String docker, Path reportFile) {
        ObjectNode report;
        try {
            report = (ObjectNode) JSON.readTree(reportFile.toFile());
        } catch (IOException exception) {
            throw new GradleException("Cannot read " + reportFile + " (" + exception + ")", exception);
        }
        List<String> published = new ArrayList<>();
        for (JsonNode component : report.get("components")) {
            if (!(component.get("image") instanceof ObjectNode image)) {
                continue;
            }
            String reference = image.get("reference").asText();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            int exit = exec.exec(spec -> {
                spec.commandLine(docker, "push", reference);
                spec.setStandardOutput(output);
                spec.setIgnoreExitValue(true);
            }).getExitValue();
            System.out.print(output.toString(StandardCharsets.UTF_8));
            if (exit != 0) {
                image.put("status", "failed");
                writeReport(reportFile, report);
                throw new GradleException("docker push " + reference + " failed (exit " + exit + "); already published: "
                        + published);
            }
            String digest = pushedDigest(output.toString(StandardCharsets.UTF_8), reference);
            if (digest == null) {
                digest = repoDigest(exec, docker, reference);
            }
            image.put("status", digest == null ? "published-unverified" : "published");
            image.put("digest", digest);
            try {
                writeReport(reportFile, report);
            } catch (GradleException exception) {
                throw new GradleException(reference + " was pushed, but " + reportFile + " could not record it; "
                        + "already published before it: " + published, exception);
            }
            if (digest == null) {
                throw new GradleException(reference + " was pushed, but its registry digest could not be determined"
                        + " (recorded as published-unverified); already published before it: " + published);
            }
            published.add(reference + "@" + digest);
        }
    }

    /** The manifest digest docker push prints for this tag ("<tag>: digest: sha256:... size: N"), never an image ID. */
    static String pushedDigest(String pushOutput, String reference) {
        String tag = reference.substring(reference.lastIndexOf(':') + 1);
        Matcher matcher = PUSH_DIGEST.matcher(pushOutput);
        while (matcher.find()) {
            if (matcher.group(1).equals(tag)) {
                return matcher.group(2);
            }
        }
        return null;
    }

    /** Falls back to the local RepoDigests entry of this reference's repository, when exactly one digest matches. */
    private static String repoDigest(ExecOperations exec, String docker, String reference) {
        String repository = reference.substring(0, reference.lastIndexOf(':')) + "@";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            exec.exec(spec -> {
                spec.commandLine(docker, "image", "inspect", "--format", "{{json .RepoDigests}}", reference);
                spec.setStandardOutput(output);
                spec.setIgnoreExitValue(true);
            });
            List<String> digests = new ArrayList<>();
            JSON.readTree(output.toString(StandardCharsets.UTF_8)).forEach(entry -> {
                if (entry.asText().startsWith(repository)) {
                    digests.add(entry.asText().substring(repository.length()));
                }
            });
            return digests.stream().distinct().count() == 1 ? digests.getFirst() : null;
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    /** The local image ID, so an image that is built but never pushed can still be pinned. */
    static String imageId(ExecOperations exec, String docker, String reference) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int exit = exec.exec(spec -> {
            spec.commandLine(docker, "image", "inspect", "--format", "{{.Id}}", reference);
            spec.setStandardOutput(output);
            spec.setIgnoreExitValue(true);
        }).getExitValue();
        String id = output.toString(StandardCharsets.UTF_8).trim();
        if (exit != 0 || id.isEmpty()) {
            throw new GradleException("docker image inspect " + reference + " did not return an image ID (exit " + exit
                    + "); an image that cannot be pinned is not a usable result");
        }
        return id;
    }

    /** The executable the builder copies out: the host project's nativeCompile output, relative to the repository. */
    private static String nativeBinary(Project root, RecipeTasks.Target target) {
        RegularFile file = (RegularFile) ((Provider<?>) producer(root, target).get().property("outputFile")).get();
        return root.getRootDir().toPath().relativize(file.getAsFile().toPath()).toString().replace('\\', '/');
    }

    private static TaskProvider<Task> producer(Project root, RecipeTasks.Target target) {
        int separator = target.task().lastIndexOf(':');
        return root.project(target.task().substring(0, separator)).getTasks().named(target.task().substring(separator + 1));
    }

    /** Copies only the declared outputs of the selected task: a Boot jar, the java-lite classpath or the executable. */
    private static void stageJava(Project root, Sync sync, RecipeTasks.Target target) {
        TaskProvider<Task> producer = producer(root, target);
        sync.dependsOn(producer);
        if (target.mode().equals("native")) {
            // BuildNativeImageTask.getOutputFile(): the executable, without the rest of the output directory.
            Provider<RegularFile> executable = producer.map(task -> (RegularFile) ((Provider<?>) task.property("outputFile")).get());
            sync.from(executable, spec -> spec.into(target.stagingDir()).rename(name -> "application"));
        } else if (target.sdk().equals("java-lite")) {
            sync.from(producer, spec -> spec.into(target.stagingDir()).include("lib/**"));
        } else {
            sync.from(producer, spec -> spec.into(target.stagingDir()).rename(name -> "app.jar"));
        }
    }

    /** jvm.options and launch.args are JVM @argfiles; config/recipe.yaml is loaded through spring.config.additional-location. */
    static void writeRuntimeFiles(Path directory, RecipeTasks.Target target, JsonNode recipe) {
        try {
            Files.createDirectories(directory);
            if (target.mode().equals("jvm")) {
                List<String> options = new ArrayList<>(target.controlPlane() ? CONTROL_PLANE_FLAGS : List.of());
                options.addAll(target.jvmArgs() != null ? target.jvmArgs()
                        : target.controlPlane() ? CONTROL_PLANE_TUNING : List.of());
                Files.writeString(directory.resolve("jvm.options"), argfile(options));
                Files.writeString(directory.resolve("launch.args"), argfile(target.mainClass() != null
                        ? List.of("-cp", "lib/*", target.mainClass()) : List.of("-jar", "app.jar")));
            }
            JsonNode config = recipe.path("controlPlane").path("config");
            if (target.controlPlane() && !config.isMissingNode()) {
                DumperOptions options = new DumperOptions();
                options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
                Files.createDirectories(directory.resolve("config"));
                Files.writeString(directory.resolve("config/recipe.yaml"),
                        new Yaml(options).dump(JSON.convertValue(config, Map.class)));
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** One quoted argument per line: the launcher then keeps spaces, quotes and backslashes literally. */
    static String argfile(List<String> arguments) {
        StringBuilder text = new StringBuilder();
        arguments.forEach(argument -> text.append('"')
                .append(argument.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"\n"));
        return text.toString();
    }

    /** Java images build from their staged directory (the repository .dockerignore hides build/); others from the repository. */
    static List<String> dockerBuild(String docker, Path rootDir, Path output, RecipeTasks.Target target) {
        return List.of(docker, "build", "-f", dockerfile(rootDir, target).toString(), "-t", target.image(),
                context(rootDir, output, target).toString());
    }

    private static Path dockerfile(Path rootDir, RecipeTasks.Target target) {
        return target.dockerfile() != null ? rootDir.resolve(target.dockerfile())
                : rootDir.resolve("deploy/recipes/Dockerfile." + target.mode());
    }

    private static Path context(Path rootDir, Path output, RecipeTasks.Target target) {
        return target.dockerfile() != null ? rootDir.resolve(target.contextDir()) : output.resolve(target.stagingDir());
    }

    /** What follows -t in a buildx image build: the Dockerfile arguments, ending with the build context. */
    private static List<String> buildxSource(Project root, RecipeReader.Document recipe, List<String> modules,
                                             Services services, Path rootDir, Path output, RecipeTasks.Target target,
                                             Map<String, String> passThrough, Path containerdRepository) {
        if (!target.containerBuilt()) {
            return List.of("-f", dockerfile(rootDir, target).toString(), context(rootDir, output, target).toString());
        }
        String projectPath = target.task().substring(0, target.task().lastIndexOf(':'));
        List<String> arguments = new ArrayList<>(List.of("-f", rootDir.resolve(RecipeContainerBuild.DOCKERFILE).toString(),
                "--target", RecipeBuildx.NATIVE_TARGET, "--build-context", "recipe=" + output.resolve(target.stagingDir())));
        arguments.addAll(RecipeContainerBuild.builderArguments(rootDir, target.task(), nativeBinary(root, target),
                target.nativeOptions().distribution(), RecipeContainerBuild.gradleArgs(recipe.source(), recipe.data(),
                        projectPath, modules, source(services.getExec(), rootDir, output), passThrough),
                containerdRepository));
        arguments.add(rootDir.toString());
        return arguments;
    }

    /** Fails unless the buildx builder lists every requested platform: before the output is emptied, nothing built. */
    static void requireBuilderPlatforms(ExecOperations exec, String docker, String builder, List<String> platforms) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int exit = exec.exec(spec -> {
            spec.commandLine(RecipeBuildx.inspect(docker, builder));
            spec.setStandardOutput(output);
            spec.setIgnoreExitValue(true);
        }).getExitValue();
        String name = builder == null ? "the current buildx builder" : "buildx builder " + builder;
        if (exit != 0) {
            throw new GradleException("docker buildx inspect of " + name + " failed (exit " + exit
                    + "); registry.platforms needs docker buildx");
        }
        Set<String> available = RecipeBuildx.builderPlatforms(output.toString(StandardCharsets.UTF_8));
        List<String> missing = platforms.stream().filter(platform -> !available.contains(platform)).toList();
        if (!missing.isEmpty()) {
            throw new GradleException(name + " cannot build " + missing + " (it lists " + available + "). Select"
                    + " another with -PrecipeBuilder=<name>, add a node for them (docker buildx create --append), or"
                    + " install QEMU emulation (docker run --privileged --rm tonistiigi/binfmt --install all);"
                    + " native images under emulation are very slow");
        }
    }

    static ObjectNode report(RecipeReader.Document recipe, List<RecipeTasks.Target> targets, List<String> modules,
                             JsonNode source, Map<String, String> imageIds) {
        ObjectNode report = JSON.createObjectNode();
        report.put("schemaVersion", 2);
        // recipe.schemaVersion is the file's own version; the report's describes this document.
        report.putObject("recipe").put("name", recipe.data().get("name").asText()).put("sha256", recipe.sourceSha256())
                .put("schemaVersion", recipe.declaredVersion());
        report.put("tag", recipe.effectiveTag());
        report.set("source", source);
        ArrayNode moduleNodes = report.putArray("modules");
        modules.forEach(moduleNodes::add);
        RecipeBuildProperties.Identity identity = RecipeBuildProperties.identity(recipe.data());
        ArrayNode components = report.putArray("components");
        for (RecipeTasks.Target target : targets) {
            ObjectNode component = components.addObject().put("kind", target.kind()).put("name", target.name())
                    .put("sdk", target.sdk()).put("mode", target.mode()).put("artifact", target.stagingDir());
            if (target.controlPlane() && identity != null) {
                if (identity.variant() != null) {
                    component.put("variant", identity.variant());
                }
                component.put("optimization", identity.optimization());
            }
            if (target.nativeOptions() != null) {
                ObjectNode options = component.putObject("native")
                        .put("optimization", target.nativeOptions().optimization())
                        .put("gc", target.nativeOptions().gc());
                ArrayNode monitoring = options.putArray("monitoring");
                target.nativeOptions().monitoring().forEach(monitoring::add);
                options.put("builder", target.nativeOptions().builder());
                if (target.nativeOptions().distribution() != null) {
                    options.put("distribution", target.nativeOptions().distribution());
                }
            }
            if (target.image() == null) {
                component.putNull("image");
            } else {
                ObjectNode image = component.putObject("image").put("reference", target.image()).put("status", "built");
                List<String> platforms = RecipeBuildx.platforms(recipe.data());
                if (platforms == null) {
                    image.put("id", imageIds.get(target.image()));
                } else {
                    // No local image on the buildx path: publication records the digests instead.
                    ArrayNode platformNodes = image.putArray("platforms");
                    platforms.forEach(platformNodes::add);
                    image.put("provenance", RecipeBuildx.provenance(recipe.data()));
                }
            }
        }
        return report;
    }

    /** Git revision and dirty state, or null when Git or the repository is unavailable: never an invented clean state. */
    static JsonNode source(ExecOperations exec, Path rootDir, Path output) {
        String revision = git(exec, rootDir, "rev-parse", "HEAD");
        if (revision == null) {
            return NullNode.getInstance();
        }
        // Untracked files count: an untracked function directory can be built into an image. The assembly's own
        // output does not, when -PrecipeOutput puts it inside the repository.
        Path realOutput = output.toAbsolutePath().normalize();
        String status = realOutput.startsWith(rootDir) && !realOutput.equals(rootDir)
                ? git(exec, rootDir, "status", "--porcelain", "--", ".",
                        ":(exclude)" + rootDir.relativize(realOutput).toString().replace('\\', '/'))
                : git(exec, rootDir, "status", "--porcelain");
        ObjectNode source = JSON.createObjectNode().put("revision", revision);
        return status == null ? source.putNull("dirty") : source.put("dirty", !status.isEmpty());
    }

    private static String git(ExecOperations exec, Path directory, String... arguments) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        List<String> command = new ArrayList<>(List.of("git", "-C", directory.toString()));
        command.addAll(List.of(arguments));
        try {
            int exit = exec.exec(spec -> {
                spec.commandLine(command);
                spec.setStandardOutput(output);
                spec.setErrorOutput(OutputStream.nullOutputStream());
                spec.setIgnoreExitValue(true);
            }).getExitValue();
            return exit == 0 ? output.toString(StandardCharsets.UTF_8).trim() : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /** Writes a sibling temporary file and moves it over the report, atomically where the file system allows. */
    static void writeReport(Path file, JsonNode report) {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(temporary, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new GradleException("Cannot write " + file + " (" + exception + ")", exception);
        }
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
