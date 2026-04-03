package it.unimib.datai.nanofaas.controlplane.e2e;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("inter_e2e")
class ContainerLocalE2eTest {

    @Test
    void containerLocalManagedDeploymentFlow_passesScriptRunner() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker-compatible runtime is required for container-local E2E");

        File projectRoot = new File("..").getAbsoluteFile().getCanonicalFile();
        ProcessBuilder builder = new ProcessBuilder("bash", "scripts/e2e-container-local.sh")
                .directory(projectRoot);
        builder.environment().putIfAbsent("JAVA_HOME", System.getProperty("java.home"));
        builder.redirectErrorStream(true);

        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
            }
        }

        int exit = process.waitFor();
        assertThat(exit)
                .withFailMessage("e2e-container-local.sh failed with output:%n%s", output)
                .isZero();
    }
}
