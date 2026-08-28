package it.unimib.datai.nanofaas.gradle;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.initialization.Settings;

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
        settings.getGradle().getExtensions().getExtraProperties()
                .set(SELECTED_EXTRA_PROPERTY, List.copyOf(selected));
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
                requested.addAll(available);
                requested.remove("all");
            }
        }

        List<String> unknown = requested.stream().filter(id -> !available.contains(id)).sorted().toList();
        if (!unknown.isEmpty()) {
            throw failure("Unknown control-plane module(s): " + unknown);
        }
        return requested.stream().sorted().toList();
    }

    private static GradleException failure(String message) {
        return new GradleException(message);
    }

    private static GradleException failure(String message, Exception cause) {
        return new GradleException(message, cause);
    }
}
