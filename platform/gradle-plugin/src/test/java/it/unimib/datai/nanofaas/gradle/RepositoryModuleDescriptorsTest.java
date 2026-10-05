package it.unimib.datai.nanofaas.gradle;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryModuleDescriptorsTest {

    @Test
    void everyImmediateGradleModuleHasExactlyOneValidDescriptor() throws IOException {
        Path modulesRoot = repositoryRoot().resolve("platform/modules");
        List<Path> moduleDirectories;
        try (var directories = Files.list(modulesRoot)) {
            moduleDirectories = directories.filter(Files::isDirectory)
                    .filter(this::isGradleProject)
                    .sorted()
                    .toList();
        }
        List<ModuleDescriptor> descriptors = new ArrayList<>();
        for (Path moduleDirectory : moduleDirectories) {
            try (var files = Files.list(moduleDirectory)) {
                assertThat(files.filter(file -> file.getFileName().toString().equals("module.properties")).count())
                        .as(moduleDirectory.toString()).isEqualTo(1);
            }
            ModuleDescriptor descriptor = new ModuleDescriptorReader()
                    .read(moduleDirectory.resolve("module.properties"));
            assertThat(descriptor.schemaVersion()).isEqualTo(1);
            assertThat(descriptor.id()).isEqualTo(moduleDirectory.getFileName().toString());
            descriptors.add(descriptor);
        }
        Set<String> moduleIds = descriptors.stream().map(ModuleDescriptor::id).collect(Collectors.toSet());

        assertThat(moduleIds).containsExactlyInAnyOrder(
                "async-queue", "autoscaler", "build-metadata", "concurrency-control", "forecasting",
                "container-deployment-provider", "containerd-deployment-provider", "k8s-deployment-provider", "offload",
                "p2p-discovery", "runtime-config", "sync-queue");

        for (ModuleDescriptor descriptor : descriptors) {
            java.util.stream.Stream.of(
                            descriptor.strongRequirements(), descriptor.oneOfRequirements(),
                            descriptor.weakRequirements(), descriptor.conflicts())
                    .flatMap(java.util.Collection::stream)
                    .forEach(reference -> assertThat(moduleIds).contains(reference));
        }
    }

    @Test
    void deploymentProvidersHaveOneDefaultAndSymmetricConflicts() throws IOException {
        Path modulesRoot = repositoryRoot().resolve("platform/modules");
        ModuleDescriptor k8s = new ModuleDescriptorReader()
                .read(modulesRoot.resolve("k8s-deployment-provider/module.properties"));
        ModuleDescriptor container = new ModuleDescriptorReader()
                .read(modulesRoot.resolve("container-deployment-provider/module.properties"));
        ModuleDescriptor containerd = new ModuleDescriptorReader()
                .read(modulesRoot.resolve("containerd-deployment-provider/module.properties"));

        assertThat(k8s.defaultEnabled()).isTrue();
        assertThat(container.defaultEnabled()).isFalse();
        assertThat(containerd.defaultEnabled()).isFalse();
        assertThat(k8s.conflicts()).containsExactly("container-deployment-provider", "containerd-deployment-provider");
        assertThat(container.conflicts()).containsExactly("containerd-deployment-provider", "k8s-deployment-provider");
        assertThat(containerd.conflicts()).containsExactly("container-deployment-provider", "k8s-deployment-provider");
    }

    private boolean isGradleProject(Path path) {
        return Files.isRegularFile(path.resolve("build.gradle"))
                || Files.isRegularFile(path.resolve("build.gradle.kts"));
    }

    private Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 4; i++) {
            if (Files.isDirectory(current.resolve("platform/modules"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root");
    }
}
