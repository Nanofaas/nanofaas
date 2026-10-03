package it.unimib.datai.nanofaas.modules.k8s;

import io.fabric8.kubernetes.client.KubernetesClient;
import it.unimib.datai.nanofaas.controlplane.deployment.ImageInventory;
import it.unimib.datai.nanofaas.controlplane.deployment.ImageInventorySource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Node.status is the kubelet's last reported cache, not an authoritative registry listing. */
final class KubernetesImageInventorySource implements ImageInventorySource {
    private final KubernetesClient client;
    KubernetesImageInventorySource(KubernetesClient client) { this.client = client; }
    @Override public ImageInventory snapshot(Duration timeout, int maxEntries) {
        try {
            List<ImageInventory.Entry> entries = new ArrayList<>();
            String next = null;
            long deadline = System.nanoTime() + timeout.toNanos();
            do {
                if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) return unavailable("TIMEOUT");
                var nodes = client.nodes().list(new io.fabric8.kubernetes.api.model.ListOptionsBuilder()
                        .withLimit(100L).withContinue(next).build());
                for (var node : nodes.getItems()) {
                    if (node.getStatus() == null || node.getStatus().getImages() == null) continue;
                    for (var image : node.getStatus().getImages()) {
                        if (entries.size() >= maxEntries) return unavailable("LIMIT_EXCEEDED");
                        if (image.getNames() == null || image.getNames().isEmpty()) continue;
                        entries.add(new ImageInventory.Entry(node.getMetadata().getName(),
                                image.getNames().stream().distinct().toList(), null, null));
                    }
                }
                next = nodes.getMetadata() == null ? null : nodes.getMetadata().getContinue();
            } while (next != null && !next.isBlank());
            return new ImageInventory("k8s", "kubernetes-node-status", Instant.now(),
                    ImageInventory.Status.PARTIAL, "SOURCE_AGE_UNKNOWN", entries);
        } catch (RuntimeException e) { return unavailable("BACKEND_ERROR"); }
    }
    private static ImageInventory unavailable(String reason) {
        return new ImageInventory("k8s", "kubernetes-node-status", Instant.now(),
                ImageInventory.Status.UNAVAILABLE, reason, List.of());
    }
}
