package it.unimib.datai.nanofaas.sdk.runtime;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InvokeControllerBoundedOutputTest {
    @Test
    void rejectsCustomOutputBeforeBuildingAnUnlimitedJsonTree() {
        AtomicInteger emitted = new AtomicInteger();
        SimpleModule module = new SimpleModule();
        module.addSerializer(StreamedValue.class, new ValueSerializer<>() {
            @Override
            public void serialize(StreamedValue value, JsonGenerator generator,
                                  SerializationContext context) throws tools.jackson.core.JacksonException {
                generator.writeStartArray();
                for (int index = 0; index < 10_000; index++) {
                    generator.writeString("0123456789");
                    emitted.incrementAndGet();
                }
                generator.writeEndArray();
            }
        });
        var mapper = JsonMapper.builder().addModule(module).build();
        CallbackDispatcher callbacks = mock(CallbackDispatcher.class);
        HandlerRegistry registry = mock(HandlerRegistry.class);
        when(registry.resolve()).thenReturn(_ -> new StreamedValue());
        InvocationRuntimeContextResolver resolver = mock(InvocationRuntimeContextResolver.class);
        when(resolver.resolve(any(), any())).thenReturn(new InvocationRuntimeContext("exec", "trace"));
        InvokeController controller = new InvokeController(callbacks, registry, resolver,
                new ColdStartTracker(), new HandlerExecutor(1_000), new JsonOutputNormalizer(mapper),
                new RuntimePayloadLimits(mapper, 64));

        var response = controller.invoke(new InvocationRequest(Map.of(), null), "exec", "trace", "1");

        assertEquals(500, response.getStatusCode().value());
        assertEquals("RUNTIME_OUTPUT_TOO_LARGE",
                ((Map<?, ?>) ((Map<?, ?>) response.getBody()).get("error")).get("code"));
        assertTrue(emitted.get() < 2_048,
                "the controller must bound serialization before materializing a JsonNode");
    }

    private static final class StreamedValue { }
}
