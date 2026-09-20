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
                "sync-queue.enabled=false"
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

        // Baseline: FUNCTION_QUEUE admission profile accepts :enqueue.
        assertEnqueueAccepted();

        // The ONLY change: switch the scheduling strategy, through the real admin API / real
        // SchedulerEngine, to shared-queue — the strategy the sync-queue module contributes.
        switchStrategyTo("shared-queue");

        // The admission profile is untouched by a strategy switch: :enqueue must still be
        // accepted. A 501 here would mean the switch silently flipped ASYNC capability to sync,
        // conflating two configuration axes the plan keeps distinct.
        assertEnqueueAccepted();
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

    private static Set<String> selectedModules() {
        String modules = System.getProperty("nanofaas.selectedControlPlaneModules", "");
        return Stream.of(modules.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toSet());
    }
}
