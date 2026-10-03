package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListImagesCmd;
import com.github.dockerjava.api.model.Image;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import it.unimib.datai.nanofaas.controlplane.deployment.ImageInventory;

class DockerImageInventorySourceTest {
    @Test void listsActualCacheIncludingUntaggedImagesWithoutTakingClientOwnership() throws Exception {
        var client = mock(DockerClient.class);
        var command = mock(ListImagesCmd.class);
        var image = mock(Image.class);
        when(image.getId()).thenReturn("sha256:abc");
        when(image.getRepoTags()).thenReturn(new String[]{"<none>:<none>"});
        when(client.listImagesCmd()).thenReturn(command);
        when(command.withShowAll(true)).thenReturn(command);
        when(command.exec()).thenReturn(List.of(image));
        var source = new DockerImageInventorySource(client);
        var result = source.snapshot(Duration.ofSeconds(2), 5000);
        assertThat(result.status()).isEqualTo(ImageInventory.Status.AVAILABLE);
        assertThat(result.entries().getFirst().imageId()).isEqualTo("sha256:abc");
        assertThat(result.entries().getFirst().references()).isEmpty();
        assertThat(source.snapshot(Duration.ofSeconds(2), 0).reasonCode()).isEqualTo("LIMIT_EXCEEDED");
        verify(client, never()).close();
        when(command.exec()).thenThrow(new IllegalStateException("daemon unavailable"));
        assertThat(source.snapshot(Duration.ofSeconds(2), 5000).status()).isEqualTo(ImageInventory.Status.UNAVAILABLE);
    }
}
