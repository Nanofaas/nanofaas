package it.unimib.datai.nanofaas.controlplane.e2e;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * E2E test that validates the Java SDK example functions (word-stats, json-transform)
 * running as real containers through the control plane.
 */
@Testcontainers
class SdkExamplesE2eTest {

    private static final Path PROJECT_ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Network network = Network.newNetwork();

    // word-stats function container
    private static final GenericContainer<?> wordStats = new GenericContainer<>(
            new ImageFromDockerfile()
                    .withFileFromPath("Dockerfile",
                            PROJECT_ROOT.resolve("examples/java/word-stats/Dockerfile"))
                    .withFileFromPath("build/libs/word-stats.jar",
                            PROJECT_ROOT.resolve("examples/java/word-stats/build/libs/word-stats.jar"))
    )
            .withExposedPorts(8080)
            .withNetwork(network)
            .withNetworkAliases("word-stats")
            .waitingFor(Wait.forHttp("/actuator/health").forPort(8080).withStartupTimeout(Duration.ofSeconds(60)));

    // json-transform function container
    private static final GenericContainer<?> jsonTransform = new GenericContainer<>(
            new ImageFromDockerfile()
                    .withFileFromPath("Dockerfile",
                            PROJECT_ROOT.resolve("examples/java/json-transform/Dockerfile"))
                    .withFileFromPath("build/libs/json-transform.jar",
                            PROJECT_ROOT.resolve("examples/java/json-transform/build/libs/json-transform.jar"))
    )
            .withExposedPorts(8080)
            .withNetwork(network)
            .withNetworkAliases("json-transform")
            .waitingFor(Wait.forHttp("/actuator/health").forPort(8080).withStartupTimeout(Duration.ofSeconds(60)));

    // control plane
    private static final GenericContainer<?> controlPlane = new GenericContainer<>(
            new ImageFromDockerfile()
                    .withFileFromPath("Dockerfile",
                            PROJECT_ROOT.resolve("control-plane/Dockerfile"))
                    .withFileFromPath("build/libs/control-plane-0.5.0.jar",
                            PROJECT_ROOT.resolve("control-plane/build/libs/control-plane-0.5.0.jar"))
    )
            .withExposedPorts(8080, 8081)
            .withNetwork(network)
            .withNetworkAliases("control-plane")
            .withEnv("SYNC_QUEUE_ENABLED", "false")
            .waitingFor(Wait.forHttp("/actuator/health").forPort(8081).withStartupTimeout(Duration.ofSeconds(60)));

    @BeforeAll
    static void startContainers() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker not available");
        wordStats.start();
        jsonTransform.start();
        controlPlane.start();
        RestAssured.baseURI = "http://" + controlPlane.getHost();
        RestAssured.port = controlPlane.getMappedPort(8080);

        // Register both functions once
        registerFunction("word-stats", "http://word-stats:8080/invoke");
        registerFunction("json-transform", "http://json-transform:8080/invoke");
    }

    // ── word-stats ──────────────────────────────────────────────────

    @Test
    void wordStats_syncInvoke_returnsCorrectStatistics() {
        // Invoke synchronously
        RestAssured.given()
                .contentType(ContentType.JSON)
                .body(Map.of("input", Map.of(
                        "text", "the quick brown fox jumps over the lazy dog the dog",
                        "topN", 3
                )))
                .post("/v1/functions/word-stats:invoke")
                .then()
                .statusCode(200)
                .body("status", equalTo("success"))
                .body("output.wordCount", equalTo(11))
                .body("output.uniqueWords", equalTo(8))
                .body("output.topWords", hasSize(3))
                .body("output.topWords[0].word", equalTo("the"))
                .body("output.topWords[0].count", equalTo(3));
    }

    @Test
    void wordStats_stringInput_treatedAsText() {
        RestAssured.given()
                .contentType(ContentType.JSON)
                .body(Map.of("input", "hello world hello"))
                .post("/v1/functions/word-stats:invoke")
                .then()
                .statusCode(200)
                .body("status", equalTo("success"))
                .body("output.wordCount", equalTo(3))
                .body("output.uniqueWords", equalTo(2));
    }

    // ── json-transform ──────────────────────────────────────────────

    @Test
    void jsonTransform_syncInvoke_groupAndCount() {
        // Group by department, count
        RestAssured.given()
                .contentType(ContentType.JSON)
                .body(Map.of("input", Map.of(
                        "data", List.of(
                                Map.of("dept", "eng", "salary", 80000),
                                Map.of("dept", "sales", "salary", 60000),
                                Map.of("dept", "eng", "salary", 90000),
                                Map.of("dept", "sales", "salary", 70000),
                                Map.of("dept", "eng", "salary", 85000)
                        ),
                        "groupBy", "dept",
                        "operation", "count"
                )))
                .post("/v1/functions/json-transform:invoke")
                .then()
                .statusCode(200)
                .body("status", equalTo("success"))
                .body("output.groupBy", equalTo("dept"))
                .body("output.operation", equalTo("count"))
                .body("output.groups.eng", equalTo(3))
                .body("output.groups.sales", equalTo(2));
    }

    @Test
    void jsonTransform_syncInvoke_groupAndAvg() {
        RestAssured.given()
                .contentType(ContentType.JSON)
                .body(Map.of("input", Map.of(
                        "data", List.of(
                                Map.of("dept", "eng", "salary", 80000),
                                Map.of("dept", "eng", "salary", 90000)
                        ),
                        "groupBy", "dept",
                        "operation", "avg",
                        "valueField", "salary"
                )))
                .post("/v1/functions/json-transform:invoke")
                .then()
                .statusCode(200)
                .body("status", equalTo("success"))
                .body("output.groups.eng", equalTo(85000.0f));
    }

    @Test
    void jsonTransform_asyncInvoke_pollForResult() {
        // Enqueue async
        String executionId = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(Map.of("input", Map.of(
                        "data", List.of(
                                Map.of("category", "A", "value", 10),
                                Map.of("category", "B", "value", 20),
                                Map.of("category", "A", "value", 30)
                        ),
                        "groupBy", "category",
                        "operation", "sum",
                        "valueField", "value"
                )))
                .post("/v1/functions/json-transform:enqueue")
                .then()
                .statusCode(202)
                .body("executionId", notNullValue())
                .extract()
                .path("executionId");

        // Poll until complete
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> {
                    var response = RestAssured.get("/v1/executions/{id}", executionId)
                            .then()
                            .statusCode(200)
                            .extract()
                            .response();
                    assertEquals("success", response.path("status"));
                    // Verify the aggregated result
                    assertEquals(40.0f, ((Number) response.path("output.groups.A")).floatValue(), 0.01);
                    assertEquals(20.0f, ((Number) response.path("output.groups.B")).floatValue(), 0.01);
                });
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static void registerFunction(String name, String endpointUrl) {
        RestAssured.given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "name", name,
                        "image", name + ":test",
                        "timeoutMs", 10000,
                        "concurrency", 4,
                        "queueSize", 20,
                        "executionMode", "POOL",
                        "endpointUrl", endpointUrl
                ))
                .post("/v1/functions")
                .then()
                .statusCode(201);
    }
}
