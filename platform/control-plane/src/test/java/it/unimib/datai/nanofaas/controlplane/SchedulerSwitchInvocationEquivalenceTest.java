package it.unimib.datai.nanofaas.controlplane;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionService;
import java.time.Duration;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Task 10 step 4 (issue #208): the invocation-level counterpart to the admission independence
 * pins. Those prove admission policy/capability are invisible to a strategy switch; this proves
 * the whole attempt path (dispatch, completion, the shared future a SYNC waiter blocks on, and
 * the archived outcome an ASYNC poller reads) survives a hot switch in BOTH directions, for BOTH
 * invocation kinds, through the real admin API and the real {@code SchedulerEngine} — not a fake.
 *
 * <p>Runs under the default (both-modules) profile: {@code per-function} and {@code
 * shared-queue} must both be on the classpath to switch between them, and {@code
 * nanofaas.admin.runtime-config.enabled=true} needs the runtime-config module for the admin API
 * itself (see {@link AdmissionStrategyIndependenceApiTest} for why an explicit two-module-only
 * {@code -PcontrolPlaneModules} selection is the WRONG way to ask for "both modules" — it drops
 * runtime-config and 404s the admin endpoint).
 */
@EnabledIfSystemProperty(named = "nanofaas.selectedControlPlaneModules", matches = ".*\\basync-queue\\b.*")
@EnabledIfSystemProperty(named = "nanofaas.selectedControlPlaneModules", matches = ".*\\bsync-queue\\b.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "nanofaas.rate.maxPerSecond=1000",
                "nanofaas.defaults.timeoutMs=5000",
                "nanofaas.defaults.concurrency=4",
                "nanofaas.defaults.queueSize=50",
                "nanofaas.defaults.maxRetries=3",
                "nanofaas.registry.path=build/test-scheduler-switch-equivalence-functions.json",
                "nanofaas.admin.runtime-config.enabled=true",
                "nanofaas.scheduler.strategy=per-function"
        })
@AutoConfigureWebTestClient
class SchedulerSwitchInvocationEquivalenceTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private FunctionService functionService;

    @Test
    void syncWaiterAndAsyncPollingObserveTheSameResultAcrossTwoStrategySwitches() {
        functionService.remove("echo");
        functionService.register(new FunctionSpec(
                "echo", "local", null, Map.of(), null,
                2000, 4, 50, 3, null,
                ExecutionMode.LOCAL, null, null, null
        ));

        // Round 1, strategy = per-function (the startup default).
        assertSyncInvokeEchoesItsPayload("sync-round-1");
        assertAsyncEnqueueEventuallyArchivesItsPayload("async-round-1");

        // Switch #1: per-function -> shared-queue, through the real admin API.
        switchStrategyTo("shared-queue");
        assertSyncInvokeEchoesItsPayload("sync-round-2");
        assertAsyncEnqueueEventuallyArchivesItsPayload("async-round-2");

        // Switch #2: shared-queue -> per-function, the other direction.
        switchStrategyTo("per-function");
        assertSyncInvokeEchoesItsPayload("sync-round-3");
        assertAsyncEnqueueEventuallyArchivesItsPayload("async-round-3");
    }

    /**
     * A LOCAL dispatch always echoes its own request input as output (see {@code
     * LocalDispatcher}), so the SYNC waiter observing anything other than its own payload back
     * would mean the attempt path misrouted or corrupted the result across the switch.
     */
    private void assertSyncInvokeEchoesItsPayload(String payload) {
        webTestClient.post()
                .uri("/v1/functions/echo:invoke")
                .bodyValue(new InvocationRequest(payload, Map.of()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("success")
                .jsonPath("$.output").isEqualTo(payload);
    }

    /**
     * The ASYNC counterpart: enqueue returns immediately (202, "queued"), and polling {@code
     * GET /v1/executions/{id}} is the ASYNC caller's only way to observe the same eventual
     * result a SYNC waiter would have received inline.
     */
    private void assertAsyncEnqueueEventuallyArchivesItsPayload(String payload) {
        byte[] enqueueBody = webTestClient.post()
                .uri("/v1/functions/echo:enqueue")
                .bodyValue(new InvocationRequest(payload, Map.of()))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.status").isEqualTo("queued")
                .returnResult()
                .getResponseBody();
        String executionId = extractExecutionId(new String(enqueueBody, java.nio.charset.StandardCharsets.UTF_8));

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                webTestClient.get().uri("/v1/executions/{id}", executionId)
                        .exchange()
                        .expectStatus().isOk()
                        .expectBody()
                        .jsonPath("$.status").isEqualTo("success")
                        .jsonPath("$.output").isEqualTo(payload));
    }

    private void switchStrategyTo(String strategy) {
        long revision = currentRevision();
        webTestClient.patch().uri("/v1/admin/runtime-config/scheduler")
                .bodyValue("{\"expectedRevision\":%d,\"values\":{\"strategy\":\"%s\"}}".formatted(revision, strategy))
                .header("Content-Type", "application/json")
                .exchange()
                .expectStatus().isOk();
    }

    private long currentRevision() {
        String body = new String(webTestClient.get().uri("/v1/admin/runtime-config")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .returnResult()
                .getResponseBody(), java.nio.charset.StandardCharsets.UTF_8);
        return extractLong(body, "revision");
    }

    private static String extractExecutionId(String body) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"executionId\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if (!m.find()) {
            throw new IllegalStateException("no executionId field in enqueue response: " + body);
        }
        return m.group(1);
    }

    private static long extractLong(String body, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\"" + field + "\"\\s*:\\s*(\\d+)").matcher(body);
        if (!m.find()) {
            throw new IllegalStateException("no " + field + " field in response: " + body);
        }
        return Long.parseLong(m.group(1));
    }
}
