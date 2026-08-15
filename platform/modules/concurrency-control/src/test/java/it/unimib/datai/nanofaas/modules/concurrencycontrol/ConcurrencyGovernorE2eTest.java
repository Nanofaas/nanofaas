package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import io.micrometer.core.instrument.MeterRegistry;
import it.unimib.datai.nanofaas.controlplane.ControlPlaneApplication;
import org.junit.jupiter.api.Test;
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
 * {@code ScalingMetricsSource} into the queue module and land on the state that actually gates
 * dispatch. The {@code function_effective_concurrency} gauge is bound to that state, so reading it
 * back proves the whole chain without this module referencing the queue module.
 */
@SpringBootTest(classes = ControlPlaneApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "nanofaas.concurrency-control.poll-interval-ms=500",
                // the concurrency gauges are filtered out of the basic metrics profile
                "nanofaas.metrics.profile=advanced",
                "sync-queue.enabled=false"
        })
@AutoConfigureWebTestClient
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
