package it.unimib.datai.nanofaas.controlplane.dispatch;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoolDispatcherTest {
    @Test
    void poolDispatchCallsEndpoint() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setBody("{\"message\":\"ok\"}")
                .addHeader("Content-Type", "application/json"));
        server.start();

        String endpoint = server.url("/invoke").toString();

        FunctionSpec spec = new FunctionSpec(
                "pool-fn",
                "image",
                null,
                Map.of(),
                null,
                1000,
                1,
                10,
                3,
                endpoint,
                ExecutionMode.POOL,
                null,
                null,
                null
        );

        InvocationTask task = new InvocationTask(
                "exec-pool",
                "pool-fn",
                spec,
                new InvocationRequest("payload", Map.of()),
                null,
                null,
                Instant.now(),
                1
        );

        PoolDispatcher dispatcher = new PoolDispatcher(WebClient.builder().build());
        DispatchResult dr = dispatcher.dispatch(task).get();

        assertTrue(dr.result().success());
        assertNotNull(dr.result().output());
        assertFalse(dr.coldStart());
        assertEquals(1, server.getRequestCount());
        server.shutdown();
    }

    @Test
    void poolDispatchHandlesTextPlain() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setBody("plain-output")
                .addHeader("Content-Type", "text/plain"));
        server.start();

        String endpoint = server.url("/invoke").toString();

        FunctionSpec spec = new FunctionSpec(
                "pool-fn",
                "image",
                null,
                Map.of(),
                null,
                1000,
                1,
                10,
                3,
                endpoint,
                ExecutionMode.POOL,
                null,
                null,
                null
        );

        InvocationTask task = new InvocationTask(
                "exec-pool",
                "pool-fn",
                spec,
                new InvocationRequest("payload", Map.of()),
                null,
                null,
                Instant.now(),
                1
        );

        PoolDispatcher dispatcher = new PoolDispatcher(WebClient.builder().build());
        DispatchResult dr = dispatcher.dispatch(task).get();

        assertTrue(dr.result().success());
        assertEquals("plain-output", dr.result().output());
        server.shutdown();
    }
}
