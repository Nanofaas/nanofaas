package it.unimib.datai.nanofaas.controlplane.deployment;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ImageInventoryTest {
    @Test void preservesUntaggedIdentityAndCopiesMutableInputs() {
        List<String> refs = new ArrayList<>(List.of("echo:latest"));
        ImageInventory.Entry entry = new ImageInventory.Entry(null, refs, null, "sha256:abc");
        refs.clear();
        assertEquals(List.of("echo:latest"), entry.references());
        assertEquals("sha256:abc", new ImageInventory.Entry(null, List.of(), null, "sha256:abc").imageId());
        assertThrows(IllegalArgumentException.class, () -> new ImageInventory.Entry(null, List.of(), null, null));
        assertThrows(IllegalArgumentException.class, () -> new ImageInventory("docker", "daemon", Instant.EPOCH,
                ImageInventory.Status.UNAVAILABLE, "ERROR", List.of(entry)));
    }
}
