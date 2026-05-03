package it.unimib.datai.nanofaas.modules.k8s.e2e;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.KubernetesResource;
import io.fabric8.kubernetes.api.model.Probe;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.fail;

class K8sE2eDeploymentSpecTest {

    private static final Duration HELM_TEMPLATE_TIMEOUT = Duration.ofSeconds(30);

    @Test
    void controlPlaneHelmChart_rendersManagementHealthProbes() throws Exception {
        Container container = renderedDeployment("nanofaas", "helm/nanofaas", "nanofaas-control-plane")
                .getSpec().getTemplate().getSpec().getContainers().getFirst();

        assertAll(
                () -> assertHttpProbe(
                        container.getReadinessProbe(),
                        "control-plane readinessProbe",
                        "/actuator/health/readiness",
                        8081),
                () -> assertHttpProbe(
                        container.getLivenessProbe(),
                        "control-plane livenessProbe",
                        "/actuator/health/liveness",
                        8081)
        );
    }

    @Test
    void functionRuntimeHelmChart_rendersHttpHealthProbes() throws Exception {
        Container container = renderedDeployment("runtime", "helm/nanofaas-runtime", "runtime")
                .getSpec().getTemplate().getSpec().getContainers().getFirst();

        assertAll(
                () -> assertHttpProbe(
                        container.getReadinessProbe(),
                        "function-runtime readinessProbe",
                        "/actuator/health/readiness",
                        8080),
                () -> assertHttpProbe(
                        container.getLivenessProbe(),
                        "function-runtime livenessProbe",
                        "/actuator/health/liveness",
                        8080)
        );
    }

    @Test
    void registrationTargets_fallsBackToLegacyEchoWhenManifestIsAbsent() {
        var targets = K8sE2eTest.registrationTargets(Optional.empty());

        assertAll(
                () -> assertEquals(1, targets.size()),
                () -> assertEquals("k8s-echo", targets.getFirst().name()),
                () -> assertEquals("legacy-echo", targets.getFirst().family())
        );
    }

    private static Deployment renderedDeployment(
            String releaseName,
            String chartRepoRelativePath,
            String deploymentName) throws Exception {
        List<Deployment> deployments = renderedResources(releaseName, chartRepoRelativePath).stream()
                .filter(Deployment.class::isInstance)
                .map(Deployment.class::cast)
                .toList();

        return deployments.stream()
                .filter(deployment -> deploymentName.equals(deployment.getMetadata().getName()))
                .findFirst()
                .orElseGet(() -> fail("Expected Helm chart " + chartRepoRelativePath
                        + " to render Deployment " + deploymentName
                        + "; rendered deployments: " + deployments.stream()
                        .map(deployment -> deployment.getMetadata().getName())
                        .toList()));
    }

    private static List<KubernetesResource> renderedResources(
            String releaseName,
            String chartRepoRelativePath) throws Exception {
        Object decoded = Serialization.unmarshal(
                renderHelmChart(releaseName, chartRepoRelativePath),
                KubernetesResource.class);

        if (decoded instanceof List<?> resources) {
            assertFalse(resources.isEmpty(), "helm template should render at least one Kubernetes resource");
            return resources.stream()
                    .map(resource -> assertInstanceOf(
                            KubernetesResource.class,
                            resource,
                            "helm template rendered a non-Kubernetes resource"))
                    .toList();
        }

        return List.of(assertInstanceOf(
                KubernetesResource.class,
                decoded,
                "helm template rendered a non-Kubernetes resource"));
    }

    private static String renderHelmChart(String releaseName, String chartRepoRelativePath) throws Exception {
        Path chartPath = repoRoot().resolve(chartRepoRelativePath);
        Process process;
        try {
            process = new ProcessBuilder(
                    "helm",
                    "template",
                    releaseName,
                    chartPath.toString(),
                    "--namespace",
                    "nanofaas")
                    .directory(repoRoot().toFile())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            throw new AssertionError("helm must be installed and available on PATH to validate rendered charts", e);
        }

        if (!process.waitFor(HELM_TEMPLATE_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("helm template timed out for chart " + chartRepoRelativePath);
        }

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            fail("helm template failed for chart " + chartRepoRelativePath + ":\n" + output);
        }
        return output;
    }

    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("helm"))
                    && Files.isDirectory(current.resolve("control-plane-modules"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate repository root from working directory");
    }

    private static void assertHttpProbe(Probe probe, String probeName, String path, int port) {
        if (probe == null) {
            fail(probeName + " should be configured");
        }
        if (probe.getHttpGet() == null) {
            fail(probeName + " should use httpGet");
        }
        assertAll(
                () -> assertEquals(path, probe.getHttpGet().getPath()),
                () -> assertEquals(Integer.valueOf(port), probe.getHttpGet().getPort().getIntVal())
        );
    }
}
