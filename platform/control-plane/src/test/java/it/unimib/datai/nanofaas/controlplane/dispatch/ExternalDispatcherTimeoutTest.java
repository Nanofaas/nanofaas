package it.unimib.datai.nanofaas.controlplane.dispatch;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;
import io.netty.handler.timeout.ReadTimeoutException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalDispatcherTimeoutTest {

    @ParameterizedTest
    @CsvSource({"200,false", "500,false", "200,true"})
    void dispatch_nettyTimeoutBeforeOuterTimer_returnsTimeout(int status, boolean delayHeaders) throws Exception {
        CountDownLatch started = new CountDownLatch(Schedulers.DEFAULT_POOL_SIZE);
        CountDownLatch release = new CountDownLatch(1);
        List<Disposable> blockers = new ArrayList<>();
        AtomicReference<Throwable> transportError = new AtomicReference<>();
        try (MockWebServer server = new MockWebServer()) {
            MockResponse response = new MockResponse().setResponseCode(status)
                    .addHeader("Content-Type", "application/json").setBody("{\"message\":\"ok\"}");
            if (delayHeaders) {
                response.setHeadersDelay(2, TimeUnit.SECONDS);
            } else {
                response.setBodyDelay(2, TimeUnit.SECONDS);
            }
            server.enqueue(response);
            server.start();

            // Hold Reactor's timeout workers so the actual Netty response timer wins.
            // Both production deadlines stay at 200 ms; no sleeps decide the winner.
            for (int i = 0; i < Schedulers.DEFAULT_POOL_SIZE; i++) {
                blockers.add(Schedulers.parallel().schedule(() -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            WebClient client = WebClient.builder()
                    .filter((request, next) -> next.exchange(request)
                            .doOnError(transportError::set)
                            .map(received -> received.mutate()
                                    .body(body -> body.doOnError(transportError::set)).build()))
                    .build();
            FunctionSpec spec = new FunctionSpec(
                    "pool-fn", "image", null, Map.of(), null,
                    200, 1, 10, 3, server.url("/invoke").toString(), ExecutionMode.EXTERNAL, null, null, null);
            InvocationTask task = new InvocationTask(
                    "exec-netty-timeout", "pool-fn", spec,
                    new InvocationRequest("payload", Map.of()),
                    null, null, Instant.now(), 1, InvocationKind.SYNC);

            DispatchResult result = new ExternalDispatcher(client).dispatch(task).get(5, TimeUnit.SECONDS);
            Throwable cause = transportError.get();
            assertThat(cause).isNotNull();
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            assertThat(cause).isInstanceOf(ReadTimeoutException.class);
            assertThat(result.result().success()).isFalse();
            assertThat(result.result().error().code())
                    .as("transport error: %s", transportError.get()).isEqualTo("EXTERNAL_TIMEOUT");
            assertThat(result.result().error().message()).contains("200ms");
        } finally {
            release.countDown();
            blockers.forEach(Disposable::dispose);
        }
    }

    @Test
    void dispatch_connectionRefused_remainsExternalError() throws Exception {
        String endpoint;
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            endpoint = server.url("/invoke").toString();
        }
        FunctionSpec spec = new FunctionSpec(
                "pool-fn", "image", null, Map.of(), null,
                1000, 1, 10, 3, endpoint, ExecutionMode.EXTERNAL, null, null, null);
        InvocationTask task = new InvocationTask(
                "exec-connect-error", "pool-fn", spec,
                new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1, InvocationKind.SYNC);

        DispatchResult result = new ExternalDispatcher(WebClient.builder().build())
                .dispatch(task).get(5, TimeUnit.SECONDS);
        assertThat(result.result().success()).isFalse();
        assertThat(result.result().error().code()).isEqualTo("EXTERNAL_ERROR");
    }

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
                200, 1, 10, 3, endpoint, ExecutionMode.EXTERNAL, null, null, null
        );

        InvocationTask task = new InvocationTask(
                "exec-timeout", "pool-fn", spec,
                new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1
        ,
        InvocationKind.SYNC
    );

        ExternalDispatcher dispatcher = new ExternalDispatcher(WebClient.builder().build());
        DispatchResult dr = dispatcher.dispatch(task).get();

        assertThat(dr.result().success()).isFalse();
        assertThat(dr.result().error().code()).isEqualTo("EXTERNAL_TIMEOUT");
        assertThat(dr.result().error().message()).contains("200ms");

        server.shutdown();
    }

    @Test
    void dispatch_missingEndpoint_returnsPoolEndpointMissing() throws Exception {
        FunctionSpec spec = new FunctionSpec(
                "pool-fn", "image", null, Map.of(), null,
                1000, 1, 10, 3, null, ExecutionMode.EXTERNAL, null, null, null
        );

        InvocationTask task = new InvocationTask(
                "exec-no-ep", "pool-fn", spec,
                new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1
        ,
        InvocationKind.SYNC
    );

        ExternalDispatcher dispatcher = new ExternalDispatcher(WebClient.builder().build());
        DispatchResult dr = dispatcher.dispatch(task).get();

        assertThat(dr.result().success()).isFalse();
        assertThat(dr.result().error().code()).isEqualTo("EXTERNAL_ENDPOINT_MISSING");
    }

    @Test
    void dispatch_serverError_returnsPoolError() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setResponseCode(500).setBody("Internal Server Error"));
        server.start();

        String endpoint = server.url("/invoke").toString();

        FunctionSpec spec = new FunctionSpec(
                "pool-fn", "image", null, Map.of(), null,
                1000, 1, 10, 3, endpoint, ExecutionMode.EXTERNAL, null, null, null
        );

        InvocationTask task = new InvocationTask(
                "exec-err", "pool-fn", spec,
                new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1
        ,
        InvocationKind.SYNC
    );

        ExternalDispatcher dispatcher = new ExternalDispatcher(WebClient.builder().build());
        DispatchResult dr = dispatcher.dispatch(task).get();

        assertThat(dr.result().success()).isFalse();
        assertThat(dr.result().error().code()).isEqualTo("EXTERNAL_ERROR");

        server.shutdown();
    }
}
