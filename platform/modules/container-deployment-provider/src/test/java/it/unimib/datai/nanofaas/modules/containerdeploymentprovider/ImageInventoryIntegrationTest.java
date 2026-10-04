package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ImageInventoryIntegrationTest {
    @Test void javaAndCliReadTheRealEngineInventory() throws Exception {
        try (var client = ContainerDeploymentProviderConfiguration.createDockerClient()) {
            boolean available;
            try { client.pingCmd().exec(); available = true; }
            catch (RuntimeException e) { available = false; }
            assumeTrue(available, "Docker engine unavailable");
            var images = client.listImagesCmd().withShowAll(true).exec();
            assumeTrue(!images.isEmpty(), "Needs an existing image to create a test-owned alias");
            String id = images.getFirst().getId();
            String tag = UUID.randomUUID().toString();
            String repository = "nanofaas-p2p-inventory-test";
            client.tagImageCmd(id, repository, tag).exec();
            try {
                var javaInventory = new DockerImageInventorySource(client).snapshot(Duration.ofSeconds(2), 5000);
                assertThat(javaInventory.entries()).anySatisfy(image ->
                        assertThat(image.references()).contains(repository + ":" + tag));
                var cliInventory = new CliImageInventorySource("docker", new ImageInventoryCommand())
                        .snapshot(Duration.ofSeconds(2), 5000);
                assertThat(cliInventory.entries()).anySatisfy(image ->
                        assertThat(image.references()).contains(repository + ":" + tag));
                assertThat(cliInventory.entries()).anySatisfy(image -> assertThat(image.imageId()).isEqualTo(id));
            } finally {
                // Remove the unique test-owned alias only; the original image remains registered.
                client.removeImageCmd(repository + ":" + tag).exec();
            }
        }
    }
}
