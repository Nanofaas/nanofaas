package it.unimib.datai.nanofaas.controlplane.dispatch;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PoolDispatcherTimeoutTest {

    @Test
    void dispatch_slowServer_returnsPoolTimeout() throws Exception {
        MockWebServer server = new MockWebServer();
        // Respond after 2 seconds delay
        server.enqueue(new MockResponse()
                .setBody("{\"message\":\"ok\"}")
                .addHeader("Content-Type", "application/json")
                .setBodyDelay(2, TimeUnit.SECONDS));
        server.start();

        String endpoint = server.url("/invoke").toString();

        // Function with 200ms timeout
        FunctionSpec spec = new FunctionSpec(
                "pool-fn", "image", null, Map.of(), null,
                200, 1, 10, 3, endpoint, ExecutionMode.POOL, null, null, null
        );

        InvocationTask task = new InvocationTask(
                "exec-timeout", "pool-fn", spec,
                new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1
        );

        PoolDispatcher dispatcher = new PoolDispatcher(WebClient.builder().build());
        InvocationResult result = dispatcher.dispatch(task).get();

        assertThat(result.success()).isFalse();
        assertThat(result.error().code()).isEqualTo("POOL_TIMEOUT");
        assertThat(result.error().message()).contains("200ms");

        server.shutdown();
    }

    @Test
    void dispatch_missingEndpoint_returnsPoolEndpointMissing() throws Exception {
        FunctionSpec spec = new FunctionSpec(
                "pool-fn", "image", null, Map.of(), null,
                1000, 1, 10, 3, null, ExecutionMode.POOL, null, null, null
        );

        InvocationTask task = new InvocationTask(
                "exec-no-ep", "pool-fn", spec,
                new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1
        );

        PoolDispatcher dispatcher = new PoolDispatcher(WebClient.builder().build());
        InvocationResult result = dispatcher.dispatch(task).get();

        assertThat(result.success()).isFalse();
        assertThat(result.error().code()).isEqualTo("POOL_ENDPOINT_MISSING");
    }

    @Test
    void dispatch_serverError_returnsPoolError() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setResponseCode(500).setBody("Internal Server Error"));
        server.start();

        String endpoint = server.url("/invoke").toString();

        FunctionSpec spec = new FunctionSpec(
                "pool-fn", "image", null, Map.of(), null,
                1000, 1, 10, 3, endpoint, ExecutionMode.POOL, null, null, null
        );

        InvocationTask task = new InvocationTask(
                "exec-err", "pool-fn", spec,
                new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1
        );

        PoolDispatcher dispatcher = new PoolDispatcher(WebClient.builder().build());
        InvocationResult result = dispatcher.dispatch(task).get();

        assertThat(result.success()).isFalse();
        assertThat(result.error().code()).isEqualTo("POOL_ERROR");

        server.shutdown();
    }
}
