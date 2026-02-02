package it.unimib.datai.mcfaas.controlplane;

import it.unimib.datai.mcfaas.common.model.ExecutionMode;
import it.unimib.datai.mcfaas.common.model.FunctionSpec;
import it.unimib.datai.mcfaas.common.model.InvocationRequest;
import it.unimib.datai.mcfaas.controlplane.registry.FunctionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mcfaas.rate.maxPerSecond=1000",
                "mcfaas.defaults.timeoutMs=2000",
                "mcfaas.defaults.concurrency=2",
                "mcfaas.defaults.queueSize=10",
                "mcfaas.defaults.maxRetries=3",
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
class SyncQueueBackpressureApiTest {
    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private FunctionService functionService;

    @Test
    void syncInvokeReturns429WithRetryAfter() {
        functionService.register(new FunctionSpec(
                "echo",
                "local",
                null,
                Map.of(),
                null,
                1000,
                1,
                10,
                3,
                null,
                ExecutionMode.LOCAL,
                null,
                null
        ));

        webTestClient.post()
                .uri("/v1/functions/echo:invoke")
                .bodyValue(new InvocationRequest("payload", Map.of()))
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("Retry-After", "2")
                .expectHeader().valueEquals("X-Queue-Reject-Reason", "est_wait");
    }
}
