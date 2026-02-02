package it.unimib.datai.mcfaas.controlplane.e2e;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.awaitility.Awaitility;
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
import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Testcontainers
class E2eFlowTest {
    // Paths relative to project root (test runs from control-plane/)
    private static final Path PROJECT_ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Network network = Network.newNetwork();

    private static final GenericContainer<?> functionRuntime = new GenericContainer<>(
            new ImageFromDockerfile()
                    .withFileFromPath("Dockerfile", PROJECT_ROOT.resolve("function-runtime/Dockerfile"))
                    .withFileFromPath("build/libs/function-runtime-0.5.0.jar",
                            PROJECT_ROOT.resolve("function-runtime/build/libs/function-runtime-0.5.0.jar"))
    )
            .withExposedPorts(8080)
            .withNetwork(network)
            .withNetworkAliases("function-runtime")
            .waitingFor(Wait.forListeningPort());

    private static final GenericContainer<?> controlPlane = new GenericContainer<>(
            new ImageFromDockerfile()
                    .withFileFromPath("Dockerfile", PROJECT_ROOT.resolve("control-plane/Dockerfile"))
                    .withFileFromPath("build/libs/control-plane-0.5.0.jar",
                            PROJECT_ROOT.resolve("control-plane/build/libs/control-plane-0.5.0.jar"))
    )
            .withExposedPorts(8080, 8081)
            .withNetwork(network)
            .withNetworkAliases("control-plane")
            .waitingFor(Wait.forHttp("/actuator/health").forPort(8081).withStartupTimeout(Duration.ofSeconds(60)));

    @BeforeAll
    static void startContainers() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker not available");
        functionRuntime.start();
        controlPlane.start();
        RestAssured.baseURI = "http://" + controlPlane.getHost();
        RestAssured.port = controlPlane.getMappedPort(8080);
    }

    @Test
    void e2eRegisterInvokeAndPoll() {
        String endpointUrl = "http://function-runtime:8080/invoke";
        Map<String, Object> spec = Map.of(
                "name", "e2e-echo",
                "image", "mcfaas/function-runtime:0.5.0",
                "timeoutMs", 5000,
                "concurrency", 2,
                "queueSize", 20,
                "maxRetries", 3,
                "executionMode", "POOL",
                "endpointUrl", endpointUrl
        );

        RestAssured.given()
                .contentType(ContentType.JSON)
                .body(spec)
                .post("/v1/functions")
                .then()
                .statusCode(201)
                .body("name", equalTo("e2e-echo"));

        RestAssured.given()
                .contentType(ContentType.JSON)
                .body(Map.of("input", Map.of("message", "hi")))
                .post("/v1/functions/e2e-echo:invoke")
                .then()
                .statusCode(200)
                .body("status", equalTo("success"))
                .body("output.message", equalTo("hi"));

        String executionId = RestAssured.given()
                .contentType(ContentType.JSON)
                .header("Idempotency-Key", "abc")
                .body(Map.of("input", "payload"))
                .post("/v1/functions/e2e-echo:enqueue")
                .then()
                .statusCode(202)
                .body("executionId", notNullValue())
                .extract()
                .path("executionId");

        String executionId2 = RestAssured.given()
                .contentType(ContentType.JSON)
                .header("Idempotency-Key", "abc")
                .body(Map.of("input", "payload"))
                .post("/v1/functions/e2e-echo:enqueue")
                .then()
                .statusCode(202)
                .extract()
                .path("executionId");

        org.junit.jupiter.api.Assertions.assertEquals(executionId, executionId2);

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> RestAssured.get("/v1/executions/{id}", executionId)
                        .then()
                        .statusCode(200)
                        .body("status", equalTo("success")));
    }

    @Test
    void e2ePrometheusMetricsExposed() {
        String metrics = RestAssured.get("http://" + controlPlane.getHost() + ":" + controlPlane.getMappedPort(8081) + "/actuator/prometheus")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        org.junit.jupiter.api.Assertions.assertTrue(metrics.contains("function_enqueue_total"));
    }
}
