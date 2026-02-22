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

import static org.assertj.core.api.Assertions.assertThat;

class PoolDispatcherColdStartTest {

    @Test
    void dispatch_extractsColdStartHeaders() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setBody("{\"message\":\"ok\"}")
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Cold-Start", "true")
                .addHeader("X-Init-Duration-Ms", "250"));
        server.start();

        InvocationTask task = createTask(server);
        PoolDispatcher dispatcher = new PoolDispatcher(WebClient.builder().build());
        DispatchResult dr = dispatcher.dispatch(task).get();

        assertThat(dr.result().success()).isTrue();
        assertThat(dr.coldStart()).isTrue();
        assertThat(dr.initDurationMs()).isEqualTo(250L);

        server.shutdown();
    }

    @Test
    void dispatch_warmStart_noColdStartHeaders() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setBody("{\"message\":\"ok\"}")
                .addHeader("Content-Type", "application/json"));
        server.start();

        InvocationTask task = createTask(server);
        PoolDispatcher dispatcher = new PoolDispatcher(WebClient.builder().build());
        DispatchResult dr = dispatcher.dispatch(task).get();

        assertThat(dr.result().success()).isTrue();
        assertThat(dr.coldStart()).isFalse();
        assertThat(dr.initDurationMs()).isNull();

        server.shutdown();
    }

    private InvocationTask createTask(MockWebServer server) {
        String endpoint = server.url("/invoke").toString();
        FunctionSpec spec = new FunctionSpec(
                "test-fn", "image", null, Map.of(), null,
                5000, 1, 10, 3, endpoint, ExecutionMode.POOL, null, null, null
        );
        return new InvocationTask(
                "exec-cs", "test-fn", spec,
                new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1
        );
    }
}
