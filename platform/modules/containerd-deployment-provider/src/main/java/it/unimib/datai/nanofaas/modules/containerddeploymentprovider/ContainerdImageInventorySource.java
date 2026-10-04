package it.unimib.datai.nanofaas.modules.containerddeploymentprovider;

import io.nanofaas.containerd.spi.ContainerdClient;
import it.unimib.datai.nanofaas.controlplane.deployment.ImageInventory;
import it.unimib.datai.nanofaas.controlplane.deployment.ImageInventorySource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

final class ContainerdImageInventorySource implements ImageInventorySource {
    private final ContainerdClient client;
    ContainerdImageInventorySource(ContainerdClient client) { this.client = client; }
    @Override public ImageInventory snapshot(Duration timeout, int maxEntries) {
        String scope = "containerd-namespace:" + client.namespace();
        try {
            var images = client.images().list();
            if (images.size() > maxEntries) return unavailable(scope, "LIMIT_EXCEEDED");
            var entries = images.stream().map(image -> new ImageInventory.Entry(null,
                    List.of(image.name()), image.digest(), null)).toList();
            return new ImageInventory("containerd", scope, Instant.now(), ImageInventory.Status.AVAILABLE, null, entries);
        } catch (RuntimeException e) { return unavailable(scope, "BACKEND_ERROR"); }
    }
    private static ImageInventory unavailable(String scope, String reason) {
        return new ImageInventory("containerd", scope, Instant.now(), ImageInventory.Status.UNAVAILABLE, reason, List.of());
    }
}
