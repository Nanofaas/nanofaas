package it.unimib.datai.nanofaas.sdk.runtime;

import org.junit.jupiter.api.Test;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CallbackDispatcherBoundedSerializationTest {
    @Test
    void abortsPathologicalCallbackSerializationBeforeQueueing() {
        AtomicInteger emitted = new AtomicInteger();
        SimpleModule module = new SimpleModule();
        module.addSerializer(CallbackPayload.class, new ValueSerializer<>() {
            @Override
            public void serialize(CallbackPayload value, JsonGenerator generator,
                                  SerializationContext context) throws tools.jackson.core.JacksonException {
                generator.writeStartArray();
                while (true) {
                    generator.writeString("0123456789");
                    emitted.incrementAndGet();
                }
            }
        });
        CallbackClient client = new CallbackClient(mock(org.springframework.web.client.RestClient.class),
                mock(RuntimeSettings.class), JsonMapper.builder().addModule(module).build());
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1));
        CallbackDispatcher dispatcher = new CallbackDispatcher(client, executor, null, 1, 64, 64);
        try {
            assertEquals(CallbackDispatcher.SubmitResult.PAYLOAD_TOO_LARGE,
                    dispatcher.submit(dispatcher.reserveInvocation(), "exec",
                            CallbackPayload.error("E", "m"), null, null));
            assertTrue(emitted.get() < 2_048);
            assertEquals(0, dispatcher.pendingCallbackCount());
            assertTrue(executor.getQueue().isEmpty());
        } finally {
            dispatcher.shutdown();
            executor.shutdownNow();
        }
    }
}
