package it.unimib.datai.nanofaas.gradle;

import com.fasterxml.jackson.databind.JsonNode;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.plugins.JavaApplication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Resolves a recipe against the evaluated build and registers the recipe tasks on the root project.
 * One resolver feeds the catalog, the preview and the build, so they cannot disagree.
 */
final class RecipeTasks {

    private static final String GROUP = "distribution";
    private static final List<String> DOCKERFILE_SDKS = List.of("go", "javascript", "python");

    /** An implementation present in this checkout; {@code projectPath} is null for Dockerfile SDKs. */
    record Implementation(String name, String sdk, Path directory, String projectPath, List<String> modes) {
    }

    /**
     * One component of the resolved recipe. {@code task} is the Gradle task producing a Java artifact, else null;
     * {@code dockerfile} is set for Dockerfile SDKs; {@code image} is null when the component has no container.
     * {@code jvmArgs} is null when the recipe sets none; {@code mainClass} is set only for java-lite on the JVM.
     */
    record Target(String field, String name, String sdk, String mode, String task, Path dockerfile,
                  String stagingDir, String image, List<String> jvmArgs, String mainClass) {

        boolean controlPlane() {
            return field.equals("controlPlane");
        }
    }

    private final Project root;
    private final RecipeReader.Document recipe;
    private List<Target> targets;

    private RecipeTasks(Project root, RecipeReader.Document recipe) {
        this.root = root;
        this.recipe = recipe;
    }

    /** @param recipe the validated recipe, or null when {@code -Precipe} is absent (only the catalog works) */
    static void register(Project root, RecipeReader.Document recipe) {
        RecipeTasks recipeTasks = new RecipeTasks(root, recipe);
        root.getTasks().register("listRecipeFunctions", task -> {
            task.setGroup(GROUP);
            task.setDescription("Lists the function implementations a recipe can select.");
            task.doLast(ignored -> recipeTasks.catalog().forEach(implementation -> System.out.printf(
                    "%-24s %-11s %-44s %s%n", implementation.name(), implementation.sdk(),
                    slash(implementation.directory()), String.join(", ", implementation.modes()))));
        });
        root.getTasks().register("validateRecipe", task -> {
            task.setGroup(GROUP);
            task.setDescription("Validates -Precipe and previews modules, build tasks, artifacts and images.");
            task.doLast(ignored -> recipeTasks.printPreview());
        });
        root.getTasks().register("assembleRecipe", task -> {
            task.setGroup(GROUP);
            task.setDescription("Builds the -Precipe artifacts and images into build/recipes/<name>/; never pushes.");
            task.doFirst(ignored -> requireRecipe(recipe, "assembleRecipe"));
        });
        root.getTasks().register("publishRecipe", task -> {
            task.setGroup(GROUP);
            task.setDescription("Assembles the whole -Precipe, then pushes its images and records their digests.");
            task.doFirst(ignored -> requireRecipe(recipe, "publishRecipe"));
        });
        if (recipe != null) {
            root.getGradle().projectsEvaluated(ignored -> {
                recipeTasks.targets = recipeTasks.resolve();
                RecipeArtifacts.register(root, recipe, recipeTasks.targets, recipeTasks.modules());
            });
        }
    }

    List<Implementation> catalog() {
        Path rootDir = realPath(root.getRootDir().toPath());
        List<Implementation> implementations = new ArrayList<>();
        for (Project project : root.getAllprojects()) {
            if (project.getParent() == null || !project.getParent().getPath().equals(":functions:java")) {
                continue;
            }
            String sdk = javaSdkOf(project);
            boolean lite = "java-lite".equals(sdk);
            if (sdk == null || lite != project.getName().endsWith("-lite") || !inside(rootDir, project.getProjectDir().toPath())) {
                continue;
            }
            Set<String> taskNames = project.getTasks().getNames();
            List<String> modes = Stream.of("jvm", "native").filter(mode -> taskNames.contains(taskName(sdk, mode))).toList();
            if (!modes.isEmpty()) {
                String name = lite ? project.getName().substring(0, project.getName().length() - "-lite".length())
                        : project.getName();
                implementations.add(new Implementation(name, sdk, relative(project.getProjectDir().toPath()),
                        project.getPath(), modes));
            }
        }
        for (String sdk : DOCKERFILE_SDKS) {
            Path sdkDir = rootDir.resolve("functions").resolve(sdk);
            if (!Files.isDirectory(sdkDir)) {
                continue;
            }
            try (Stream<Path> directories = Files.list(sdkDir)) {
                directories.filter(directory -> Files.isRegularFile(directory.resolve("Dockerfile")))
                        .filter(directory -> inside(rootDir, directory))
                        .forEach(directory -> implementations.add(new Implementation(directory.getFileName().toString(),
                                sdk, relative(directory), null, List.of("container"))));
            } catch (IOException exception) {
                throw new GradleException("Cannot list " + sdkDir, exception);
            }
        }
        implementations.sort(Comparator.comparing(Implementation::name).thenComparing(Implementation::sdk));
        return implementations;
    }

    private List<Target> resolve() {
        JsonNode data = recipe.data();
        List<Target> resolved = new ArrayList<>();
        Set<String> images = new HashSet<>();

        Project controlPlane = root.findProject(":control-plane");
        String controlPlaneMode = data.at("/controlPlane/build/mode").asText();
        if (controlPlane == null) {
            throw fail("controlPlane: project :control-plane is not available in this build");
        }
        if (!controlPlane.getTasks().getNames().contains(taskName("java", controlPlaneMode))) {
            throw fail("controlPlane.build.mode: the control plane does not support " + controlPlaneMode);
        }
        resolved.add(new Target("controlPlane", "control-plane", "java", controlPlaneMode,
                controlPlane.getPath() + ":" + taskName("java", controlPlaneMode), null, "control-plane/",
                image(data.path("controlPlane"), "controlPlane", images), jvmArgs(data.path("controlPlane")), null));

        Map<List<String>, Implementation> available = new HashMap<>();
        catalog().forEach(implementation -> available.put(List.of(implementation.name(), implementation.sdk()), implementation));
        Set<List<String>> declared = new HashSet<>();
        JsonNode functions = data.path("functions");
        for (int index = 0; index < functions.size(); index++) {
            JsonNode function = functions.get(index);
            String field = "functions[" + index + "]";
            String name = function.get("name").asText();
            String sdk = function.get("sdk").asText();
            if (!declared.add(List.of(name, sdk))) {
                throw fail(field + ": " + name + " (" + sdk + ") is already declared");
            }
            Implementation implementation = available.get(List.of(name, sdk));
            if (implementation == null) {
                throw fail(field + ": " + sdk + " implementation of " + name + " is not available");
            }
            boolean java = implementation.projectPath() != null;
            String mode = java ? function.at("/build/mode").asText() : "container";
            if (!implementation.modes().contains(mode)) {
                throw fail(field + ".build.mode: " + sdk + " implementation of " + name + " does not support " + mode);
            }
            resolved.add(new Target(field, name, sdk, mode,
                    java ? implementation.projectPath() + ":" + taskName(sdk, mode) : null,
                    java ? null : implementation.directory().resolve("Dockerfile"),
                    java ? "functions/" + sdk + "/" + name + "/" : null,
                    image(function, field, images), jvmArgs(function),
                    sdk.equals("java-lite") && mode.equals("jvm") ? mainClass(field, implementation) : null));
        }
        return List.copyOf(resolved);
    }

    private static List<String> jvmArgs(JsonNode component) {
        JsonNode args = component.path("jvm").path("args");
        if (args.isMissingNode()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        args.forEach(arg -> values.add(arg.asText()));
        return List.copyOf(values);
    }

    private String mainClass(String field, Implementation implementation) {
        JavaApplication application = root.project(implementation.projectPath()).getExtensions()
                .findByType(JavaApplication.class);
        if (application == null || !application.getMainClass().isPresent()) {
            throw fail(field + ": " + implementation.projectPath() + " declares no application mainClass");
        }
        return application.getMainClass().get();
    }

    private String image(JsonNode component, String field, Set<String> images) {
        JsonNode image = component.path("container").path("image");
        if (image.isMissingNode()) {
            return null;
        }
        JsonNode registry = recipe.data().path("registry");
        String repository = registry.isMissingNode()
                ? "nanofaas/" + recipe.data().get("name").asText() : registry.get("repository").asText();
        String reference = repository + "/" + image.asText() + ":" + recipe.effectiveTag();
        if (!images.add(reference)) {
            throw fail(field + ".container.image: image " + reference + " is already used");
        }
        return reference;
    }

    @SuppressWarnings("unchecked")
    private List<String> modules() {
        return (List<String>) root.getGradle().getExtensions().getExtraProperties()
                .get(ControlPlaneModulesPlugin.SELECTED_EXTRA_PROPERTY);
    }

    static void requireRecipe(RecipeReader.Document recipe, String taskName) {
        if (recipe == null) {
            throw new GradleException(taskName + " requires -Precipe=<file>");
        }
    }

    private void printPreview() {
        requireRecipe(recipe, "validateRecipe");
        List<String> modules = modules();
        System.out.println("Recipe " + recipe.data().get("name").asText() + ": " + recipe.source()
                + " (sha256 " + recipe.sourceSha256() + ")");
        System.out.println("Tag: " + recipe.effectiveTag());
        System.out.println("Control-plane modules: " + (modules.isEmpty() ? "(core only)" : String.join(", ", modules)));
        for (Target target : targets) {
            String build = target.task() != null ? target.task()
                    : "docker build -f " + slash(target.dockerfile()) + " .";
            System.out.printf("  %-20s %-11s %-10s %s%s%s%n", target.name(), target.sdk(), target.mode(), build,
                    target.stagingDir() == null ? "" : " -> " + target.stagingDir(),
                    target.image() == null ? "" : "  image " + target.image());
        }
    }

    private static String javaSdkOf(Project project) {
        Configuration implementation = project.getConfigurations().findByName("implementation");
        if (implementation == null) {
            return null;
        }
        Set<String> paths = implementation.getDependencies().withType(ProjectDependency.class).stream()
                .map(ProjectDependency::getPath).collect(Collectors.toSet());
        return paths.contains(":sdks:java-lite") ? "java-lite" : paths.contains(":sdks:java") ? "java" : null;
    }

    static String taskName(String sdk, String mode) {
        if (mode.equals("native")) {
            return "nativeCompile";
        }
        return sdk.equals("java-lite") ? "installDist" : "bootJar";
    }

    private static boolean inside(Path rootDir, Path directory) {
        Path real = realPath(directory);
        return real != null && real.startsWith(rootDir);
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException exception) {
            return null;
        }
    }

    private Path relative(Path directory) {
        return realPath(root.getRootDir().toPath()).relativize(realPath(directory));
    }

    private static String slash(Path path) {
        return path.toString().replace('\\', '/');
    }

    private GradleException fail(String message) {
        return RecipeReader.failure(recipe.source(), message);
    }
}
