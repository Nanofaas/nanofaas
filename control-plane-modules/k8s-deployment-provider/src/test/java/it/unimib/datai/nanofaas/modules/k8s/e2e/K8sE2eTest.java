package it.unimib.datai.nanofaas.modules.k8s.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.LocalPortForward;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import it.unimib.datai.nanofaas.controlplane.e2e.E2eApiSupport;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class K8sE2eTest {
    private static final String DEFAULT_NS = System.getenv().getOrDefault("NANOFAAS_E2E_NAMESPACE", "nanofaas-e2e");
    private static final String RUNTIME_IMAGE = System.getenv().getOrDefault("FUNCTION_RUNTIME_IMAGE", "nanofaas/function-runtime:e2e");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static KubernetesClient client;

    @BeforeAll
    static void setupCluster() throws Exception {
        String kubeconfig = System.getenv("KUBECONFIG");
        assumeTrue(kubeconfig != null && !kubeconfig.isBlank(),
            "KUBECONFIG not set. Run controlplane-tool e2e run k3s-junit-curl or export a valid k3s kubeconfig.");
        assumeTrue(Files.exists(Path.of(kubeconfig)),
            "KUBECONFIG file not found at: " + kubeconfig);

        client = new KubernetesClientBuilder().build();
        assumeTrue(client.getConfiguration() != null && client.getConfiguration().getMasterUrl() != null,
            "Kubernetes client not configured. Check KUBECONFIG.");
        assertNotNull(
                client.namespaces().withName(namespace()).get(),
                "expected namespace to be created by the Python runner");
        awaitDeploymentReady("nanofaas-control-plane");
        awaitDeploymentReady("function-runtime");
        awaitServiceReady("control-plane");
    }

    @AfterAll
    static void cleanup() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void k8sRegisterInvokeAndPoll() throws Exception {
        awaitControlPlaneReady();
        try (LocalPortForward apiForward = client.services().inNamespace(namespace()).withName("control-plane").portForward(8080);
             LocalPortForward mgmtForward = client.services().inNamespace(namespace()).withName("control-plane").portForward(8081)) {

            RestAssured.baseURI = "http://localhost";
            RestAssured.port = apiForward.getLocalPort();
            int mgmtPort = mgmtForward.getLocalPort();
            awaitHealth(mgmtPort, "/actuator/health");
            awaitHealth(mgmtPort, "/actuator/health/liveness");
            awaitHealth(mgmtPort, "/actuator/health/readiness");

            for (RegistrationTarget target : registrationTargets(scenarioManifest())) {
                var registerResponse = E2eApiSupport.registerDeploymentFunction(target.name(), target.image());
                String endpointUrl = registerResponse.then()
                        .statusCode(201)
                        .body("name", equalTo(target.name()))
                        .body("image", equalTo(target.image()))
                        .body("requestedExecutionMode", equalTo("DEPLOYMENT"))
                        .body("effectiveExecutionMode", equalTo("DEPLOYMENT"))
                        .body("deploymentBackend", equalTo("k8s"))
                        .body("endpointUrl", notNullValue())
                        .extract()
                        .path("endpointUrl");

                org.junit.jupiter.api.Assertions.assertTrue(
                        endpointUrl.startsWith(
                                "http://fn-" + target.name() + "." + namespace() + ".svc.cluster.local:8080/invoke"),
                        "expected provider-derived service endpoint but was " + endpointUrl);
                awaitManagedFunctionReady(target.name());

                RestAssured.get("/v1/functions")
                        .then()
                        .statusCode(200)
                        .body("name", hasItem(target.name()));

                RestAssured.get("/v1/functions/{name}", target.name())
                        .then()
                        .statusCode(200)
                        .body("name", equalTo(target.name()))
                        .body("image", equalTo(target.image()))
                        .body("requestedExecutionMode", equalTo("DEPLOYMENT"))
                        .body("effectiveExecutionMode", equalTo("DEPLOYMENT"))
                        .body("deploymentBackend", equalTo("k8s"))
                        .body("endpointUrl", equalTo(endpointUrl));

                awaitScenarioInvokeSuccess(target);

                String executionId = E2eApiSupport.enqueue(target.name(), target.payload(), target.name() + "-idem");
                String executionId2 = E2eApiSupport.enqueue(target.name(), target.payload(), target.name() + "-idem");

                org.junit.jupiter.api.Assertions.assertEquals(executionId, executionId2);

                E2eApiSupport.awaitExecutionSuccess(executionId, Duration.ofSeconds(20));

                RestAssured.delete("/v1/functions/{name}", target.name())
                        .then()
                        .statusCode(204);
                awaitManagedFunctionDeleted(target.name());

                RestAssured.get("/v1/functions/{name}", target.name())
                        .then()
                        .statusCode(404);
            }

            String metrics = E2eApiSupport.fetchPrometheusMetrics(
                    "http://localhost:" + mgmtPort + "/actuator/prometheus");
            E2eApiSupport.assertMetricPresent(metrics, "function_enqueue_total");
            E2eApiSupport.assertMetricPresent(metrics, "function_success_total");
        }
    }

    @Test
    void k8sSyncQueueBackpressure() throws Exception {
        awaitControlPlaneReady();
        try (LocalPortForward apiForward = client.services().inNamespace(namespace()).withName("control-plane").portForward(8080);
             LocalPortForward mgmtForward = client.services().inNamespace(namespace()).withName("control-plane").portForward(8081)) {

            RestAssured.baseURI = "http://localhost";
            RestAssured.port = apiForward.getLocalPort();
            int mgmtPort = mgmtForward.getLocalPort();
            awaitHealth(mgmtPort, "/actuator/health");
            awaitHealth(mgmtPort, "/actuator/health/liveness");
            awaitHealth(mgmtPort, "/actuator/health/readiness");

            String fn = "k8s-echo-sync-queue";
            E2eApiSupport.registerFunction(E2eApiSupport.deploymentFunctionSpec(
                    fn, RUNTIME_IMAGE, 5000, 1, 20, 3));
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
                E2eApiSupport.assertMetricSumAtLeastAny(
                        metrics,
                        Map.of(),
                        1.0,
                        "sync_queue_wait_seconds_count",
                        "sync_queue_wait_count");
                E2eApiSupport.assertMetricPresent(metrics, "sync_queue_depth");
            });
        }
    }

    @Test
    void k8sColdStartMetrics_areRecorded() throws Exception {
        awaitControlPlaneReady();
        try (LocalPortForward apiForward = client.services().inNamespace(namespace()).withName("control-plane").portForward(8080);
             LocalPortForward mgmtForward = client.services().inNamespace(namespace()).withName("control-plane").portForward(8081)) {

            RestAssured.baseURI = "http://localhost";
            RestAssured.port = apiForward.getLocalPort();
            int mgmtPort = mgmtForward.getLocalPort();
            awaitHealth(mgmtPort, "/actuator/health");
            awaitHealth(mgmtPort, "/actuator/health/readiness");

            String functionName = "k8s-cold-metrics";
            E2eApiSupport.registerDeploymentFunction(functionName, RUNTIME_IMAGE);

            E2eApiSupport.awaitSyncInvokeSuccess(functionName, "cold");
            E2eApiSupport.awaitSyncInvokeSuccess(functionName, "warm-1");
            E2eApiSupport.awaitSyncInvokeSuccess(functionName, "warm-2");

            String metrics = E2eApiSupport.fetchPrometheusMetrics(
                    "http://localhost:" + mgmtPort + "/actuator/prometheus");

            E2eApiSupport.assertMetricSumAtLeast(
                    metrics, "function_cold_start_total", Map.of("function", functionName), 1.0);
            E2eApiSupport.assertMetricSumAtLeast(
                    metrics, "function_warm_start_total", Map.of("function", functionName), 1.0);
            E2eApiSupport.assertMetricSumAtLeastAny(
                    metrics,
                    Map.of("function", functionName),
                    1.0,
                    "function_init_duration_ms_seconds_count",
                    "function_init_duration_ms_count");
        }
    }

    static List<RegistrationTarget> registrationTargets(
            Optional<K8sE2eScenarioManifest> manifest) {
        if (manifest.isEmpty()) {
            return List.of(new RegistrationTarget(
                    "k8s-echo",
                    "legacy-echo",
                    RUNTIME_IMAGE,
                    Map.of("message", "hi")));
        }

        K8sE2eScenarioManifest resolvedManifest = manifest.get();
        return resolvedManifest.selectedFunctions().stream()
                .map(function -> new RegistrationTarget(
                        function.key(),
                        function.family(),
                        function.image() == null || function.image().isBlank() ? RUNTIME_IMAGE : function.image(),
                        readInvocationPayload(resolvedManifest, function)))
                .toList();
    }

    private static Optional<K8sE2eScenarioManifest> scenarioManifest() {
        return K8sE2eScenarioManifest.loadFromSystemProperty();
    }

    private static String namespace() {
        return scenarioManifest()
                .map(manifest -> manifest.namespaceOr(DEFAULT_NS))
                .orElse(DEFAULT_NS);
    }

    private static Object readInvocationPayload(
            K8sE2eScenarioManifest manifest,
            K8sE2eScenarioManifest.SelectedFunction function) {
        String payloadPath = resolvedPayloadPath(manifest, function);
        if (payloadPath == null || payloadPath.isBlank()) {
            return fallbackPayloadForFamily(function.family());
        }
        try {
            return OBJECT_MAPPER.readValue(Path.of(payloadPath).toFile(), Object.class);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read scenario payload: " + payloadPath, e);
        }
    }

    private static String resolvedPayloadPath(
            K8sE2eScenarioManifest manifest,
            K8sE2eScenarioManifest.SelectedFunction function) {
        String manifestPath = System.getProperty(K8sE2eScenarioManifest.SYSTEM_PROPERTY_NAME);
        if (manifestPath != null
                && !manifestPath.isBlank()
                && function.repoRelativePayloadPath() != null
                && !function.repoRelativePayloadPath().isBlank()) {
            Path remoteRepoRoot = Path.of(manifestPath)
                    .getParent()
                    .getParent()
                    .getParent()
                    .getParent();
            return remoteRepoRoot.resolve(function.repoRelativePayloadPath()).normalize().toString();
        }
        return function.resolvedPayloadPath(manifest.payloads()).orElse(null);
    }

    private static Object fallbackPayloadForFamily(String family) {
        return switch (family) {
            case "word-stats" -> Map.of(
                    "text", "nanofaas makes function demos measurable and repeatable",
                    "topN", 3);
            case "json-transform" -> Map.of(
                    "data", List.of(
                            Map.of("dept", "eng", "salary", 90000),
                            Map.of("dept", "eng", "salary", 110000),
                            Map.of("dept", "ops", "salary", 70000)),
                    "groupBy", "dept",
                    "operation", "avg",
                    "valueField", "salary");
            default -> Map.of("message", "hi");
        };
    }

    private static void awaitScenarioInvokeSuccess(RegistrationTarget target) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
            var response = RestAssured.given()
                    .contentType(ContentType.JSON)
                    .body(Map.of("input", target.payload()))
                    .post("/v1/functions/" + target.name() + ":invoke");
            response.then()
                    .statusCode(200)
                    .body("status", equalTo("success"));

            if ("word-stats".equals(target.family())) {
                response.then()
                        .body("output.wordCount", notNullValue())
                        .body("output.uniqueWords", notNullValue());
            } else if ("json-transform".equals(target.family())) {
                response.then()
                        .body("output.groups", notNullValue())
                        .body("output.operation", notNullValue());
            } else {
                response.then().body("output", notNullValue());
            }
        });
    }

    static record RegistrationTarget(String name, String family, String image, Object payload) {
    }

    private static boolean hasReadyEndpoint(Endpoints endpoints) {
        if (endpoints == null || endpoints.getSubsets() == null) {
            return false;
        }
        return endpoints.getSubsets().stream().anyMatch(subset ->
                subset.getAddresses() != null && !subset.getAddresses().isEmpty());
    }

    private static void awaitHealth(int port, String path) {
        Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() ->
                RestAssured.get("http://localhost:" + port + path)
                        .then()
                        .statusCode(200)
                        .body("status", equalTo("UP")));
    }

    private static void awaitControlPlaneReady() {
        awaitDeploymentReady("nanofaas-control-plane");
        awaitServiceReady("control-plane");
    }

    private static void awaitManagedFunctionReady(String functionName) {
        Awaitility.await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
            String deploymentName = "fn-" + functionName;
            Deployment deployment = client.apps().deployments().inNamespace(namespace()).withName(deploymentName).get();
            Integer ready = deployment == null || deployment.getStatus() == null ? null : deployment.getStatus().getReadyReplicas();
            org.junit.jupiter.api.Assertions.assertNotNull(ready, "expected deployment ready replicas");
            org.junit.jupiter.api.Assertions.assertTrue(ready >= 1, "expected at least one ready replica");

            Service service = client.services().inNamespace(namespace()).withName(deploymentName).get();
            org.junit.jupiter.api.Assertions.assertNotNull(service, "expected managed service to exist");
            Endpoints endpoints = client.endpoints().inNamespace(namespace()).withName(deploymentName).get();
            org.junit.jupiter.api.Assertions.assertTrue(hasReadyEndpoint(endpoints), "expected managed service endpoints");
        });
    }

    private static void awaitManagedFunctionDeleted(String functionName) {
        String deploymentName = "fn-" + functionName;
        Awaitility.await().atMost(Duration.ofMinutes(1)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
            org.junit.jupiter.api.Assertions.assertNull(
                    client.apps().deployments().inNamespace(namespace()).withName(deploymentName).get(),
                    "expected managed deployment to be deleted");
            org.junit.jupiter.api.Assertions.assertNull(
                    client.services().inNamespace(namespace()).withName(deploymentName).get(),
                    "expected managed service to be deleted");
        });
    }

    private static void awaitDeploymentReady(String deploymentName) {
        Awaitility.await().atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
            Deployment deployment = client.apps().deployments().inNamespace(namespace()).withName(deploymentName).get();
            Integer ready = deployment == null || deployment.getStatus() == null ? null : deployment.getStatus().getReadyReplicas();
            org.junit.jupiter.api.Assertions.assertEquals(1, ready == null ? 0 : ready);
        });
    }

    private static void awaitServiceReady(String serviceName) {
        Awaitility.await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
            Endpoints endpoints = client.endpoints().inNamespace(namespace()).withName(serviceName).get();
            org.junit.jupiter.api.Assertions.assertTrue(hasReadyEndpoint(endpoints));
        });
    }

}
