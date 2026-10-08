package it.unimib.datai.nanofaas.controlplane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IssueCoverageTest {
    @Test
    void issue001_structureExists() {
        Path root = repoRoot();
        assertTrue(Files.isDirectory(root.resolve("platform/control-plane")));
        assertTrue(Files.isDirectory(root.resolve("services/java/warm-echo")));
        assertFalse(Files.exists(root.resolve("platform/function-runtime")));
        assertTrue(Files.isDirectory(root.resolve("platform/libs/common")));
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
        assertTrue(Files.exists(root.resolve("platform/control-plane/Dockerfile")));
        assertTrue(Files.exists(root.resolve("services/java/warm-echo/Dockerfile")));
    }

    @Test
    void issue004_k8sManifestsExist() {
        Path root = repoRoot();
        assertTrue(Files.exists(root.resolve("deploy/k8s/namespace.yaml")));
        assertTrue(Files.exists(root.resolve("deploy/k8s/serviceaccount.yaml")));
        assertTrue(Files.exists(root.resolve("deploy/k8s/rbac.yaml")));
        assertTrue(Files.exists(root.resolve("deploy/k8s/control-plane-deployment.yaml")));
        assertTrue(Files.exists(root.resolve("deploy/k8s/control-plane-service.yaml")));
        assertFalse(Files.exists(root.resolve("deploy/helm/nanofaas-runtime")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("repoFiles")
    void issueFileExists(Path file) {
        Path root = repoRoot();
        assertTrue(Files.exists(root.resolve(file)));
    }

    /** Cases: issue005_openApiExists, issue019_sloDocExists, issue020_quickstartDocExists, issue021_exampleFunctionDocExists. */
    static Stream<Path> repoFiles() {
        return Stream.of(
                Path.of("openapi/core.yaml"),         // issue005: OpenAPI spec
                Path.of("docs/slo.md"),               // issue019: SLO doc
                Path.of("docs/quickstart.md"),        // issue020: quickstart doc
                Path.of("docs/example-function.md")); // issue021: example function doc
    }

    private Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("settings.gradle"))) {
            current = current.getParent();
        }
        return current == null ? Path.of("") : current;
    }
}
