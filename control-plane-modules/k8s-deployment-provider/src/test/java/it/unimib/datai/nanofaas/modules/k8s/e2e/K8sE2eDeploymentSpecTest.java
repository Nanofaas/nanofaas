package it.unimib.datai.nanofaas.modules.k8s.e2e;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class K8sE2eDeploymentSpecTest {

    @Test
    void controlPlaneHelmChart_exposesManagementHealthProbes() throws Exception {
        String template = helmTemplate("helm/nanofaas/templates/control-plane-deployment.yaml");

        assertAll(
                () -> assertTemplateContains(
                        template,
                        "readinessProbe:\n" +
                                "            httpGet:\n" +
                                "              path: /actuator/health/readiness\n" +
                                "              port: {{ .Values.controlPlane.service.ports.actuator }}"),
                () -> assertTemplateContains(
                        template,
                        "livenessProbe:\n" +
                                "            httpGet:\n" +
                                "              path: /actuator/health/liveness\n" +
                                "              port: {{ .Values.controlPlane.service.ports.actuator }}")
        );
    }

    @Test
    void functionRuntimeHelmChart_exposesHttpHealthProbes() throws Exception {
        String template = helmTemplate("helm/nanofaas-runtime/templates/deployment.yaml");

        assertAll(
                () -> assertTemplateContains(
                        template,
                        "readinessProbe:\n" +
                                "            httpGet:\n" +
                                "              path: /actuator/health/readiness\n" +
                                "              port: {{ .Values.functionRuntime.service.port }}"),
                () -> assertTemplateContains(
                        template,
                        "livenessProbe:\n" +
                                "            httpGet:\n" +
                                "              path: /actuator/health/liveness\n" +
                                "              port: {{ .Values.functionRuntime.service.port }}")
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

    private static String helmTemplate(String repoRelativePath) throws IOException {
        return Files.readString(repoRoot().resolve(repoRelativePath));
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

    private static void assertTemplateContains(String template, String expected) {
        assertTrue(
                template.contains(expected),
                () -> "Expected Helm template to contain:\n" + expected);
    }
}
