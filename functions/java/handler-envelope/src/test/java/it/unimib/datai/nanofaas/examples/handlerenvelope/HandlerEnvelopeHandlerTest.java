package it.unimib.datai.nanofaas.examples.handlerenvelope;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HandlerEnvelopeHandlerTest {
    @Test
    void returns_the_body_and_lower_case_caller_header() {
        var output = new HandlerEnvelopeHandler().handle(new InvocationRequest(
                Map.of("message", "body-sentinel", "headers", Map.of("x-e2e-token", "forged")),
                null,
                Map.of("x-e2e-token", "header-sentinel")
        ));

        assertEquals(Map.of("body", "body-sentinel", "header", "header-sentinel"), output);
    }
}
