package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class P2pPropertiesTest {
    @Test
    void defaultsAreInertAndUnbounded() {
        P2pProperties p = new P2pProperties(null, null, null, null, null, null, null, null, null, null, null);
        assertThat(p.enabled()).isFalse();
        assertThat(p.admin().enabled()).isFalse();
        assertThat(p.port()).isZero();
        assertThat(p.seeds()).isEmpty();
        assertThat(p.maxNeighbors()).isNull();
        assertThat(p.maxLatencyMs()).isNull();
        assertThat(p.pingInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(p.pingTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(p.stateFile()).isNull();
    }
}
