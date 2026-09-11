package it.unimib.datai.nanofaas.controlplane;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.capacity.InvocationCapacity;
import it.unimib.datai.nanofaas.controlplane.capacity.WaiterCapacity;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.LocalDispatcher;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionService;
import it.unimib.datai.nanofaas.controlplane.service.InvocationEnqueuer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "nanofaas.rate.maxPerSecond=10000",
                "nanofaas.registry.path=build/test-invocation-quota-http-functions.json",
                "nanofaas.invocation-capacity.executions-global=2",
                "nanofaas.invocation-capacity.executions-per-function=2",
                "nanofaas.invocation-capacity.canonical-input-bytes-global=512",
                "nanofaas.invocation-capacity.canonical-input-bytes-per-function=512",
                "nanofaas.invocation-capacity.physical-input-copy-bytes-global=512",
                "nanofaas.invocation-capacity.physical-input-copy-bytes-per-function=512",
                "nanofaas.invocation-capacity.waiters-global=1",
                "nanofaas.invocation-capacity.waiters-per-function=1",
                "nanofaas.invocation-capacity.retained-input-max-bytes-per-execution=512",
                "sync-queue.enabled=false"
        })
@AutoConfigureWebTestClient(timeout = "5s")
class InvocationQuotaHttpTest {
    private static final String FUNCTION = "quota-http";

    @Autowired private WebTestClient client;
    @Autowired private FunctionService functions;
    @Autowired private InvocationCapacity invocations;
    @Autowired private WaiterCapacity waiters;
    @Autowired private InvocationEnqueuer enqueuer;
    @MockitoBean private LocalDispatcher dispatcher;

    private CompletableFuture<DispatchResult> pending;

    @BeforeEach
    void setUp() {
        reset(dispatcher);
        pending = new CompletableFuture<>();
        when(dispatcher.dispatch(any())).thenReturn(pending);
        functions.remove(FUNCTION);
        functions.register(new FunctionSpec(
                FUNCTION, "local", null, Map.of(), null,
                10_000, 1, 32, 0, null, ExecutionMode.LOCAL, null, null, null));
    }

    @AfterEach
    void drain() {
        pending.complete(DispatchResult.warm(InvocationResult.success("drained")));
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(invocations.executionReservedGlobally()).isZero();
            assertThat(invocations.inputReservedGlobally()).isZero();
            assertThat(invocations.physicalInputCopyReservedGlobally()).isZero();
            assertThat(waiters.reservedGlobally()).isZero();
        });
        functions.remove(FUNCTION);
    }

    @Test
    void syncExecutionSaturationReturnsTheStableQuotaError() {
        Assumptions.assumeTrue(enqueuer.supportsAsync(), "requires the async-queue profile");
        enqueueAccepted("first");
        enqueueAccepted("second");
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(invocations.executionReservedGlobally()).isEqualTo(2));

        assertQuotaError("/v1/functions/{name}:invoke", "execution", "ok");
    }

    @Test
    void asyncExecutionSaturationReturnsTheStableQuotaError() {
        Assumptions.assumeTrue(enqueuer.supportsAsync(), "requires the async-queue profile");
        enqueueAccepted("first");
        enqueueAccepted("second");
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(invocations.executionReservedGlobally()).isEqualTo(2));

        assertQuotaError("/v1/functions/{name}:enqueue", "execution", "ok");
    }

    @Test
    void asyncCanonicalInputSaturationReturnsTheStableQuotaError() {
        Assumptions.assumeTrue(enqueuer.supportsAsync(), "requires the async-queue profile");

        enqueueAccepted("x");
        assertQuotaError("/v1/functions/{name}:enqueue", "input", "x".repeat(220));

        assertThat(invocations.executionReservedGlobally()).isOne();
        assertThat(invocations.inputReservedGlobally()).isPositive().isLessThan(512);
    }

    @Test
    void pendingReplayWaiterSaturationReturnsTheStableQuotaErrorWithoutNewExecution() throws Exception {
        CompletableFuture<EntityExchangeResult<byte[]>> first = CompletableFuture.supplyAsync(() ->
                client.post().uri("/v1/functions/{name}:invoke", FUNCTION)
                        .header("Idempotency-Key", "shared")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(new InvocationRequest("ok", Map.of()))
                        .exchange().expectBody().returnResult());
        awaitOwners(1, 1);
        long canonicalBytes = invocations.inputReservedGlobally();

        client.post().uri("/v1/functions/{name}:invoke", FUNCTION)
                .header("Idempotency-Key", "shared")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new InvocationRequest("ok", Map.of()))
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("Retry-After", "1")
                .expectBody()
                .jsonPath("$.error").isEqualTo("invocation_quota_exceeded")
                .jsonPath("$.resource").isEqualTo("waiter");
        assertThat(invocations.executionReservedGlobally()).isOne();
        assertThat(invocations.inputReservedGlobally()).isEqualTo(canonicalBytes);

        pending.complete(DispatchResult.warm(InvocationResult.success("done")));
        assertThat(first.get(3, TimeUnit.SECONDS).getStatus().value()).isEqualTo(200);
    }

    private void assertQuotaError(String uri, String resource, Object input) {
        client.post().uri(uri, FUNCTION)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new InvocationRequest(input, Map.of()))
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("Retry-After", "1")
                .expectBody()
                .jsonPath("$.error").isEqualTo("invocation_quota_exceeded")
                .jsonPath("$.resource").isEqualTo(resource);
    }

    private void enqueueAccepted(Object input) {
        client.post().uri("/v1/functions/{name}:enqueue", FUNCTION)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new InvocationRequest(input, Map.of()))
                .exchange().expectStatus().isAccepted();
    }

    private void awaitOwners(long executions, long attachedWaiters) {
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(invocations.executionReservedGlobally()).isEqualTo(executions);
            assertThat(invocations.inputReservedGlobally()).isPositive();
            assertThat(waiters.reservedGlobally()).isEqualTo(attachedWaiters);
        });
    }
}
