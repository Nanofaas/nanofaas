package it.unimib.datai.nanofaas.examples.qrcode;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.runtime.HandlerResponse;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QrCodeHandlerTest {
    private final QrCodeHandler handler = new QrCodeHandler();

    @Test
    void returnsBase64PngEnvelope() {
        var result = (HandlerResponse) handler.handle(new InvocationRequest(Map.of("text", "https://example.org/invite/abc", "size", 256), null));
        assertEquals(200, result.statusCode());
        assertEquals("image/png", result.headers().get("Content-Type"));
        assertEquals("base64", result.encoding());
        assertArrayEquals(new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a},
                java.util.Arrays.copyOf(Base64.getDecoder().decode((String) result.output()), 8));
    }

    @Test
    void rejectsInvalidInput() {
        for (Object input : new Object[] {Map.of(), Map.of("text", ""), Map.of("text", 42), Map.of("text", "x".repeat(1025)), Map.of("text", "https://example.org", "size", 127)}) {
            var result = handler.handle(new InvocationRequest(input, null));
            assertInstanceOf(HandlerResponse.class, result);
            assertEquals(422, ((HandlerResponse) result).statusCode());
        }
    }
}
