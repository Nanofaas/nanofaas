package it.unimib.datai.nanofaas.controlplane.deployment;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ImageInventoryTest {
    @Test void preservesUntaggedIdentityAndCopiesMutableInputs() {
        List<String> refs = new ArrayList<>(List.of("echo:latest"));
        ImageInventory.Entry entry = new ImageInventory.Entry(null, refs, null, "sha256:abc");
        refs.clear();
        assertThat(entry.references()).containsExactly("echo:latest");
        assertThat(new ImageInventory.Entry(null, List.of(), null, "sha256:abc").imageId()).isEqualTo("sha256:abc");
        assertThatThrownBy(() -> new ImageInventory.Entry(null, List.of(), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImageInventory("docker", "daemon", Instant.EPOCH,
                ImageInventory.Status.UNAVAILABLE, "ERROR", List.of(entry)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
