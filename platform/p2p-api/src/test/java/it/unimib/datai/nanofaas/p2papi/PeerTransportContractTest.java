package it.unimib.datai.nanofaas.p2papi;
import org.junit.jupiter.api.Test;
import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;
class PeerTransportContractTest {
    @Test void requiresExplicitHttpEndpointAndIncarnation() {
        assertThrows(IllegalArgumentException.class, () -> new PeerEndpoint("a", "", URI.create("http://a/invoke")));
        assertThrows(IllegalArgumentException.class, () -> new PeerEndpoint("a", "run", URI.create("tcp://a:7000")));
        assertEquals("http://a/invoke", new PeerEndpoint("a", "run", URI.create("http://a/invoke")).invocationUri().toString());
    }
}
