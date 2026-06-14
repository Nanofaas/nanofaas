package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "nanofaas.rate.maxPerSecond=1000",
                "nanofaas.defaults.timeoutMs=2000",
                "nanofaas.defaults.concurrency=2",
                "nanofaas.defaults.queueSize=10",
                "nanofaas.defaults.maxRetries=3",
                "sync-queue.enabled=false",
                // Avoid fixed port collisions when Gradle runs tests in parallel.
                "management.server.port=0",
                // Force-enable the Prometheus scrape endpoint for this test context.
                "management.metrics.export.prometheus.enabled=true",
                "management.endpoint.prometheus.enabled=true",
                "management.endpoints.web.exposure.include=health,prometheus"
        }
)
@AutoConfigureWebTestClient
class PrometheusEndpointTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private FunctionService functionService;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setup() {
        functionService.register(new FunctionSpec(
                "echo",
                "local",
                null,
                Map.of(),
                null,
                1000,
                1,
                10,
                0,
                null,
                ExecutionMode.LOCAL,
                null,
                null,
                null
        ));
    }

    @Test
    void actuatorPrometheus_exposesFunctionCountersAndLatencyTimer() {
        // Trigger at least one dispatch + completion to materialize counters/timers.
        webTestClient.post()
                .uri("/v1/functions/echo:invoke")
                .bodyValue(new InvocationRequest("payload", Map.of()))
                .exchange()
                .expectStatus().isOk();

        Counter dispatch = meterRegistry.find("function_dispatch_total").tag("function", "echo").counter();
        Timer latency = meterRegistry.find("function_latency_ms").tag("function", "echo").timer();

        assertTrue(dispatch != null && dispatch.count() >= 1.0, "Expected function_dispatch_total counter to be present and >= 1");
        assertTrue(latency != null && latency.count() >= 1, "Expected function_latency_ms timer to be present and have at least 1 sample");
    }
}
