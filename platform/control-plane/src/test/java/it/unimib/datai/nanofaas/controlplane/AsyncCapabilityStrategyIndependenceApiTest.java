package it.unimib.datai.nanofaas.controlplane;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Task 10 (issue #208), RED companion to {@link AdmissionStrategyIndependenceApiTest}: the other
 * half of the plan's «il cambio non altera capability SYNC/ASYNC» constraint. Here the admission
 * profile is the legacy async-capable one (FUNCTION_QUEUE, the default with both queue modules
 * on the classpath and no explicit {@code nanofaas.admission.profile}), and the assertion is that
 * a real {@code SchedulingStrategy} switch to {@code shared-queue} — normally associated with the
 * sync-queue module — does NOT flip the admission profile or disable {@code :enqueue}. The
 * scheduling strategy and the admission policy are distinct configuration axes (plan-context
 * decision 10); this test is what would fail if a future change conflated them.
 *
 * <p>Mutation-proof (Task 10 step 1b, issue #208): deliberately does NOT set
 * {@code sync-queue.enabled=false}. Task 8's own bug (fix round C1) only manifested in exactly
 * this "both-modules default artefact" shape — {@code sync-queue.enabled} left at its
 * {@code application.yml} default of {@code true}, no explicit {@code nanofaas.admission.profile}
 * — where {@code EngineSyncQueueGateway.enabled()} used to return {@code configSource
 * .syncQueueEnabled()} alone and silently routed every sync invocation through the sync queue's
 * depth/estimated-wait admission even though the resolved profile was FUNCTION_QUEUE. An earlier
 * version of this test set {@code sync-queue.enabled=false}, which defeated that exact scenario
 * and passed unchanged whether or not the C1 gate was present — a pin that did not pin. Leaving
 * sync-queue enabled and giving it an estimated-wait threshold of zero turns a silent mis-route
 * into an observable 429/est_wait on plain sync {@code :invoke}, which
 * {@link #assertSyncInvokeIsNotGatedBySyncQueue()} asserts against.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "nanofaas.rate.maxPerSecond=1000",
                "nanofaas.defaults.timeoutMs=2000",
                "nanofaas.defaults.concurrency=2",
                "nanofaas.defaults.queueSize=10",
                "nanofaas.defaults.maxRetries=3",
                "nanofaas.registry.path=build/test-async-capability-strategy-independence-functions.json",
                "nanofaas.admin.runtime-config.enabled=true",
                // No nanofaas.admission.profile override: with both queue modules present this
                // resolves to FUNCTION_QUEUE (async-capable), exactly the legacy async mapping
                // plan-context decision 2 describes.
                "nanofaas.scheduler.strategy=per-function",
                // sync-queue.enabled is left at its application.yml default (true) on purpose —
                // see the class comment. max-estimated-wait=0s makes a mis-route observable.
                "sync-queue.max-estimated-wait=0s"
        })
@AutoConfigureWebTestClient
class AsyncCapabilityStrategyIndependenceApiTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private FunctionService functionService;

    @Test
    void asyncCapabilityStaysAsyncAcrossASwitchToSharedQueue() {
        Assumptions.assumeTrue(selectedModules().contains("sync-queue"));
        Assumptions.assumeTrue(selectedModules().contains("async-queue"));

        functionService.remove("echo");
        functionService.register(new FunctionSpec(
                "echo", "local", null, Map.of(), null,
                1000, 1, 10, 3, null,
                ExecutionMode.LOCAL, null, null, null
        ));

        // Baseline: FUNCTION_QUEUE admission profile accepts :enqueue, and a plain sync
        // :invoke is NOT gated by the sync queue's depth/estimated-wait admission (the C1 defect).
        assertEnqueueAccepted();
        assertSyncInvokeIsNotGatedBySyncQueue();

        // The ONLY change: switch the scheduling strategy, through the real admin API / real
        // SchedulerEngine, to shared-queue — the strategy the sync-queue module contributes.
        switchStrategyTo("shared-queue");

        // The admission profile is untouched by a strategy switch: :enqueue must still be
        // accepted. A 501 here would mean the switch silently flipped ASYNC capability to sync,
        // conflating two configuration axes the plan keeps distinct.
        assertEnqueueAccepted();
        assertSyncInvokeIsNotGatedBySyncQueue();
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
                .getResponseBody());
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"revision\"\\s*:\\s*(\\d+)").matcher(body);
        if (!m.find()) {
            throw new IllegalStateException("no revision field in admin runtime-config envelope: " + body);
        }
        return Long.parseLong(m.group(1));
    }

    private void assertEnqueueAccepted() {
        webTestClient.post()
                .uri("/v1/functions/echo:enqueue")
                .bodyValue(new InvocationRequest("payload", Map.of()))
                .exchange()
                .expectStatus().isAccepted();
    }

    /**
     * The C1-regression pin: with the resolved profile FUNCTION_QUEUE, a plain sync
     * {@code :invoke} must dispatch straight through (LOCAL echo, 200) rather than being
     * evaluated against the sync queue's zero-tolerance estimated-wait threshold. If
     * {@code EngineSyncQueueGateway.enabled()} ever again answers {@code true} purely from
     * {@code sync-queue.enabled}, ignoring the resolved admission profile, this invocation
     * gets rejected 429/est_wait instead.
     */
    private void assertSyncInvokeIsNotGatedBySyncQueue() {
        webTestClient.post()
                .uri("/v1/functions/echo:invoke")
                .bodyValue(new InvocationRequest("payload", Map.of()))
                .exchange()
                .expectStatus().isOk();
    }

    private static Set<String> selectedModules() {
        String modules = System.getProperty("nanofaas.selectedControlPlaneModules", "");
        return Stream.of(modules.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toSet());
    }
}
