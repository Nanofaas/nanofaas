package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import it.unimib.datai.nanofaas.controlplane.ControlPlaneApplication;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionService;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityState;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(classes = ControlPlaneApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "nanofaas.metrics.profile=advanced",
                "nanofaas.concurrency-control.poll-interval-ms=100",
                "nanofaas.registry.path=build/test-sync-concurrency-control-functions.json"
        })
@AutoConfigureWebTestClient
@EnabledIfSystemProperty(named = "nanofaas.queue.provider", matches = "sync-queue")
class SyncConcurrencyControlE2eTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private FunctionCapacityRegistry capacityRegistry;

    @Autowired
    private FunctionService functionService;

    @BeforeEach
    void setup() {
        // A re-run without `./gradlew clean` restores this EXTERNAL function from the
        // persisted catalog; drop it so the register(...) below never collides.
        functionService.remove("sync-governed");
    }

    /**
     * This test was held {@code @Disabled} from Task 8's fix round to Task 11 of issue #208: the
     * engine composition retired the per-module {@code WorkloadMetricsSource} beans with no
     * replacement, so the second assertion below was genuinely red. Task 11 restored the bean as
     * {@code EngineWorkloadMetricsSource} and the annotation is gone, so the test runs again —
     * under the profile that supplies the sync queue, which is the only one where its gate
     * ({@code nanofaas.queue.provider=sync-queue}) opens; CI gives it that profile as its own
     * invocation.
     */
    @Test
    void governorUpdatesTheSharedSyncCapacityState() {
        assertThat(applicationContext.getBeansOfType(FunctionCapacityRegistry.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(WorkloadMetricsSource.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(ConcurrencyControlCoordinator.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(ConcurrencyGovernor.class)).hasSize(1);

        webTestClient.post().uri("/v1/functions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "name": "sync-governed",
                          "image": "example/sync-governed:1",
                          "concurrency": 6,
                          "executionMode": "EXTERNAL",
                          "endpointUrl": "http://localhost:9",
                          "scalingConfig": {
                            "strategy": "NONE",
                            "minReplicas": 1,
                            "maxReplicas": 1,
                            "concurrencyControl": {
                              "mode": "STATIC_PER_POD",
                              "targetInFlightPerPod": 2
                            }
                          }
                        }
                        """)
                .exchange()
                .expectStatus().is2xxSuccessful();

        assertThat(capacityRegistry.state("sync-governed"))
                .extracting(FunctionCapacityState::configuredConcurrency)
                .isEqualTo(6);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(capacityRegistry.state("sync-governed"))
                        .extracting(FunctionCapacityState::effectiveConcurrency)
                        .isEqualTo(2));
    }
}
