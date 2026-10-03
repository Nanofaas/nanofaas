package it.unimib.datai.nanofaas.modules.containerddeploymentprovider;

import io.nanofaas.containerd.spi.ContainerdClient;
import io.nanofaas.containerd.spi.Images;
import io.nanofaas.containerd.Image;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import it.unimib.datai.nanofaas.controlplane.deployment.ImageInventory;

class ContainerdImageInventorySourceTest {
    @Test void listsTheConfiguredNamespaceAndPreservesDigest() {
        var client = mock(ContainerdClient.class);
        var images = mock(Images.class);
        when(client.namespace()).thenReturn("custom-functions");
        when(client.images()).thenReturn(images);
        when(images.list()).thenReturn(List.of(new Image("repo/fn:v1", "sha256:abc", 1024, Instant.now(), Map.of())));
        var source = new ContainerdImageInventorySource(client);
        var result = source.snapshot(Duration.ofSeconds(2), 5000);
        assertThat(result.scope()).isEqualTo("containerd-namespace:custom-functions");
        assertThat(result.entries().getFirst().digest()).isEqualTo("sha256:abc");
        assertThat(source.snapshot(Duration.ofSeconds(2), 0).reasonCode()).isEqualTo("LIMIT_EXCEEDED");
        when(images.list()).thenReturn(List.of());
        assertThat(source.snapshot(Duration.ofSeconds(2), 5000).status()).isEqualTo(ImageInventory.Status.AVAILABLE);
        when(images.list()).thenThrow(new IllegalStateException("backend unavailable"));
        assertThat(source.snapshot(Duration.ofSeconds(2), 5000).status()).isEqualTo(ImageInventory.Status.UNAVAILABLE);
        verify(client, never()).close();
    }
}
