package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import it.unimib.datai.nanofaas.controlplane.ControlPlaneApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Closes the loop the unit tests cannot: the governor's decision has to travel through the
 * {@code WorkloadCapacityController} into the queue module and land on the state that actually gates
 * dispatch. The {@code function_effective_concurrency} gauge is bound to that state, so reading it
 * back proves the whole chain without this module referencing the queue module.
 */
@SpringBootTest(classes = ControlPlaneApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "nanofaas.concurrency-control.poll-interval-ms=500",
                "nanofaas.concurrency-control.total-budget=10",
                // the concurrency gauges are filtered out of the basic metrics profile
                "nanofaas.metrics.profile=advanced",
                "sync-queue.enabled=false"
        })
@AutoConfigureWebTestClient
// This E2E scenario runs with the async provider; the same provider-neutral wiring is exercised
// by the sync composition smoke test when that provider is selected.
@EnabledIfSystemProperty(named = "nanofaas.queue.provider", matches = "async-queue")
class ConcurrencyGovernorE2eTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void governorThrottlesTheQueueConcurrencyLimitOfARegisteredFunction() {
        register("governed", 6, 2);

        // the queue starts at the configured limit; only the governor can move it off 6
        assertThat(effectiveConcurrency("governed")).isEqualTo(6.0);

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(effectiveConcurrency("governed")).isEqualTo(2.0));

        // The two gauges the dashboard reads are owned by this module, not by the queue.
        Gauge targetGauge = meterRegistry.find("function_target_inflight_per_pod")
                .tag("function", "governed").gauge();
        Gauge modeGauge = meterRegistry.find("function_concurrency_controller_mode")
                .tags("function", "governed", "mode", "STATIC_PER_POD").gauge();
        assertThat(targetGauge).isNotNull();
        assertThat(modeGauge).isNotNull();
        assertThat(modeGauge.value()).isEqualTo(1.0);

        webTestClient.delete().uri("/v1/functions/governed")
                .exchange()
                .expectStatus().isNoContent();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(meterRegistry.find("function_target_inflight_per_pod")
                    .tag("function", "governed").gauge()).isNull();
            assertThat(meterRegistry.find("function_concurrency_controller_mode")
                    .tags("function", "governed", "mode", "STATIC_PER_POD").gauge()).isNull();
        });
    }

    @Test
    void raisingTheConfiguredCeilingAtRuntimeUnblocksTheGovernor() {
        // the governor wants 1 replica x 4 per replica, but the configured limit pins it to 2
        register("capped", 2, 4);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(effectiveConcurrency("capped")).isEqualTo(2.0));

        webTestClient.patch().uri("/v1/functions/capped")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"concurrency": 8}
                        """)
                .exchange()
                .expectStatus().isOk();

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(effectiveConcurrency("capped")).isEqualTo(4.0));
    }

    @Test
    void budgetedFunctionsShareOneBudgetInsteadOfCompetingForTheSameCores() {
        registerBudgeted("alpha", 200);
        registerBudgeted("beta", 200);

        // Neither function is told a limit; both are told an SLO, and the platform decides how
        // much of its budget each one gets. What must hold is the thing a per-function decision
        // cannot guarantee: the limits together never exceed what the platform has.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            double total = effectiveConcurrency("alpha") + effectiveConcurrency("beta");
            assertThat(total).isLessThanOrEqualTo(10.0);
            assertThat(effectiveConcurrency("alpha")).isGreaterThanOrEqualTo(1.0);
            assertThat(effectiveConcurrency("beta")).isGreaterThanOrEqualTo(1.0);
        });
    }

    private void registerBudgeted(String name, long targetLatencyMs) {
        webTestClient.post().uri("/v1/functions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "name": "%s",
                          "image": "example/%s:1",
                          "concurrency": 64,
                          "executionMode": "EXTERNAL",
                          "endpointUrl": "http://localhost:9",
                          "scalingConfig": {
                            "strategy": "NONE",
                            "concurrencyControl": {
                              "mode": "BUDGETED",
                              "targetLatencyMs": %d
                            }
                          }
                        }
                        """.formatted(name, name, targetLatencyMs))
                .exchange()
                .expectStatus().is2xxSuccessful();
    }

    private void register(String name, int concurrency, int targetInFlightPerPod) {
        webTestClient.post().uri("/v1/functions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "name": "%s",
                          "image": "example/%s:1",
                          "concurrency": %d,
                          "executionMode": "EXTERNAL",
                          "endpointUrl": "http://localhost:9",
                          "scalingConfig": {
                            "strategy": "NONE",
                            "minReplicas": 1,
                            "maxReplicas": 1,
                            "concurrencyControl": {
                              "mode": "STATIC_PER_POD",
                              "targetInFlightPerPod": %d
                            }
                          }
                        }
                        """.formatted(name, name, concurrency, targetInFlightPerPod))
                .exchange()
                .expectStatus().is2xxSuccessful();
    }

    private double effectiveConcurrency(String function) {
        return meterRegistry.get("function_effective_concurrency").tag("function", function).gauge().value();
    }
}
