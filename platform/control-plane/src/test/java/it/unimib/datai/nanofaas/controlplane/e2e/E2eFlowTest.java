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
    private static final java.nio.file.Path REPOSITORY_ROOT = E2eTestSupport.PROJECT_ROOT.getParent();
    private static final java.nio.file.Path WARM_ECHO_JAR = E2eTestSupport.resolveBootJar(
            REPOSITORY_ROOT.resolve("services/java/warm-echo/build/libs"),
            "warm-echo-");

    private static final GenericContainer<?> warmEcho = new GenericContainer<>(
            new ImageFromDockerfile()
                    .withFileFromPath("Dockerfile", REPOSITORY_ROOT.resolve("services/java/warm-echo/Dockerfile"))
                    .withFileFromPath("build/libs/" + WARM_ECHO_JAR.getFileName(), WARM_ECHO_JAR)
    )
            .withExposedPorts(8080)
            .withNetwork(network)
            .withNetworkAliases("warm-echo")
            .waitingFor(Wait.forListeningPort());

    private static final GenericContainer<?> controlPlane = E2eTestSupport.createControlPlaneContainer(
            network,
            Duration.ofSeconds(60));

    @BeforeAll
    static void startContainers() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker not available");
        warmEcho.start();
        controlPlane.start();
        RestAssured.baseURI = "http://" + controlPlane.getHost();
        RestAssured.port = controlPlane.getMappedPort(8080);
    }

    @Test
    void e2eRegisterInvokeAndPoll() {
        String endpointUrl = "http://warm-echo:8080/invoke";
        Map<String, Object> spec = E2eApiSupport.poolFunctionSpec(
                "e2e-echo",
                E2eTestSupport.versionedImage("java-warm-echo"),
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
