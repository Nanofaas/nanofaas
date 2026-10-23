package it.unimib.datai.nanofaas.sdk.runtime;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.StringNode;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class CallbackLoggingTest {
    @Test void callbackWorkerHasOwnIdentityAndIsCleanAfterDeliveryFailure() throws Exception {
        var observed = new ArrayBlockingQueue<Map<String, String>>(2);
        var client = new CallbackClient(RestClient.create(), new RuntimeSettings(null, null, null, null), new JsonMapper()) {
            @Override public boolean sendSerializedResult(String id, byte[] body, String traceId, String attempt) {
                var context = MDC.getCopyOfContextMap(); observed.add(context == null ? Map.of() : Map.copyOf(context));
                return false;
            }
        };
        var executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2));
        var dispatcher = new CallbackDispatcher(client, executor);
        try {
            for (String id : new String[]{"one", "two"}) {
                assertTrue(dispatcher.submit(id, CallbackPayload.success(StringNode.valueOf("ok")), "trace-" + id));
                var fields = observed.poll(2, TimeUnit.SECONDS); assertNotNull(fields);
                assertEquals(id, fields.get("executionId")); assertEquals("trace-" + id, fields.get("traceId"));
                assertNull(executor.submit(() -> MDC.get("executionId")).get());
            }
        } finally { dispatcher.shutdown(); executor.shutdownNow(); }
    }
}
