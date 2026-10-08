package it.unimib.datai.nanofaas.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.AppliedPlugin;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ControlPlaneModuleProjectPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        ModuleDescriptor descriptor = new ModuleDescriptorReader()
                .read(Path.of(project.getProjectDir().toURI()).resolve("module.properties"));
        AtomicBoolean configured = new AtomicBoolean();
        org.gradle.api.Action<AppliedPlugin> configure = ignored -> {
            if (configured.compareAndSet(false, true)) {
                addDependencies(project, descriptor);
            }
        };
        project.getPluginManager().withPlugin("java", configure);
        project.getPluginManager().withPlugin("java-library", configure);
    }

    private static void addDependencies(Project project, ModuleDescriptor descriptor) {
        descriptor.strongRequirements().forEach(id -> addDependency(project, "implementation", id));
        descriptor.weakRequirements().forEach(id -> addDependency(project, "compileOnly", id));
    }

    private static void addDependency(Project project, String configuration, String moduleId) {
        project.getDependencies().add(configuration,
                project.project(":control-plane-modules:" + moduleId));
    }
}
