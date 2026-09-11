package it.unimib.datai.nanofaas.sdk.lite.callback;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallbackClientBoundedSerializationTest {
    @Test
    void directCallbackSerializationAbortsCustomValuesAtThePayloadCap() {
        AtomicInteger emitted = new AtomicInteger();
        SimpleModule module = new SimpleModule();
        module.addSerializer(UnboundedValue.class, new JsonSerializer<>() {
            @Override public void serialize(UnboundedValue value, JsonGenerator generator,
                                            SerializerProvider serializers) throws IOException {
                generator.writeStartArray();
                while (true) { generator.writeString("0123456789"); emitted.incrementAndGet(); }
            }
        });
        CallbackClient client = new CallbackClient(new ObjectMapper().registerModule(module),
                "http://127.0.0.1:1", 64);

        assertFalse(client.sendResult("execution", InvocationResult.success(new UnboundedValue()), null));
        assertTrue(emitted.get() < 2_048);
    }

    private static final class UnboundedValue { }
}
