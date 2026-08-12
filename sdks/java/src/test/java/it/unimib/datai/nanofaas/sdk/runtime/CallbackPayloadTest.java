package it.unimib.datai.nanofaas.sdk.runtime;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.StringNode;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CallbackPayloadTest {
    @Test
    void success_hasNullEnvelopeFieldsByDefault() {
        CallbackPayload p = CallbackPayload.success(new StringNode("ok"));
        assertNull(p.statusCode());
        assertNull(p.headers());
        assertNull(p.encoding());
    }

    @Test
    void successWithEnvelope_carriesFields() {
        CallbackPayload p = CallbackPayload.successWithEnvelope(
                new StringNode("ok"), 201, Map.of("Location", "/x"), "base64");
        assertTrue(p.success());
        assertEquals(201, p.statusCode());
        assertEquals("/x", p.headers().get("Location"));
        assertEquals("base64", p.encoding());
    }

    @Test
    void serializedFieldNamesMatchInvocationResult() {
        // CallbackPayload is serialized here and deserialized as InvocationResult on the
        // control plane (/internal/executions/{id}:complete). The two records are never
        // checked against each other by the compiler — only this test catches a rename.
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        String json = mapper.writeValueAsString(CallbackPayload.successWithEnvelope(
                new StringNode("ok"), 201, Map.of("Location", "/x"), "base64"));
        var received = mapper.readValue(json,
                it.unimib.datai.nanofaas.common.model.InvocationResult.class);
        assertTrue(received.success());
        assertEquals(201, received.statusCode());
        assertEquals("/x", received.headers().get("Location"));
        assertEquals("base64", received.encoding());
    }
}
