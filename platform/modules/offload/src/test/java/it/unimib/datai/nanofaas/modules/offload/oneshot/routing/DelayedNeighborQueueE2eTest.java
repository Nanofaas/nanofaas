package it.unimib.datai.nanofaas.modules.offload.oneshot.routing;

import it.unimib.datai.nanofaas.controlplane.ControlPlaneApplication;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadGateway;
import it.unimib.datai.nanofaas.execution.PendingWorkStore;
import it.unimib.datai.nanofaas.modules.offload.DefaultOffloadGateway;
import it.unimib.datai.nanofaas.modules.offload.oneshot.actuation.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class DelayedNeighborQueueE2eTest {
    @Test
    void oldNeighborRequestWaitsForCurrentCapacityAndQueueOverflowStillRejects() throws Exception {
        try (var backend = new MockWebServer(); var cloud = new MockWebServer()) {
            backend.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    return new MockResponse().setHeader("Content-Type", "application/json").setBody("\"executed\"");
                }
            });
            backend.start();cloud.start();
            var registry = java.nio.file.Files.createTempDirectory("one-shot-delayed-queue-").resolve("functions.json");
            try (var app = new SpringApplicationBuilder(ControlPlaneApplication.class).run(
                    "--server.port=0", "--management.server.port=0",
                    "--spring.autoconfigure.exclude=it.unimib.datai.nanofaas.modules.asyncqueue.AsyncQueueConfiguration",
                    "--sync-queue.enabled=true", "--sync-queue.max-depth=1",
                    "--sync-queue.admission-enabled=false", "--sync-queue.max-queue-wait=30s",
                    "--nanofaas.registry.path=" + registry); var http = HttpClient.newHttpClient()) {
                var url = OneShotHopGuardE2eTest.url(app);
                var digest = OneShotHopGuardE2eTest.DIGEST;
                var spec = "{\"name\":\"f\",\"image\":\"img@" + digest + "\",\"executionMode\":\"EXTERNAL\",\"endpointUrl\":\""
                        + backend.url("/invoke") + "\",\"timeoutMs\":10000,\"concurrency\":1,\"maxRetries\":0}";
                OneShotHopGuardE2eTest.send(url + "/v1/functions", "POST", spec, Map.of());
                var capacity = app.getBean(FunctionCapacityRegistry.class);
                var generation = capacity.activeGeneration("f").id();
                capacity.setEffectiveConcurrency("f", 0);
                var config = OneShotHopGuardE2eTest.settings(generation, cloud.url("/").toString());
                var active = new AtomicReference<ActiveRoutingPlan>();
                var transition = new AtomicReference<>(new AllocationTransition("a", 2));
                var router = new PlanRouter(() -> Optional.ofNullable(active.get()), () -> Optional.of(config), rev -> Optional.of(config),
                        OneShotHopGuardE2eTest.peers("a", url, "b", "http://b:8080"), () -> 0,
                        name -> Optional.ofNullable(transition.get()));
                ((DefaultOffloadGateway) app.getBean(OffloadGateway.class)).plannedRouting(router);
                var headers = Map.of("X-NanoFaaS-Offload-Hop", "1", "X-NanoFaaS-Offload-Version", "1",
                        "X-NanoFaaS-Offload-Origin", "b@b-run", "X-NanoFaaS-Offload-Epoch", "1",
                        "X-NanoFaaS-Offload-Assignment", "old-grant");
                var payload = "{\"input\":{\"payload\":\"value\",\"nested\":[1,2]}}";
                var request = HttpRequest.newBuilder(URI.create(url + "/v1/functions/f:invoke"))
                        .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
                headers.forEach(request::header);
                var first = http.sendAsync(request.POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(app.getBean(PendingWorkStore.class).reservedCount("f")).isEqualTo(1));
                assertThat(first).isNotDone();
                assertThat(backend.getRequestCount()).isZero();
                var overflow = OneShotHopGuardE2eTest.send(url + "/v1/functions/f:invoke", "POST", payload, headers);
                assertThat(overflow.statusCode()).as(overflow.body()).isEqualTo(429);
                assertThat(cloud.getRequestCount()).isZero();
                active.set(new ActiveRoutingPlan("a", "a-run", 2, 1, Instant.EPOCH, Instant.EPOCH.plusSeconds(360), 1,
                        Map.of("f", new ActiveRoutingPlan.FunctionPlan(digest, generation, 1, 0, 1, 1, 64, 1, .8, List.of(), List.of()))));
                transition.set(null);
                capacity.setEffectiveConcurrency("f", 1);
                var result = first.get(10, TimeUnit.SECONDS);
                assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
                assertThat(result.headers().firstValue("X-NanoFaaS-Execution-Node")).contains("a");
                var afterTransition = OneShotHopGuardE2eTest.send(url + "/v1/functions/f:invoke", "POST", payload, headers);
                assertThat(afterTransition.statusCode()).as(afterTransition.body()).isEqualTo(200);
                assertThat(backend.getRequestCount()).isEqualTo(2);
                assertThat(cloud.getRequestCount()).isZero();
            }
        }
    }
}
