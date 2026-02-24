package it.unimib.datai.nanofaas.controlplane.e2e;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class E2eTestSupportTest {

    @Test
    void resolveBootJar_prefersProjectVersionOverLexicographicMax(@TempDir Path tempDir) throws Exception {
        String previousVersion = System.getProperty("project.version");
        System.setProperty("project.version", "0.10.0");
        try {
            Files.createFile(tempDir.resolve("control-plane-0.9.2.jar"));
            Files.createFile(tempDir.resolve("control-plane-0.10.0.jar"));
            Files.createFile(tempDir.resolve("control-plane-0.11.0.jar"));
            Files.createFile(tempDir.resolve("control-plane-0.10.0-plain.jar"));

            Path selected = E2eTestSupport.resolveBootJar(tempDir, "control-plane");

            assertEquals("control-plane-0.10.0.jar", selected.getFileName().toString());
        } finally {
            if (previousVersion == null) {
                System.clearProperty("project.version");
            } else {
                System.setProperty("project.version", previousVersion);
            }
        }
    }

    @Test
    void resolveControlPlaneContainerPlan_usesImageOverrideWhenConfigured() {
        String previousOverride = System.getProperty("control.plane.image.override");
        System.setProperty("control.plane.image.override", "nanofaas/control-plane:rust-e2e");
        try {
            E2eTestSupport.ControlPlaneContainerPlan plan = E2eTestSupport.resolveControlPlaneContainerPlan();

            assertTrue(plan.isImageOverride());
            assertEquals("nanofaas/control-plane:rust-e2e", plan.imageOverride());
        } finally {
            if (previousOverride == null) {
                System.clearProperty("control.plane.image.override");
            } else {
                System.setProperty("control.plane.image.override", previousOverride);
            }
        }
    }

    @Test
    void resolveControlPlaneContainerPlan_defaultsToDockerfilePlanWithoutOverride() {
        String previousOverride = System.getProperty("control.plane.image.override");
        try {
            System.clearProperty("control.plane.image.override");

            E2eTestSupport.ControlPlaneContainerPlan plan = E2eTestSupport.resolveControlPlaneContainerPlan();

            assertFalse(plan.isImageOverride());
            assertEquals("control-plane-", plan.jarPrefix());
            assertEquals(E2eTestSupport.PROJECT_ROOT.resolve("control-plane/Dockerfile"), plan.dockerfilePath());
            assertEquals(E2eTestSupport.PROJECT_ROOT.resolve("control-plane/build/libs"), plan.moduleBuildLibsDir());
        } finally {
            if (previousOverride != null) {
                System.setProperty("control.plane.image.override", previousOverride);
            }
        }
    }
}
