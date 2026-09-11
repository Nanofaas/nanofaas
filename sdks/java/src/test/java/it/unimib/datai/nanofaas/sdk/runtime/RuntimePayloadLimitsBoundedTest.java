package it.unimib.datai.nanofaas.sdk.runtime;

import org.junit.jupiter.api.Test;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimePayloadLimitsBoundedTest {
    @Test
    void normalizesFromOneBoundedSerializationWithoutUnlimitedTraversal() {
        AtomicInteger emitted = new AtomicInteger();
        SimpleModule module = new SimpleModule();
        module.addSerializer(StreamedValue.class, new ValueSerializer<>() {
            @Override
            public void serialize(StreamedValue value, JsonGenerator generator,
                                  SerializationContext context) throws tools.jackson.core.JacksonException {
                generator.writeStartArray();
                while (true) {
                    generator.writeString("0123456789");
                    emitted.incrementAndGet();
                }
            }
        });
        RuntimePayloadLimits limits = new RuntimePayloadLimits(
                JsonMapper.builder().addModule(module).build(), 64);

        assertThrows(BoundedJson.PayloadTooLargeException.class,
                () -> limits.normalize(new StreamedValue()));
        assertTrue(emitted.get() < 2_048);
    }

    @Test
    void returnsTheNormalizedTreeFromTheBoundedBytes() {
        RuntimePayloadLimits limits = new RuntimePayloadLimits(JsonMapper.builder().build(), 64);

        assertEquals("ok", limits.normalize(java.util.Map.of("result", "ok")).get("result").asText());
    }

    private static final class StreamedValue { }
}
