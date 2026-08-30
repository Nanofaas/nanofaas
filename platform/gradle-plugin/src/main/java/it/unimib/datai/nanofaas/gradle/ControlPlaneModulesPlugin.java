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
import java.util.Set;

public final class ControlPlaneModulesPlugin implements Plugin<Settings> {

    private static final String SELECTOR_PROPERTY = "controlPlaneModules";
    private static final String SELECTED_EXTRA_PROPERTY = "nanofaasSelectedControlPlaneModules";

    @Override
    public void apply(Settings settings) {
        Path modulesRoot = settings.getSettingsDir().toPath().resolve("platform/modules");
        List<ModuleDescriptor> descriptors = discover(settings, modulesRoot);
        List<String> selected = select(settings, descriptors);
        new ModuleConstraintResolver().validate(descriptors, selected);
        settings.getGradle().beforeProject(project -> {
            if (project.getPath().startsWith(":control-plane-modules:")) {
                project.getPluginManager().apply(ControlPlaneModuleProjectPlugin.class);
            }
            if (project.getPath().equals(":control-plane")) {
                configureOpenApiComposition(project, modulesRoot, selected);
            }
        });
        settings.getGradle().getExtensions().getExtraProperties()
                .set(SELECTED_EXTRA_PROPERTY, List.copyOf(selected));
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
        // ponytail: module counts are tiny; replace the pair scan only if that changes materially.
        for (int leftIndex = 0; leftIndex < descriptors.size(); leftIndex++) {
            ModuleDescriptor left = descriptors.get(leftIndex);
            for (int rightIndex = leftIndex + 1; rightIndex < descriptors.size(); rightIndex++) {
                ModuleDescriptor right = descriptors.get(rightIndex);
                if (left.defaultEnabled() == right.defaultEnabled() && conflicts(left, right)) {
                    throw failure("Invalid module constraints: modules '" + left.id()
                            + "' and '" + right.id() + "' conflict with equal defaultEnabled priority");
                }
            }
        }

        List<ModuleDescriptor> defaults = descriptors.stream().filter(ModuleDescriptor::defaultEnabled).toList();
        List<String> selected = new ArrayList<>();
        for (ModuleDescriptor descriptor : descriptors) {
            ModuleDescriptor conflictingDefault = defaults.stream()
                    .filter(candidate -> conflicts(descriptor, candidate))
                    .findFirst()
                    .orElse(null);
            if (!descriptor.defaultEnabled() && conflictingDefault != null) {
                Logging.getLogger(ControlPlaneModulesPlugin.class).lifecycle("Skipping control-plane module '{}': "
                                + "conflicts with default-enabled module '{}'",
                        descriptor.id(), conflictingDefault.id());
            } else {
                selected.add(descriptor.id());
            }
        }
        return selected;
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
