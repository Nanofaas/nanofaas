package com.mcfaas.controlplane.e2e;

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
import io.restassured.http.ContentType;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.nio.file.Files;
import java.nio.file.Path;

class K8sE2eTest {
    private static final String NS = System.getenv().getOrDefault("MCFAAS_E2E_NAMESPACE", "mcfaas-e2e");
    private static final String CONTROL_IMAGE = System.getenv().getOrDefault("CONTROL_PLANE_IMAGE", "mcfaas/control-plane:0.5.0");
    private static final String RUNTIME_IMAGE = System.getenv().getOrDefault("FUNCTION_RUNTIME_IMAGE", "mcfaas/function-runtime:0.5.0");
    private static KubernetesClient client;

    @BeforeAll
    static void setupCluster() {
        String kubeconfig = System.getenv("KUBECONFIG");
        assumeTrue(kubeconfig != null && !kubeconfig.isBlank(),
            "KUBECONFIG not set. Run scripts/setup-multipass-kind.sh and export KUBECONFIG.");
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
            Map<String, Object> spec = Map.of(
                    "name", "k8s-echo",
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
                .body("name", equalTo("k8s-echo"));

            RestAssured.get("/v1/functions")
                    .then()
                    .statusCode(200)
                    .body("name", hasItem("k8s-echo"));

            RestAssured.get("/v1/functions/k8s-echo")
                    .then()
                    .statusCode(200)
                    .body("name", equalTo("k8s-echo"))
                    .body("image", equalTo(RUNTIME_IMAGE));

            RestAssured.given()
                    .contentType(ContentType.JSON)
                    .body(Map.of("input", Map.of("message", "hi")))
                    .post("/v1/functions/k8s-echo:invoke")
                    .then()
                    .statusCode(200)
                    .body("status", equalTo("success"))
                    .body("output.message", equalTo("hi"));

            RestAssured.given()
                    .contentType(ContentType.JSON)
                    .body(Map.of("input", Map.of("message", "hello")))
                    .post("/v1/functions/k8s-echo:invoke")
                    .then()
                    .statusCode(200)
                    .body("status", equalTo("success"))
                    .body("output.message", equalTo("hello"));

            String executionId = RestAssured.given()
                    .contentType(ContentType.JSON)
                    .header("Idempotency-Key", "abc")
                    .body(Map.of("input", "payload"))
                    .post("/v1/functions/k8s-echo:enqueue")
                    .then()
                    .statusCode(202)
                    .body("executionId", notNullValue())
                    .extract()
                    .path("executionId");

            String executionId2 = RestAssured.given()
                    .contentType(ContentType.JSON)
                    .header("Idempotency-Key", "abc")
                    .body(Map.of("input", "payload"))
                    .post("/v1/functions/k8s-echo:enqueue")
                    .then()
                    .statusCode(202)
                    .extract()
                    .path("executionId");

            org.junit.jupiter.api.Assertions.assertEquals(executionId, executionId2);

            Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                    RestAssured.get("/v1/executions/{id}", executionId)
                            .then()
                            .statusCode(200)
                            .body("status", equalTo("success")));

            String metrics = RestAssured.get("http://localhost:" + mgmtPort + "/actuator/prometheus")
                    .then()
                    .statusCode(200)
                    .extract()
                    .asString();
            org.junit.jupiter.api.Assertions.assertTrue(metrics.contains("function_enqueue_total"));
            org.junit.jupiter.api.Assertions.assertTrue(metrics.contains("function_success_total"));
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
