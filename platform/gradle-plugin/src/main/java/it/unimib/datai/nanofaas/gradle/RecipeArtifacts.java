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
import java.util.List;
import java.util.Map;
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

        TaskProvider<Task> clean = root.getTasks().register("cleanRecipe", task -> {
            task.setDescription("Empties the recipe output directory it owns and marks it; refuses any other directory.");
            task.doLast(ignored -> RecipeOutput.claim(recipe.source(), output, rootDir));
        });
        TaskProvider<Sync> stage = root.getTasks().register("stageRecipe", Sync.class, sync -> {
            sync.dependsOn(clean);
            sync.into(output);
            sync.preserve(filter -> filter.include(RecipeOutput.MARKER));
            targets.stream().filter(target -> target.task() != null).forEach(target -> stageJava(root, sync, target));
            sync.doLast(ignored -> targets.stream().filter(target -> target.task() != null)
                    .forEach(target -> writeRuntimeFiles(output.resolve(target.stagingDir()), target, recipe.data())));
        });
        // A compile that fails after the clean must not leave the previous distribution's report behind.
        targets.stream().filter(target -> target.task() != null)
                .forEach(target -> producer(root, target).configure(task -> task.mustRunAfter(clean)));

        Services services = root.getObjects().newInstance(Services.class);
        Map<String, String> imageIds = new java.util.concurrent.ConcurrentHashMap<>();
        List<TaskProvider<Exec>> images = new ArrayList<>();
        for (RecipeTasks.Target target : targets) {
            if (target.image() != null) {
                images.add(root.getTasks().register("recipeImage" + images.size(), Exec.class, exec -> {
                    exec.setDescription("Builds " + target.image());
                    exec.dependsOn(stage);
                    exec.commandLine(dockerBuild(docker, rootDir, output, target));
                    exec.doLast(ignored -> imageIds.put(target.image(),
                            imageId(services.getExec(), docker, target.image())));
                }));
            }
        }

        root.getTasks().named("assembleRecipe", task -> {
            task.dependsOn(stage, images);
            task.doLast(ignored -> writeReport(output.resolve(REPORT),
                    report(recipe, targets, modules, source(services.getExec(), rootDir), imageIds)));
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
        Path dockerfile = target.dockerfile() != null ? rootDir.resolve(target.dockerfile())
                : rootDir.resolve("deploy/recipes/Dockerfile." + target.mode());
                Path context = target.dockerfile() != null ? rootDir.resolve(target.contextDir()) : output.resolve(target.stagingDir());
        return List.of(docker, "build", "-f", dockerfile.toString(), "-t", target.image(), context.toString());
    }

    static ObjectNode report(RecipeReader.Document recipe, List<RecipeTasks.Target> targets, List<String> modules,
                             JsonNode source, Map<String, String> imageIds) {
        ObjectNode report = JSON.createObjectNode();
        report.put("schemaVersion", 2);
        report.putObject("recipe").put("name", recipe.data().get("name").asText()).put("sha256", recipe.sourceSha256());
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
                component.put("variant", identity.variant()).put("optimization", identity.optimization());
            }
            if (target.nativeOptions() != null) {
                ObjectNode options = component.putObject("native")
                        .put("optimization", target.nativeOptions().optimization())
                        .put("gc", target.nativeOptions().gc());
                ArrayNode monitoring = options.putArray("monitoring");
                target.nativeOptions().monitoring().forEach(monitoring::add);
            }
            if (target.image() == null) {
                component.putNull("image");
            } else {
                component.putObject("image").put("reference", target.image()).put("status", "built")
                        .put("id", imageIds.get(target.image()));
            }
        }
        return report;
    }

    /** Git revision and dirty state, or null when Git or the repository is unavailable: never an invented clean state. */
    static JsonNode source(ExecOperations exec, Path rootDir) {
        String revision = git(exec, rootDir, "rev-parse", "HEAD");
        if (revision == null) {
            return NullNode.getInstance();
        }
        // Untracked files count: an untracked function directory can be built into an image.
        String status = git(exec, rootDir, "status", "--porcelain");
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
