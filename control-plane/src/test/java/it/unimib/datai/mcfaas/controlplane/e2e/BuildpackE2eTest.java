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
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Testcontainers
class BuildpackE2eTest {
    private static final String CONTROL_IMAGE = "mcfaas/control-plane:buildpack";
    private static final String RUNTIME_IMAGE = "mcfaas/function-runtime:buildpack";
    private static final Network network = Network.newNetwork();

    private static GenericContainer<?> functionRuntime;
    private static GenericContainer<?> controlPlane;

    @BeforeAll
    static void buildImagesAndStart() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for buildpack E2E test");

        // Skip on ARM64 - Paketo buildpacks don't have native ARM64 support,
        // emulated amd64 images are unstable. Use E2eFlowTest instead.
        String arch = System.getProperty("os.arch");
        assumeTrue(!"aarch64".equals(arch) && !"arm64".equals(arch),
            "BuildpackE2eTest skipped on ARM64: Paketo lacks native ARM64 support");

        runGradleBuild();

        functionRuntime = new GenericContainer<>(RUNTIME_IMAGE)
                .withExposedPorts(8080)
                .withNetwork(network)
                .withNetworkAliases("function-runtime")
                .waitingFor(Wait.forListeningPort());

        controlPlane = new GenericContainer<>(CONTROL_IMAGE)
                .withExposedPorts(8080, 8081)
                .withNetwork(network)
                .withNetworkAliases("control-plane")
                .waitingFor(Wait.forHttp("/actuator/health").forPort(8081).withStartupTimeout(Duration.ofSeconds(60)));

        functionRuntime.start();
        controlPlane.start();

        RestAssured.baseURI = "http://" + controlPlane.getHost();
        RestAssured.port = controlPlane.getMappedPort(8080);
    }

    @Test
    void buildpackRegisterInvokeAndPoll() {
        String endpointUrl = "http://function-runtime:8080/invoke";
        Map<String, Object> spec = Map.of(
                "name", "bp-echo",
                "image", RUNTIME_IMAGE,
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
                .body("name", equalTo("bp-echo"));

        RestAssured.given()
                .contentType(ContentType.JSON)
                .body(Map.of("input", Map.of("message", "hi")))
                .post("/v1/functions/bp-echo:invoke")
                .then()
                .statusCode(200)
                .body("status", equalTo("success"))
                .body("output.message", equalTo("hi"));

        String executionId = RestAssured.given()
                .contentType(ContentType.JSON)
                .body(Map.of("input", "payload"))
                .post("/v1/functions/bp-echo:enqueue")
                .then()
                .statusCode(202)
                .body("executionId", notNullValue())
                .extract()
                .path("executionId");

        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                RestAssured.get("/v1/executions/{id}", executionId)
                        .then()
                        .statusCode(200)
                        .body("status", equalTo("success")));

        String metrics = RestAssured.get("http://" + controlPlane.getHost() + ":" + controlPlane.getMappedPort(8081) + "/actuator/prometheus")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        org.junit.jupiter.api.Assertions.assertTrue(metrics.contains("function_enqueue_total"));
    }

    private static void runGradleBuild() throws Exception {
        // Test runs from control-plane/ directory, so project root is ..
        File projectRoot = new File("..").getAbsoluteFile().getCanonicalFile();
        ProcessBuilder builder = new ProcessBuilder()
                .directory(projectRoot)
                .command("./gradlew", ":control-plane:bootBuildImage", ":function-runtime:bootBuildImage",
                        "-PcontrolPlaneImage=" + CONTROL_IMAGE, "-PfunctionRuntimeImage=" + RUNTIME_IMAGE, "--no-daemon");
        builder.environment().putIfAbsent("JAVA_HOME", System.getProperty("java.home"));
        builder.redirectErrorStream(true);
        Process process = builder.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
        }

        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("bootBuildImage failed with exit code " + exit);
        }
    }
}
