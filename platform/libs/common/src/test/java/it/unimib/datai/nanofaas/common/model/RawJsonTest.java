package it.unimib.datai.nanofaas.common.model;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RawJsonTest {
    private static RawJson raw(String json) {
        return RawJson.of(json.getBytes(StandardCharsets.UTF_8), 1, 0, 0);
    }

    @Test
    void anyMapperWritesItsTextVerbatimInsideAnEnvelope() {
        // Spacing, number spelling and non-ASCII text are the caller's; none of it is rewritten.
        var request = new InvocationRequest(raw("{\"a\": 1.50, \"città\":[ ]}"), Map.of("k", "v"), null);

        assertEquals("{\"input\":{\"a\": 1.50, \"città\":[ ]},\"metadata\":{\"k\":\"v\"},\"headers\":null}",
                JsonMapper.shared().writeValueAsString(request));
    }

    @Test
    void aTextLargerThanTheGeneratorBufferAndACharBasedWriterAreWrittenVerbatimToo() {
        String large = "[\"" + "città-".repeat(20_000) + "\"]";
        var request = new InvocationRequest(raw(large), null, null);
        String expected = "{\"input\":" + large + ",\"metadata\":null,\"headers\":null}";

        assertEquals(expected, new String(JsonMapper.shared().writeValueAsBytes(request), StandardCharsets.UTF_8));
        assertEquals(expected, JsonMapper.shared().writeValueAsString(request));
    }

    @Test
    void equalityIsByContent() {
        assertEquals(raw("[1]"), raw("[1]"));
        assertEquals(raw("[1]").hashCode(), raw("[1]").hashCode());
    }
}
