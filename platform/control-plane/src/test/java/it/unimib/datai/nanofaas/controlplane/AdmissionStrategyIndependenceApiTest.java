package it.unimib.datai.nanofaas.controlplane;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

/**
 * Task 10 (issue #208) independence tests, written RED before the attempt/retry state machine
 * moves into {@code AttemptCoordinator}.
 *
 * <p>The binding constraint is the plan's own «il cambio non altera capability SYNC/ASYNC,
 * limiti, policy di ammissione o contratti HTTP»: switching the {@code SchedulingStrategy} via
 * the admin API must be invisible to every admission-policy observable. Task 8 breached exactly
 * this once (sync admission ran under the wrong profile until a reviewer caught it), which is
 * why this class pins the ENTIRE admission-observable surface (the {@code :enqueue} 501, the
 * sync-queue depth/estimated-wait threshold, the {@code Retry-After} value, and the reject
 * reason the wait estimator produces) before AND after a real strategy switch through
 * {@code SchedulerControl} (backed by the real {@code SchedulerEngine}, not a fake).
 *
 * <p>Gated at the CLASS level by {@code nanofaas.selectedControlPlaneModules}, not by an
 * in-method {@code Assumptions} check (Task 10 step 4 fix, issue #208): this class needs
 * {@code per-function} (from async-queue) AND {@code shared-queue} (from sync-queue) both on the
 * classpath to switch between them, and {@code nanofaas.scheduler.strategy=per-function} below
 * fails Spring context creation outright under a sync-queue-only profile — well before any
 * method-body {@code Assumptions} check would ever run. Discovered by actually running the
 * four-profile matrix for this step, which step 1 never did.
 */
@EnabledIfSystemProperty(named = "nanofaas.selectedControlPlaneModules", matches = ".*\\basync-queue\\b.*")
@EnabledIfSystemProperty(named = "nanofaas.selectedControlPlaneModules", matches = ".*\\bsync-queue\\b.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "nanofaas.rate.maxPerSecond=1000",
                "nanofaas.defaults.timeoutMs=2000",
                "nanofaas.defaults.concurrency=2",
                "nanofaas.defaults.queueSize=10",
                "nanofaas.defaults.maxRetries=3",
                "nanofaas.registry.path=build/test-admission-strategy-independence-functions.json",
                "nanofaas.admin.runtime-config.enabled=true",
                // Pin the SYNC admission profile explicitly, as SyncQueueBackpressureApiTest
                // does: with both queue modules on the classpath the default profile is
                // FUNCTION_QUEUE, and this test's whole point is to exercise sync admission
                // regardless of which SchedulingStrategy the scheduler namespace picks.
                "nanofaas.admission.profile=sync-queue",
                "nanofaas.scheduler.strategy=per-function",
                "sync-queue.enabled=true",
                "sync-queue.admission-enabled=true",
                "sync-queue.max-estimated-wait=0s",
                "sync-queue.max-queue-wait=2s",
                "sync-queue.max-depth=200",
                "sync-queue.retry-after-seconds=2",
                "sync-queue.throughput-window=30s",
                "sync-queue.per-function-min-samples=50"
        })
@AutoConfigureWebTestClient
class AdmissionStrategyIndependenceApiTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private FunctionService functionService;

    @Test
    void switchingStrategyAloneLeavesSyncAdmissionContractUnchanged() {
        functionService.remove("echo");
        functionService.register(new FunctionSpec(
                "echo", "local", null, Map.of(), null,
                1000, 1, 10, 3, null,
                ExecutionMode.LOCAL, null, null, null
        ));

        // Baseline, strategy = per-function: :enqueue stays disabled under the sync profile,
        // and the sync queue rejects on the same threshold/estimate/Retry-After.
        assertEnqueueDisabled();
        assertSyncRejectionUnchanged();

        // The ONLY change: switch the scheduler's SchedulingStrategy through the real admin
        // API / real SchedulerEngine. Nothing else in this test's configuration moves.
        switchStrategyTo("shared-queue");

        // Re-assert the identical admission-policy observables. A regression here is exactly
        // the Task 8 class of bug: admission running under (or leaking through) the wrong
        // profile after a strategy change that should be invisible to it.
        assertEnqueueDisabled();
        assertSyncRejectionUnchanged();
    }

    private void switchStrategyTo(String strategy) {
        long revision = currentRevision();
        webTestClient.patch().uri("/v1/admin/runtime-config/scheduler")
                .bodyValue("{\"expectedRevision\":%d,\"values\":{\"strategy\":\"%s\"}}".formatted(revision, strategy))
                .header("Content-Type", "application/json")
                .exchange()
                .expectStatus().isOk();
        // Read the selection back from the engine's own live selection (SchedulerControl.snapshot,
        // which this namespace body serves), not from the PATCH response's echo of the request:
        // without this, a switchTo that silently did nothing would leave every "the switch changed
        // nothing" assertion below green — the test would prove the invariance of a configuration
        // that was never switched.
        webTestClient.get().uri("/v1/admin/runtime-config/scheduler")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.strategy").isEqualTo(strategy);
    }

    /** {@code GET /v1/admin/runtime-config} returns the {@code RuntimeConfigSnapshot} envelope
     * directly (top-level {@code revision}), unlike the PATCH response's nested one. */
    private long currentRevision() {
        String body = new String(webTestClient.get().uri("/v1/admin/runtime-config")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .returnResult()
                .getResponseBody());
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"revision\"\\s*:\\s*(\\d+)").matcher(body);
        if (!m.find()) {
            throw new IllegalStateException("no revision field in admin runtime-config envelope: " + body);
        }
        return Long.parseLong(m.group(1));
    }

    private void assertEnqueueDisabled() {
        webTestClient.post()
                .uri("/v1/functions/echo:enqueue")
                .bodyValue(new InvocationRequest("payload", Map.of()))
                .exchange()
                .expectStatus().isEqualTo(501);
    }

    private void assertSyncRejectionUnchanged() {
        webTestClient.post()
                .uri("/v1/functions/echo:invoke")
                .bodyValue(new InvocationRequest("payload", Map.of()))
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("Retry-After", "2")
                .expectHeader().valueEquals("X-Queue-Reject-Reason", "est_wait");
    }
}
