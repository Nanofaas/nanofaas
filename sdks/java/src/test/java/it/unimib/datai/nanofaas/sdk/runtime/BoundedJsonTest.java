package it.unimib.datai.nanofaas.sdk.runtime;

import org.junit.jupiter.api.Test;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedJsonTest {
    @Test
    void returnsTheSingleBoundedSerializationForReuse() {
        BoundedJson json = new BoundedJson(JsonMapper.builder().build());

        byte[] encoded = json.serialize(java.util.Map.of("ok", true), 32);

        assertArrayEquals("{\"ok\":true}".getBytes(StandardCharsets.UTF_8), encoded);
    }

    @Test
    void abortsCustomSerializationAtTheByteLimit() {
        AtomicInteger emittedValues = new AtomicInteger();
        SimpleModule module = new SimpleModule();
        module.addSerializer(UnboundedValue.class, new ValueSerializer<>() {
            @Override
            public void serialize(UnboundedValue value, JsonGenerator generator,
                                  SerializationContext context) throws tools.jackson.core.JacksonException {
                generator.writeStartArray();
                while (true) {
                    generator.writeString("0123456789");
                    emittedValues.incrementAndGet();
                }
            }
        });
        BoundedJson json = new BoundedJson(JsonMapper.builder().addModule(module).build());

        assertThrows(BoundedJson.PayloadTooLargeException.class,
                () -> json.serialize(new UnboundedValue(), 64));
        assertTrue(emittedValues.get() < 2_048,
                "the serializer may fill Jackson's fixed internal buffer but must not traverse indefinitely");
    }

    private static final class UnboundedValue { }
}
