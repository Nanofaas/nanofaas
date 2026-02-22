package it.unimib.datai.nanofaas.controlplane.e2e;

import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Testcontainers
@Tag("inter_e2e")
class E2eFlowTest {
    private static final Network network = Network.newNetwork();
    private static final java.nio.file.Path FUNCTION_RUNTIME_JAR = E2eTestSupport.resolveBootJar(
            E2eTestSupport.PROJECT_ROOT.resolve("function-runtime/build/libs"),
            "function-runtime-");
    private static final java.nio.file.Path CONTROL_PLANE_JAR = E2eTestSupport.resolveBootJar(
            E2eTestSupport.PROJECT_ROOT.resolve("control-plane/build/libs"),
            "control-plane-");

    private static final GenericContainer<?> functionRuntime = new GenericContainer<>(
            new ImageFromDockerfile()
                    .withFileFromPath("Dockerfile", E2eTestSupport.PROJECT_ROOT.resolve("function-runtime/Dockerfile"))
                    .withFileFromPath("build/libs/" + FUNCTION_RUNTIME_JAR.getFileName(), FUNCTION_RUNTIME_JAR)
    )
            .withExposedPorts(8080)
            .withNetwork(network)
            .withNetworkAliases("function-runtime")
            .waitingFor(Wait.forListeningPort());

    private static final GenericContainer<?> controlPlane = new GenericContainer<>(
            new ImageFromDockerfile()
                    .withFileFromPath("Dockerfile", E2eTestSupport.PROJECT_ROOT.resolve("control-plane/Dockerfile"))
                    .withFileFromPath("build/libs/" + CONTROL_PLANE_JAR.getFileName(), CONTROL_PLANE_JAR)
    )
            .withExposedPorts(8080, 8081)
            .withNetwork(network)
            .withNetworkAliases("control-plane")
            .withEnv("SYNC_QUEUE_ENABLED", "false")
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
        Map<String, Object> spec = E2eApiSupport.poolFunctionSpec(
                "e2e-echo",
                E2eTestSupport.versionedImage("function-runtime"),
                endpointUrl
        );
        E2eApiSupport.registerFunction(spec);
        E2eApiSupport.awaitSyncInvokeSuccess("e2e-echo", "hi");

        String executionId = E2eApiSupport.enqueue("e2e-echo", "payload", "abc");
        String executionId2 = E2eApiSupport.enqueue("e2e-echo", "payload", "abc");

        org.junit.jupiter.api.Assertions.assertEquals(executionId, executionId2);

        E2eApiSupport.awaitExecutionSuccess(executionId, Duration.ofSeconds(10));
    }

    @Test
    void e2ePrometheusMetricsExposed() {
        String metrics = E2eApiSupport.fetchPrometheusMetrics(
                "http://" + controlPlane.getHost() + ":" + controlPlane.getMappedPort(8081) + "/actuator/prometheus");
        E2eApiSupport.assertMetricPresent(metrics, "function_enqueue_total");
    }
}
