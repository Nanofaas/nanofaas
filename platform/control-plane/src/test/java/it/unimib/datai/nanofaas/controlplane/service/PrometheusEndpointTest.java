package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "nanofaas.rate.maxPerSecond=1000",
                "nanofaas.defaults.timeoutMs=2000",
                "nanofaas.defaults.concurrency=2",
                "nanofaas.defaults.queueSize=10",
                "nanofaas.defaults.maxRetries=3",
                "nanofaas.metrics.profile=advanced",
                "nanofaas.registry.path=build/test-prometheus-functions.json",
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

    @Autowired
    private PrometheusMeterRegistry prometheusRegistry;

    @BeforeEach
    void setup() {
        functionService.remove("echo");
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

        // The response is settled before completion metrics are recorded.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Counter dispatch = meterRegistry.find("function_dispatch_total").tag("function", "echo").counter();
            Timer latency = meterRegistry.find("function_latency_ms").tag("function", "echo").timer();

            assertTrue(dispatch != null && dispatch.count() >= 1.0, "Expected function_dispatch_total counter to be present and >= 1");
            assertTrue(latency != null && latency.count() >= 1, "Expected function_latency_ms timer to be present and have at least 1 sample");
        });
    }

    @Test
    void theWiredExpositionCarriesTheSwitchDurationBucketsTheSoakReads() {
        // Read out of the wired registry, not one this test built: a MeterFilter
        // that arrived after the switch timer was created would leave the
        // histogram at whatever the meter was born with, and the hand-built
        // registry in MetricsProfileConfigurationTest would stay green while the
        // platform published no buckets at all. `scrape()` is the body the
        // actuator endpoint renders - it renders this registry's own prometheus
        // client registry, which is what Micrometer writes the buckets into.
        //
        // The timer is registered at composition, so the buckets are here from
        // the first scrape, before any switch has been made.
        String exposition = prometheusRegistry.scrape();

        assertTrue(
                exposition.contains("scheduler_switch_duration_seconds_bucket{le=\""),
                "Expected the switch-duration histogram in the wired exposition");
        assertTrue(
                exposition.contains("scheduler_switch_duration_seconds_bucket{le=\"+Inf\""),
                "Expected the +Inf bucket, without which a histogram_quantile is not a p99");
        assertTrue(
                exposition.contains("scheduler_switch_duration_seconds_max"),
                "Expected the maximum basic also keeps, which the pause is read against today");
    }
}
