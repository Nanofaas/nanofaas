package it.unimib.datai.nanofaas.examples.binaryenvelope;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.runtime.HandlerResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BinaryEnvelopeHandlerTest {
    @Test void returns_fixed_base64_binary_envelope() {
        var response = (HandlerResponse) new BinaryEnvelopeHandler().handle(new InvocationRequest(Map.of(), null, Map.of()));
        assertEquals("AAEC", response.output());
        assertEquals(200, response.statusCode());
        assertEquals(Map.of("Content-Type", "application/octet-stream"), response.headers());
        assertEquals("base64", response.encoding());
    }
}
