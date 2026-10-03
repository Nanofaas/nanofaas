package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import com.github.dockerjava.api.DockerClient;
import it.unimib.datai.nanofaas.controlplane.deployment.ImageInventory;
import it.unimib.datai.nanofaas.controlplane.deployment.ImageInventorySource;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

final class DockerImageInventorySource implements ImageInventorySource {
    private final DockerClient client;
    DockerImageInventorySource(DockerClient client) { this.client = client; }

    @Override public ImageInventory snapshot(Duration timeout, int maxEntries) {
        try {
            var images = client.listImagesCmd().withShowAll(true).exec();
            if (images.size() > maxEntries) return unavailable("LIMIT_EXCEEDED");
            var entries = images.stream().map(image -> new ImageInventory.Entry(null,
                    image.getRepoTags() == null ? List.<String>of() : Arrays.stream(image.getRepoTags())
                            .filter(tag -> !tag.equals("<none>:<none>")).toList(),
                    image.getRepoDigests() == null || image.getRepoDigests().length == 0
                            ? null : image.getRepoDigests()[0], image.getId())).toList();
            return new ImageInventory("container-local", "local-engine", Instant.now(),
                    ImageInventory.Status.AVAILABLE, null, entries);
        } catch (RuntimeException e) { return unavailable("BACKEND_ERROR"); }
    }
    private static ImageInventory unavailable(String reason) {
        return new ImageInventory("container-local", "local-engine", Instant.now(),
                ImageInventory.Status.UNAVAILABLE, reason, List.of());
    }
}
