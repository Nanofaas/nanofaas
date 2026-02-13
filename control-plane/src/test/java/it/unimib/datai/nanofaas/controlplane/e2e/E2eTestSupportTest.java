package it.unimib.datai.nanofaas.controlplane.e2e;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
