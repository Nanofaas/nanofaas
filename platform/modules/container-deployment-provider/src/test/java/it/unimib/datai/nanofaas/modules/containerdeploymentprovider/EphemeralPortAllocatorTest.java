package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

class EphemeralPortAllocatorTest {

    /**
     * Replicas start concurrently, so a port handed out may not be bound by its container yet when
     * the next one is drawn: the operating system is then free to return it again, and two
     * containers would ask for the same host port. Across a thousand draws from a range of a few
     * tens of thousands, a repeat is all but certain unless the allocator remembers what it gave.
     */
    @Test
    void neverHandsOutTheSamePortTwiceInABurst() {
        var allocator = new EphemeralPortAllocator("127.0.0.1");
        var seen = new HashSet<Integer>();
        for (int draw = 0; draw < 1000; draw++) {
            int port = allocator.nextPort();
            assertThat(seen.add(port)).as("draw %d returned port %d again", draw, port).isTrue();
        }
    }
}
