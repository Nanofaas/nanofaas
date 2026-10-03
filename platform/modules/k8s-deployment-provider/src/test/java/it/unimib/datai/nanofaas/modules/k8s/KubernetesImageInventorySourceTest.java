package it.unimib.datai.nanofaas.modules.k8s;

import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.NodeListBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import it.unimib.datai.nanofaas.controlplane.deployment.ImageInventory;

class KubernetesImageInventorySourceTest {
    @Test void paginatesNodesInsteadOfLoadingTheWholeClusterAtOnce() {
        var client = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        var first = new NodeListBuilder().withNewMetadata().withContinue("next").endMetadata().build();
        var second = new NodeListBuilder().withNewMetadata().endMetadata().build();
        when(client.nodes().list(any(io.fabric8.kubernetes.api.model.ListOptions.class))).thenReturn(first, second);
        var result = new KubernetesImageInventorySource(client).snapshot(Duration.ofSeconds(2), 5000);
        assertThat(result.status()).isEqualTo(ImageInventory.Status.PARTIAL);
        var options = org.mockito.ArgumentCaptor.forClass(io.fabric8.kubernetes.api.model.ListOptions.class);
        verify(client.nodes(), times(2)).list(options.capture());
        assertThat(options.getAllValues().getFirst().getLimit()).isEqualTo(100L);
        assertThat(options.getAllValues().getLast().getContinue()).isEqualTo("next");
    }
    @Test void preservesNodeIdentityAndMarksKubeletCacheAsPartial() {
        var client = mock(KubernetesClient.class, RETURNS_DEEP_STUBS);
        var node = new NodeBuilder().withNewMetadata().withName("worker-1").endMetadata()
                .withNewStatus().addNewImage().withNames("repo/fn:v1", "repo/fn@sha256:abc")
                .endImage().endStatus().build();
        when(client.nodes().list(any(io.fabric8.kubernetes.api.model.ListOptions.class)))
                .thenReturn(new NodeListBuilder().withItems(node).build());
        var source = new KubernetesImageInventorySource(client);
        var result = source.snapshot(Duration.ofSeconds(2), 5000);
        assertThat(result.status()).isEqualTo(ImageInventory.Status.PARTIAL);
        assertThat(result.reasonCode()).isEqualTo("SOURCE_AGE_UNKNOWN");
        assertThat(result.entries().getFirst().nodeId()).isEqualTo("worker-1");
        assertThat(result.entries().getFirst().references()).containsExactly("repo/fn:v1", "repo/fn@sha256:abc");
        assertThat(source.snapshot(Duration.ofSeconds(2), 0).reasonCode()).isEqualTo("LIMIT_EXCEEDED");
        when(client.nodes().list(any(io.fabric8.kubernetes.api.model.ListOptions.class)))
                .thenThrow(new IllegalStateException("forbidden"));
        assertThat(source.snapshot(Duration.ofSeconds(2), 5000).status()).isEqualTo(ImageInventory.Status.UNAVAILABLE);
    }
}
