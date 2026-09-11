package it.unimib.datai.nanofaas.sdk.lite.handler;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedJsonTest {
    @Test
    void returnsTheSingleBoundedSerializationForReuse() {
        BoundedJson json = new BoundedJson(new ObjectMapper());

        byte[] encoded = json.serialize(java.util.Map.of("ok", true), 32);

        assertArrayEquals("{\"ok\":true}".getBytes(StandardCharsets.UTF_8), encoded);
    }

    @Test
    void abortsCustomSerializationAtTheByteLimit() {
        AtomicInteger emittedValues = new AtomicInteger();
        SimpleModule module = new SimpleModule();
        module.addSerializer(UnboundedValue.class, new JsonSerializer<>() {
            @Override
            public void serialize(UnboundedValue value, JsonGenerator generator,
                                  SerializerProvider serializers) throws IOException {
                generator.writeStartArray();
                while (true) {
                    generator.writeString("0123456789");
                    emittedValues.incrementAndGet();
                }
            }
        });
        BoundedJson json = new BoundedJson(new ObjectMapper().registerModule(module));

        assertThrows(BoundedJson.PayloadTooLargeException.class,
                () -> json.serialize(new UnboundedValue(), 64));
        assertTrue(emittedValues.get() < 2_048,
                "the serializer may fill Jackson's fixed internal buffer but must not traverse indefinitely");
    }

    private static final class UnboundedValue { }
}
