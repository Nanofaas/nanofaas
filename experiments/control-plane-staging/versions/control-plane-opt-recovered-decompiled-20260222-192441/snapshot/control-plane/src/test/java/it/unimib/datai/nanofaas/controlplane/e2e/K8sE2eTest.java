package it.unimib.datai.nanofaas.controlplane.e2e;

import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.LocalPortForward;
import io.restassured.RestAssured;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class K8sE2eTest {
    private static final String NS = System.getenv().getOrDefault("NANOFAAS_E2E_NAMESPACE", "nanofaas-e2e");
    private static final String CONTROL_IMAGE = System.getenv().getOrDefault("CONTROL_PLANE_IMAGE", "nanofaas/control-plane:e2e");
    private static final String RUNTIME_IMAGE = System.getenv().getOrDefault("FUNCTION_RUNTIME_IMAGE", "nanofaas/function-runtime:e2e");
    private static KubernetesClient client;

    @BeforeAll
    static void setupCluster() {
        String kubeconfig = System.getenv("KUBECONFIG");
        assumeTrue(kubeconfig != null && !kubeconfig.isBlank(),
            "KUBECONFIG not set. Run scripts/e2e-k8s-vm.sh or export a valid k3s kubeconfig.");
        assumeTrue(Files.exists(Path.of(kubeconfig)),
            "KUBECONFIG file not found at: " + kubeconfig);

        client = new KubernetesClientBuilder().build();
        assumeTrue(client.getConfiguration() != null && client.getConfiguration().getMasterUrl() != null,
            "Kubernetes client not configured. Check KUBECONFIG.");

        Namespace namespace = new NamespaceBuilder()
                .withNewMetadata()
                .withName(NS)
                .endMetadata()
                .build();
        client.namespaces().resource(namespace).createOrReplace();

        client.apps().deployments().inNamespace(NS).resource(controlPlaneDeployment()).createOrReplace();
        client.services().inNamespace(NS).resource(controlPlaneService()).createOrReplace();

        client.apps().deployments().inNamespace(NS).resource(functionRuntimeDeployment()).createOrReplace();
        client.services().inNamespace(NS).resource(functionRuntimeService()).createOrReplace();

        Awaitility.await().atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
            Deployment control = client.apps().deployments().inNamespace(NS).withName("control-plane").get();
            Deployment runtime = client.apps().deployments().inNamespace(NS).withName("function-runtime").get();
            Integer controlReady = control.getStatus().getReadyReplicas();
            Integer runtimeReady = runtime.getStatus().getReadyReplicas();
            org.junit.jupiter.api.Assertions.assertEquals(1, controlReady == null ? 0 : controlReady);
            org.junit.jupiter.api.Assertions.assertEquals(1, runtimeReady == null ? 0 : runtimeReady);
        });

        Awaitility.await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
            Endpoints control = client.endpoints().inNamespace(NS).withName("control-plane").get();
            Endpoints runtime = client.endpoints().inNamespace(NS).withName("function-runtime").get();
            org.junit.jupiter.api.Assertions.assertTrue(hasReadyEndpoint(control));
            org.junit.jupiter.api.Assertions.assertTrue(hasReadyEndpoint(runtime));
        });
    }

    @AfterAll
    static void cleanup() {
        if (client != null) {
            client.namespaces().withName(NS).delete();
            client.close();
        }
    }

    @Test
    void k8sRegisterInvokeAndPoll() throws Exception {
        try (LocalPortForward apiForward = client.services().inNamespace(NS).withName("control-plane").portForward(8080);
             LocalPortForward mgmtForward = client.services().inNamespace(NS).withName("control-plane").portForward(8081)) {

            RestAssured.baseURI = "http://localhost";
            RestAssured.port = apiForward.getLocalPort();
            int mgmtPort = mgmtForward.getLocalPort();
            awaitHealth(mgmtPort, "/actuator/health");
            awaitHealth(mgmtPort, "/actuator/health/liveness");
            awaitHealth(mgmtPort, "/actuator/health/readiness");

            String endpointUrl = "http://function-runtime." + NS + ".svc.cluster.local:8080/invoke";
            Map<String, Object> spec = E2eApiSupport.poolFunctionSpec("k8s-echo", RUNTIME_IMAGE, endpointUrl);
            E2eApiSupport.registerFunction(spec);

            RestAssured.get("/v1/functions")
                    .then()
                    .statusCode(200)
                    .body("name", hasItem("k8s-echo"));

            RestAssured.get("/v1/functions/k8s-echo")
                    .then()
                    .statusCode(200)
                    .body("name", equalTo("k8s-echo"))
                    .body("image", equalTo(RUNTIME_IMAGE));

            E2eApiSupport.awaitSyncInvokeSuccess("k8s-echo", "hi");
            E2eApiSupport.awaitSyncInvokeSuccess("k8s-echo", "hello");

            String executionId = E2eApiSupport.enqueue("k8s-echo", "payload", "abc");
            String executionId2 = E2eApiSupport.enqueue("k8s-echo", "payload", "abc");

            org.junit.jupiter.api.Assertions.assertEquals(executionId, executionId2);

            E2eApiSupport.awaitExecutionSuccess(executionId, Duration.ofSeconds(20));

            String metrics = E2eApiSupport.fetchPrometheusMetrics(
                    "http://localhost:" + mgmtPort + "/actuator/prometheus");
            E2eApiSupport.assertMetricPresent(metrics, "function_enqueue_total");
            E2eApiSupport.assertMetricPresent(metrics, "function_success_total");
        }
    }

    @Test
    void k8sSyncQueueBackpressure() throws Exception {
        try (LocalPortForward apiForward = client.services().inNamespace(NS).withName("control-plane").portForward(8080);
             LocalPortForward mgmtForward = client.services().inNamespace(NS).withName("control-plane").portForward(8081)) {

            RestAssured.baseURI = "http://localhost";
            RestAssured.port = apiForward.getLocalPort();
            int mgmtPort = mgmtForward.getLocalPort();
            awaitHealth(mgmtPort, "/actuator/health");
            awaitHealth(mgmtPort, "/actuator/health/liveness");
            awaitHealth(mgmtPort, "/actuator/health/readiness");

            String endpointUrl = "http://function-runtime." + NS + ".svc.cluster.local:8080/invoke";
            String fn = "k8s-echo-sync-queue";
            Map<String, Object> spec = E2eApiSupport.poolFunctionSpec(
                    fn, RUNTIME_IMAGE, endpointUrl, 5000, 1, 20, 3);
            E2eApiSupport.registerFunction(spec);
            E2eApiSupport.awaitSyncInvokeSuccess(fn, "warmup");

            Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
                List<io.restassured.response.Response> responses = E2eApiSupport.invokeSyncBurst(
                        fn, 12, i -> Map.of("message", "sync-" + i));
                long ok = responses.stream().filter(r -> r.statusCode() == 200).count();
                java.util.Optional<io.restassured.response.Response> rejected = responses.stream()
                        .filter(r -> r.statusCode() == 429)
                        .findFirst();

                org.junit.jupiter.api.Assertions.assertTrue(ok >= 1, "expected at least one 200 response");
                org.junit.jupiter.api.Assertions.assertTrue(rejected.isPresent(), "expected at least one 429 response");
                org.junit.jupiter.api.Assertions.assertEquals("2", rejected.get().getHeader("Retry-After"));
                org.junit.jupiter.api.Assertions.assertEquals("depth", rejected.get().getHeader("X-Queue-Reject-Reason"));
            });

            Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
                String metrics = E2eApiSupport.fetchPrometheusMetrics(
                        "http://localhost:" + mgmtPort + "/actuator/prometheus");

                E2eApiSupport.assertMetricSumAtLeast(metrics, "sync_queue_admitted_total", Map.of(), 1.0);
                E2eApiSupport.assertMetricSumAtLeast(metrics, "sync_queue_rejected_total", Map.of(), 1.0);
                E2eApiSupport.assertMetricSumAtLeast(metrics, "sync_queue_wait_seconds_count", Map.of(), 1.0);
                E2eApiSupport.assertMetricPresent(metrics, "sync_queue_depth");
            });
        }
    }

    @Test
    void k8sColdStartMetrics_areRecorded() throws Exception {
        try (LocalPortForward apiForward = client.services().inNamespace(NS).withName("control-plane").portForward(8080);
             LocalPortForward mgmtForward = client.services().inNamespace(NS).withName("control-plane").portForward(8081)) {

            RestAssured.baseURI = "http://localhost";
            RestAssured.port = apiForward.getLocalPort();
            int mgmtPort = mgmtForward.getLocalPort();
            awaitHealth(mgmtPort, "/actuator/health");
            awaitHealth(mgmtPort, "/actuator/health/readiness");

            String functionName = "k8s-cold-metrics";
            String endpointUrl = "http://function-runtime." + NS + ".svc.cluster.local:8080/invoke";
            E2eApiSupport.registerPoolFunction(functionName, RUNTIME_IMAGE, endpointUrl);

            E2eApiSupport.awaitSyncInvokeSuccess(functionName, "cold");
            E2eApiSupport.awaitSyncInvokeSuccess(functionName, "warm-1");
            E2eApiSupport.awaitSyncInvokeSuccess(functionName, "warm-2");

            String metrics = E2eApiSupport.fetchPrometheusMetrics(
                    "http://localhost:" + mgmtPort + "/actuator/prometheus");

            E2eApiSupport.assertMetricSumAtLeast(
                    metrics, "function_cold_start_total", Map.of("function", functionName), 1.0);
            E2eApiSupport.assertMetricSumAtLeast(
                    metrics, "function_warm_start_total", Map.of("function", functionName), 1.0);
            E2eApiSupport.assertMetricSumAtLeast(
                    metrics, "function_init_duration_ms_seconds_count", Map.of("function", functionName), 1.0);
        }
    }

    private static boolean hasReadyEndpoint(Endpoints endpoints) {
        if (endpoints == null || endpoints.getSubsets() == null) {
            return false;
        }
        return endpoints.getSubsets().stream().anyMatch(subset ->
                subset.getAddresses() != null && !subset.getAddresses().isEmpty());
    }

    private static Deployment controlPlaneDeployment() {
        return new DeploymentBuilder()
                .withNewMetadata()
                .withName("control-plane")
                .addToLabels("app", "control-plane")
                .endMetadata()
                .withNewSpec()
                .withReplicas(1)
                .withNewSelector().addToMatchLabels("app", "control-plane").endSelector()
                .withNewTemplate()
                .withNewMetadata().addToLabels("app", "control-plane").endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName("control-plane")
                .withImage(CONTROL_IMAGE)
                .addNewPort().withContainerPort(8080).endPort()
                .addNewPort().withContainerPort(8081).endPort()
                .addNewEnv().withName("POD_NAMESPACE").withValue(NS).endEnv()
                .addNewEnv().withName("SYNC_QUEUE_ENABLED").withValue("true").endEnv()
                .addNewEnv().withName("SYNC_QUEUE_ADMISSION_ENABLED").withValue("false").endEnv()
                .addNewEnv().withName("SYNC_QUEUE_MAX_DEPTH").withValue("1").endEnv()
                .addNewEnv().withName("SYNC_QUEUE_MAX_ESTIMATED_WAIT").withValue("2s").endEnv()
                .addNewEnv().withName("SYNC_QUEUE_MAX_QUEUE_WAIT").withValue("5s").endEnv()
                .addNewEnv().withName("SYNC_QUEUE_RETRY_AFTER_SECONDS").withValue("2").endEnv()
                .addNewEnv().withName("SYNC_QUEUE_THROUGHPUT_WINDOW").withValue("10s").endEnv()
                .addNewEnv().withName("SYNC_QUEUE_PER_FUNCTION_MIN_SAMPLES").withValue("1").endEnv()
                .endContainer()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
    }

    private static Service controlPlaneService() {
        return new ServiceBuilder()
                .withNewMetadata()
                .withName("control-plane")
                .endMetadata()
                .withNewSpec()
                .addToSelector("app", "control-plane")
                .addNewPort().withName("http").withPort(8080).withTargetPort(new IntOrString(8080)).endPort()
                .addNewPort().withName("mgmt").withPort(8081).withTargetPort(new IntOrString(8081)).endPort()
                .endSpec()
                .build();
    }

    private static Deployment functionRuntimeDeployment() {
        return new DeploymentBuilder()
                .withNewMetadata()
                .withName("function-runtime")
                .addToLabels("app", "function-runtime")
                .endMetadata()
                .withNewSpec()
                .withReplicas(1)
                .withNewSelector().addToMatchLabels("app", "function-runtime").endSelector()
                .withNewTemplate()
                .withNewMetadata().addToLabels("app", "function-runtime").endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName("function-runtime")
                .withImage(RUNTIME_IMAGE)
                .addNewPort().withContainerPort(8080).endPort()
                .endContainer()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
    }

    private static Service functionRuntimeService() {
        return new ServiceBuilder()
                .withNewMetadata()
                .withName("function-runtime")
                .endMetadata()
                .withNewSpec()
                .addToSelector("app", "function-runtime")
                .addNewPort().withName("http").withPort(8080).withTargetPort(new IntOrString(8080)).endPort()
                .endSpec()
                .build();
    }

    private static void awaitHealth(int port, String path) {
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() ->
                RestAssured.get("http://localhost:" + port + path)
                        .then()
                        .statusCode(200)
                        .body("status", equalTo("UP")));
    }

}
