package it.unimib.datai.nanofaas.modules.offload.oneshot.routing;

import it.unimib.datai.nanofaas.controlplane.ControlPlaneApplication;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadGateway;
import it.unimib.datai.nanofaas.execution.PendingWorkStore;
import it.unimib.datai.nanofaas.modules.offload.DefaultOffloadGateway;
import it.unimib.datai.nanofaas.modules.offload.oneshot.actuation.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import it.unimib.datai.nanofaas.modules.offload.oneshot.auction.Assignment;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class RouletteQueueE2eTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void localAndNeighborRequestsQueueWithFunctionIsolation(boolean neighbor) throws Exception {
        var observations = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        observations.start();
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("it.unimib.datai.nanofaas.execution.QueueObservation");
        try (var backend = new MockWebServer(); var cloud = new MockWebServer()) {
            backend.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    return new MockResponse().setHeader("Content-Type", "application/json").setBody("\"executed\"");
                }
            });
            backend.start();cloud.start();
            var registry = java.nio.file.Files.createTempDirectory("one-shot-delayed-queue-").resolve("functions.json");
            try (var app = new SpringApplicationBuilder(ControlPlaneApplication.class).run(
                    "--server.port=0", "--management.server.port=0", "--logging.level.root=WARN",
                    "--logging.level.it.unimib.datai.nanofaas.execution.QueueObservation=INFO",
                    "--spring.autoconfigure.exclude=it.unimib.datai.nanofaas.modules.asyncqueue.AsyncQueueConfiguration",
                    "--sync-queue.enabled=true", "--sync-queue.max-depth=2",
                    "--sync-queue.admission-enabled=false", "--sync-queue.max-queue-wait=30s",
                    "--nanofaas.registry.path=" + registry); var http = HttpClient.newHttpClient()) {
                logger.addAppender(observations);
                var url = OneShotHopGuardE2eTest.url(app);
                var digest = OneShotHopGuardE2eTest.DIGEST;
                var spec = "{\"name\":\"f\",\"image\":\"img@" + digest + "\",\"executionMode\":\"EXTERNAL\",\"endpointUrl\":\""
                        + backend.url("/invoke") + "\",\"timeoutMs\":10000,\"concurrency\":1,\"queueSize\":1,\"maxRetries\":0}";
                assertThat(OneShotHopGuardE2eTest.send(url + "/v1/functions", "POST", spec, Map.of()).statusCode()).isEqualTo(201);
                assertThat(OneShotHopGuardE2eTest.send(url + "/v1/functions", "POST", spec.replace("\"f\"", "\"g\""), Map.of()).statusCode()).isEqualTo(201);
                var capacity = app.getBean(FunctionCapacityRegistry.class);
                var generation = capacity.activeGeneration("f").id();
                capacity.setEffectiveConcurrency("f", 0);
                var config = OneShotHopGuardE2eTest.settings(generation, cloud.url("/").toString());
                capacity.setEffectiveConcurrency("g", 0);
                var grant = new Assignment("grant", "b", "b-run", "a", "a-run", "f", digest, generation, generation, 1, 1, true);
                var active = new ActiveRoutingPlan("a", "a-run", 1, 1, Instant.EPOCH, Instant.EPOCH.plusSeconds(360), 1,
                        Map.of("f", new ActiveRoutingPlan.FunctionPlan(digest, generation, 3, 1, 0, 1, 64, 1, .8, List.of(grant), List.of())));
                var router = new PlanRouter(() -> Optional.of(active), () -> Optional.of(config), rev -> Optional.of(config),
                        OneShotHopGuardE2eTest.peers("a", url, "b", "http://b:8080"),
                        () -> { if (neighbor) throw new AssertionError("neighbor entered roulette"); return 0; });
                ((DefaultOffloadGateway) app.getBean(OffloadGateway.class)).plannedRouting(router);
                var headers = neighbor ? Map.of("X-NanoFaaS-Offload-Hop", "1", "X-NanoFaaS-Offload-Version", "1",
                        "X-NanoFaaS-Offload-Origin", "b@b-run", "X-NanoFaaS-Offload-Epoch", "1",
                        "X-NanoFaaS-Offload-Assignment", "grant") : Map.<String, String>of();
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
                var otherRequest = HttpRequest.newBuilder(URI.create(url + "/v1/functions/g:invoke"))
                        .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
                var other = http.sendAsync(otherRequest, HttpResponse.BodyHandlers.ofString());
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(app.getBean(PendingWorkStore.class).reservedCount("g")).isEqualTo(1));
                assertThat(other).isNotDone();
                capacity.setEffectiveConcurrency("g", 1);
                capacity.setEffectiveConcurrency("f", 1);
                var result = first.get(10, TimeUnit.SECONDS);
                assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
                assertThat(result.headers().firstValue("X-NanoFaaS-Execution-Node")).contains("a");
                assertThat(other.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
                var afterTransition = OneShotHopGuardE2eTest.send(url + "/v1/functions/f:invoke", "POST", payload, headers);
                assertThat(afterTransition.statusCode()).as(afterTransition.body()).isEqualTo(200);
                assertThat(backend.getRequestCount()).isEqualTo(3);
                var events = observations.list.stream().map(event -> event.getKeyValuePairs().stream()
                        .filter(pair -> pair.value != null).collect(java.util.stream.Collectors.toMap(pair -> pair.key, pair -> pair.value))).toList();
                assertThat(events.stream().filter(event -> "QUEUED".equals(event.get("event"))).count()).isEqualTo(3);
                assertThat(events.stream().filter(event -> "DISPATCH".equals(event.get("event"))).count()).isEqualTo(3);
                for (var entered : events.stream().filter(event -> "QUEUED".equals(event.get("event"))).toList()) {
                    var dispatched = events.stream().filter(event -> "DISPATCH".equals(event.get("event"))
                            && event.get("executionId").equals(entered.get("executionId"))).findFirst().orElseThrow();
                    assertThat((long) dispatched.get("monotonicNanos")).isGreaterThanOrEqualTo((long) entered.get("monotonicNanos"));
                    assertThat(dispatched.get("processId")).isEqualTo(entered.get("processId"));
                }
                assertThat(cloud.getRequestCount()).isZero();
            }
        } finally {
            logger.detachAppender(observations);
            observations.stop();
        }
    }
}
