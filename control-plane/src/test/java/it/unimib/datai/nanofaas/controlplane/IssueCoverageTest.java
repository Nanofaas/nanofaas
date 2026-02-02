package it.unimib.datai.nanofaas.controlplane;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class IssueCoverageTest {
    @Test
    void issue001_structureExists() {
        Path root = repoRoot();
        assertTrue(Files.isDirectory(root.resolve("control-plane")));
        assertTrue(Files.isDirectory(root.resolve("function-runtime")));
        assertTrue(Files.isDirectory(root.resolve("common")));
    }

    @Test
    void issue002_buildConfigExists() {
        Path root = repoRoot();
        assertTrue(Files.exists(root.resolve("build.gradle")));
        assertTrue(Files.exists(root.resolve("gradle.properties")));
    }

    @Test
    void issue003_dockerfilesExist() {
        Path root = repoRoot();
        assertTrue(Files.exists(root.resolve("control-plane/Dockerfile")));
        assertTrue(Files.exists(root.resolve("function-runtime/Dockerfile")));
    }

    @Test
    void issue004_k8sManifestsExist() {
        Path root = repoRoot();
        assertTrue(Files.exists(root.resolve("k8s/namespace.yaml")));
        assertTrue(Files.exists(root.resolve("k8s/serviceaccount.yaml")));
        assertTrue(Files.exists(root.resolve("k8s/rbac.yaml")));
        assertTrue(Files.exists(root.resolve("k8s/control-plane-deployment.yaml")));
        assertTrue(Files.exists(root.resolve("k8s/control-plane-service.yaml")));
    }

    @Test
    void issue005_openApiExists() {
        Path root = repoRoot();
        assertTrue(Files.exists(root.resolve("openapi.yaml")));
    }

    @Test
    void issue019_sloDocExists() {
        Path root = repoRoot();
        assertTrue(Files.exists(root.resolve("docs/slo.md")));
    }

    @Test
    void issue020_quickstartDocExists() {
        Path root = repoRoot();
        assertTrue(Files.exists(root.resolve("docs/quickstart.md")));
    }

    @Test
    void issue021_exampleFunctionDocExists() {
        Path root = repoRoot();
        assertTrue(Files.exists(root.resolve("docs/example-function.md")));
    }

    private Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("settings.gradle"))) {
            current = current.getParent();
        }
        return current == null ? Path.of("") : current;
    }
}
