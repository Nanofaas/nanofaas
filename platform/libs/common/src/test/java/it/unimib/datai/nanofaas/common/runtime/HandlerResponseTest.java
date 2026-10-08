package it.unimib.datai.nanofaas.common.runtime;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class HandlerResponseTest {
    @Test
    void of_withStatusOnly_hasEmptyHeadersAndNoEncoding() {
        HandlerResponse r = HandlerResponse.of("body", 201);
        assertEquals("body", r.output());
        assertEquals(201, r.statusCode());
        assertTrue(r.headers().isEmpty());
        assertNull(r.encoding());
    }

    @Test
    void of_withHeaders_keepsThemAsGiven() {
        HandlerResponse r = HandlerResponse.of("body", 404, Map.of("Content-Type", "text/plain"));
        assertEquals(Map.of("Content-Type", "text/plain"), r.headers());
    }

    @Test
    void record_nullHeadersAllowed() {
        HandlerResponse r = new HandlerResponse("x", 200, null, null);
        assertNull(r.headers());
    }
}
