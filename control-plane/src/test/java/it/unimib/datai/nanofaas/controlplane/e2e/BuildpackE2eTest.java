package it.unimib.datai.nanofaas.controlplane.e2e;

import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Testcontainers
@Tag("inter_e2e")
class BuildpackE2eTest {
    private static final String CONTROL_IMAGE = "nanofaas/control-plane:buildpack";
    private static final String RUNTIME_IMAGE = "nanofaas/function-runtime:buildpack";
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
        Map<String, Object> spec = E2eApiSupport.poolFunctionSpec("bp-echo", RUNTIME_IMAGE, endpointUrl);
        E2eApiSupport.registerFunction(spec);
        E2eApiSupport.awaitSyncInvokeSuccess("bp-echo", "hi");

        String executionId = E2eApiSupport.enqueue("bp-echo", "payload");
        E2eApiSupport.awaitExecutionSuccess(executionId, Duration.ofSeconds(20));

        String metrics = E2eApiSupport.fetchPrometheusMetrics(
                "http://" + controlPlane.getHost() + ":" + controlPlane.getMappedPort(8081) + "/actuator/prometheus");
        E2eApiSupport.assertMetricPresent(metrics, "function_enqueue_total");
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
