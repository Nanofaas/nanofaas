package it.unimib.datai.nanofaas.gradle;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.Directory;
import org.gradle.api.initialization.Settings;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.jvm.tasks.ProcessResources;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ControlPlaneModulesPlugin implements Plugin<Settings> {

    private static final String SELECTOR_PROPERTY = "controlPlaneModules";
    private static final String RECIPE_PROPERTY = "recipe";
    private static final String RECIPE_TAG_PROPERTY = "recipeTag";
    private static final String BUILD_TYPE_PROPERTY = "nanofaasBuildType";
    static final String SELECTED_EXTRA_PROPERTY = "nanofaasSelectedControlPlaneModules";
    private static final String NATIVE_BUILD_EXTRA_PROPERTY = "nanofaasNativeBuildRequested";

    @Override
    public void apply(Settings settings) {
        Path modulesRoot = settings.getSettingsDir().toPath().resolve("platform/modules");
        RecipeReader.Document recipe = readRecipe(settings);
        List<ModuleDescriptor> descriptors = discover(settings, modulesRoot);
        List<String> selected;
        boolean nativeBuild;
        if (recipe == null) {
            selected = select(settings, descriptors);
            new ModuleConstraintResolver().validate(descriptors, selected);
            nativeBuild = isNativeBuildRequested(settings);
        } else {
            RecipeBuildProperties.rejectOwnedFlags(recipe.source(),
                    settings.getStartParameter().getProjectProperties().keySet());
            selected = selectFromRecipe(settings, recipe, descriptors);
            RecipeBuildProperties.requireBuildMetadata(recipe.source(), recipe.data(), selected);
            nativeBuild = recipeNativeBuild(settings, recipe);
        }
        Map<String, Map<String, String>> projectProperties =
                recipe == null ? Map.of() : RecipeBuildProperties.byProject(recipe.data());
        settings.getGradle().beforeProject(project -> {
            // Before the build script runs: the scripts read these through project.findProperty,
            // exactly as they read the corresponding -P flags without a recipe.
            projectProperties.getOrDefault(project.getPath(), Map.of())
                    .forEach(project.getExtensions().getExtraProperties()::set);
            if (project.getParent() == null) {
                RecipeTasks.register(project, recipe);
            }
            if (project.getPath().startsWith(":control-plane-modules:")) {
                project.getPluginManager().apply(ControlPlaneModuleProjectPlugin.class);
            }
            if (project.getPath().equals(":control-plane")) {
                configureOpenApiComposition(project, modulesRoot, selected);
            }
        });
        settings.getGradle().getExtensions().getExtraProperties()
                .set(SELECTED_EXTRA_PROPERTY, List.copyOf(selected));
        settings.getGradle().getExtensions().getExtraProperties()
                .set(NATIVE_BUILD_EXTRA_PROPERTY, nativeBuild);
    }

    private static RecipeReader.Document readRecipe(Settings settings) {
        Map<String, String> properties = settings.getStartParameter().getProjectProperties();
        String file = properties.get(RECIPE_PROPERTY);
        String tag = properties.get(RECIPE_TAG_PROPERTY);
        if (file == null) {
            if (tag != null) {
                throw failure("-PrecipeTag requires -Precipe=<file>");
            }
            return null;
        }
        return new RecipeReader().read(settings.getSettingsDir().toPath().resolve(file).normalize(), tag);
    }

    /** With a recipe, its module list is the only composition source: no selector, no environment. */
    private static List<String> selectFromRecipe(Settings settings, RecipeReader.Document recipe,
                                                 List<ModuleDescriptor> descriptors) {
        if (settings.getStartParameter().getProjectProperties().containsKey(SELECTOR_PROPERTY)) {
            throw RecipeReader.failure(recipe.source(), "-PcontrolPlaneModules cannot be combined with -Precipe;"
                    + " the recipe's controlPlane.modules selects the modules");
        }
        List<String> requested = new ArrayList<>();
        recipe.data().at("/controlPlane/modules").forEach(module -> requested.add(module.asText()));
        Set<String> available = descriptors.stream().map(ModuleDescriptor::id).collect(java.util.stream.Collectors.toSet());
        List<String> unknown = requested.stream().filter(id -> !available.contains(id)).sorted().toList();
        if (!unknown.isEmpty()) {
            throw RecipeReader.failure(recipe.source(), "controlPlane.modules: Unknown control-plane module(s): " + unknown);
        }
        try {
            new ModuleConstraintResolver().validate(descriptors, requested);
        } catch (IllegalArgumentException exception) {
            throw RecipeReader.failure(recipe.source(), "controlPlane.modules: " + exception.getMessage());
        }
        return requested.stream().sorted().toList();
    }

    /**
     * With a recipe the control plane's declared mode decides AOT, not the task names: assembleRecipe contains no
     * native task name, and a native function must not turn a JVM control plane native.
     */
    private static boolean recipeNativeBuild(Settings settings, RecipeReader.Document recipe) {
        String mode = recipe.data().at("/controlPlane/build/mode").asText();
        String buildType = settings.getStartParameter().getProjectProperties().get(BUILD_TYPE_PROPERTY);
        if (buildType != null && !buildType.equals(mode)) {
            throw RecipeReader.failure(recipe.source(), "-P" + BUILD_TYPE_PROPERTY + "=" + buildType
                    + " contradicts controlPlane.build.mode " + mode);
        }
        if (mode.equals("jvm") && isNativeBuildRequested(settings)) {
            throw RecipeReader.failure(recipe.source(), "native tasks " + settings.getStartParameter().getTaskNames()
                    + " requested, but the recipe declares a jvm control plane; build it with assembleRecipe");
        }
        return mode.equals("native");
    }

    /**
     * Whether the invocation targets a native build. Resolved once here because both
     * {@code :control-plane} (to gate Spring AOT) and the build-metadata module (to default
     * the recorded build type) need the same answer, and two copies of the heuristic would
     * drift apart the day the native task names change.
     */
    private static boolean isNativeBuildRequested(Settings settings) {
        return settings.getStartParameter().getTaskNames().stream()
                .map(name -> name.toLowerCase(java.util.Locale.ROOT))
                .anyMatch(name -> name.contains("nativecompile") || name.contains("nativetest"));
    }

    private static void configureOpenApiComposition(Project project, Path modulesRoot, List<String> selected) {
        List<String> fragmentModuleIds = new ArrayList<>();
        List<File> fragmentFiles = new ArrayList<>();
        for (String moduleId : selected) {
            File fragment = modulesRoot.resolve(moduleId).resolve("openapi.yaml").toFile();
            if (fragment.isFile()) {
                fragmentModuleIds.add(moduleId);
                fragmentFiles.add(fragment);
            }
        }

        Provider<Directory> generatedDir = project.getLayout().getBuildDirectory().dir("generated/openapi");
        TaskProvider<ComposeOpenApiTask> composeTask = project.getTasks().register(
                "composeControlPlaneOpenApi", ComposeOpenApiTask.class, task -> {
                    task.getCoreDocument().set(project.getRootProject().file("openapi/core.yaml"));
                    task.getFragments().setFrom(fragmentFiles);
                    task.getModuleIds().set(fragmentModuleIds);
                    task.getOutputFile().set(generatedDir.map(dir -> dir.file("META-INF/resources/openapi.yaml")));
                });

        project.getPluginManager().withPlugin("java", ignored ->
                project.getTasks().named("processResources", ProcessResources.class, task -> {
                    task.dependsOn(composeTask);
                    task.from(generatedDir);
                }));
    }

    private static List<ModuleDescriptor> discover(Settings settings, Path modulesRoot) {
        if (!Files.isDirectory(modulesRoot)) {
            return List.of();
        }

        List<ModuleDescriptor> descriptors = new ArrayList<>();
        settings.include(":control-plane-modules");
        settings.project(":control-plane-modules").setProjectDir(modulesRoot.toFile());
        try (var directories = Files.list(modulesRoot)) {
            directories.filter(Files::isDirectory)
                    .filter(ControlPlaneModulesPlugin::isGradleProject)
                    .sorted()
                    .forEach(directory -> {
                        Path descriptorPath = directory.resolve("module.properties");
                        if (!Files.isRegularFile(descriptorPath)) {
                            throw failure("Module directory '" + directory.getFileName()
                                    + "' is missing module.properties");
                        }
                        ModuleDescriptor descriptor = new ModuleDescriptorReader().read(descriptorPath);
                        if (!directory.getFileName().toString().equals(descriptor.id())) {
                            throw failure("Module directory name '" + directory.getFileName()
                                    + "' does not match descriptor id '" + descriptor.id() + "'");
                        }
                        String projectPath = ":control-plane-modules:" + descriptor.id();
                        settings.include(projectPath);
                        settings.project(projectPath).setProjectDir(directory.toFile());
                        descriptors.add(descriptor);
                    });
        } catch (GradleException exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure("Cannot discover control-plane modules under " + modulesRoot, exception);
        }
        return List.copyOf(descriptors);
    }

    private static boolean isGradleProject(Path directory) {
        return Files.isRegularFile(directory.resolve("build.gradle"))
                || Files.isRegularFile(directory.resolve("build.gradle.kts"));
    }

    private static List<String> select(Settings settings, List<ModuleDescriptor> descriptors) {
        Set<String> available = descriptors.stream().map(ModuleDescriptor::id).collect(java.util.stream.Collectors.toSet());
        String raw = settings.getStartParameter().getProjectProperties().get(SELECTOR_PROPERTY);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("NANOFAAS_CONTROL_PLANE_MODULES");
        }

        LinkedHashSet<String> requested = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            descriptors.stream().filter(ModuleDescriptor::defaultEnabled)
                    .map(ModuleDescriptor::id).forEach(requested::add);
        } else {
            for (String value : raw.split(",", -1)) {
                String id = value.trim();
                if (!id.isEmpty()) {
                    if (!requested.add(id)) {
                        throw failure("Duplicate control-plane module '" + id + "'");
                    }
                }
            }
            if (requested.contains("none")) {
                if (requested.size() != 1) {
                    throw failure("Module selector 'none' cannot be combined with other values");
                }
                requested.clear();
            }
            if (requested.contains("all")) {
                if (requested.size() != 1) {
                    throw failure("Module selector 'all' cannot be combined with other values");
                }
                requested.clear();
                requested.addAll(selectAll(descriptors));
            }
        }

        List<String> unknown = requested.stream().filter(id -> !available.contains(id)).sorted().toList();
        if (!unknown.isEmpty()) {
            throw failure("Unknown control-plane module(s): " + unknown);
        }
        return requested.stream().sorted().toList();
    }

    private static List<String> selectAll(List<ModuleDescriptor> descriptors) {
        List<ModuleDescriptor> defaults = descriptors.stream().filter(ModuleDescriptor::defaultEnabled).toList();
        validateEqualPriorityConflicts(defaults);

        List<ModuleDescriptor> candidates = new ArrayList<>();
        for (ModuleDescriptor descriptor : descriptors.stream().filter(descriptor -> !descriptor.defaultEnabled()).toList()) {
            ModuleDescriptor conflictingDefault = defaults.stream().filter(candidate -> conflicts(descriptor, candidate))
                    .findFirst().orElse(null);
            if (conflictingDefault != null) {
                Logging.getLogger(ControlPlaneModulesPlugin.class).lifecycle("Skipping control-plane module '{}': "
                                + "conflicts with default-enabled module '{}'",
                        descriptor.id(), conflictingDefault.id());
            } else {
                candidates.add(descriptor);
            }
        }
        validateEqualPriorityConflicts(candidates);
        return java.util.stream.Stream.concat(defaults.stream(), candidates.stream()).map(ModuleDescriptor::id).toList();
    }

    private static void validateEqualPriorityConflicts(List<ModuleDescriptor> descriptors) {
        // ponytail: module counts are tiny; replace the pair scan only if that changes materially.
        for (int leftIndex = 0; leftIndex < descriptors.size(); leftIndex++) {
            ModuleDescriptor left = descriptors.get(leftIndex);
            for (int rightIndex = leftIndex + 1; rightIndex < descriptors.size(); rightIndex++) {
                ModuleDescriptor right = descriptors.get(rightIndex);
                if (conflicts(left, right)) {
                    throw failure("Invalid module constraints: modules '" + left.id()
                            + "' and '" + right.id() + "' conflict with equal defaultEnabled priority");
                }
            }
        }
    }

    private static boolean conflicts(ModuleDescriptor left, ModuleDescriptor right) {
        return left.conflicts().contains(right.id()) || right.conflicts().contains(left.id());
    }

    private static GradleException failure(String message) {
        return new GradleException(message);
    }

    private static GradleException failure(String message, Exception cause) {
        return new GradleException(message, cause);
    }
}
