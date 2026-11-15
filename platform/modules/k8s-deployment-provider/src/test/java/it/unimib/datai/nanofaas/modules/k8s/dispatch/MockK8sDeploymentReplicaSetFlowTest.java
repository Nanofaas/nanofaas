package it.unimib.datai.nanofaas.modules.k8s.dispatch;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingMetric;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatus;
import it.unimib.datai.nanofaas.modules.k8s.config.KubernetesProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnableKubernetesMockClient(crud = true)
class MockK8sDeploymentReplicaSetFlowTest {
    KubernetesClient client;

    private KubernetesResourceManager resourceManager;

    @BeforeEach
    void setUp() {
        KubernetesProperties properties = new KubernetesProperties("default", null);
        @SuppressWarnings("unchecked")
        ObjectProvider<KubernetesClient> clientProvider = mock(ObjectProvider.class);
        when(clientProvider.getObject()).thenReturn(client);
        resourceManager = new KubernetesResourceManager(clientProvider, properties);
    }

    private FunctionSpec spec() {
        return spec(2);
    }

    private FunctionSpec spec(int minReplicas) {
        ScalingConfig scaling = new ScalingConfig(
                ScalingStrategy.INTERNAL,
                minReplicas,
                10,
                List.of(new ScalingMetric("queue_depth", "5", null))
        );
        return new FunctionSpec(
                "echo",
                "nanofaas/java-warm-echo:0.5.0",
                List.of(),
                Map.of(),
                null,
                30_000,
                4,
                100,
                3,
                null,
                ExecutionMode.DEPLOYMENT,
                RuntimeMode.HTTP,
                null,
                scaling
        );
    }

    /** {@code clusterIP} is immutable: a ClusterIP Service only becomes headless by being recreated. */
    @Test
    void reconcileRecreatesAServiceThatIsNotHeadless() {
        client.services().inNamespace("default").resource(clusterIpService()).create();

        resourceManager.reconcile(spec(), 1, persistedObjects());

        var service = client.services().inNamespace("default").withName("fn-echo").get();
        assertEquals("None", service.getSpec().getClusterIP());
        assertNull(service.getMetadata().getAnnotations().get(ORIGINAL), "the old Service was patched, not recreated");
    }

    @Test
    void provisionReplacesAServiceThatIsNotHeadless() {
        client.services().inNamespace("default").resource(clusterIpService()).create();

        resourceManager.provision(spec());

        var service = client.services().inNamespace("default").withName("fn-echo").get();
        assertEquals("None", service.getSpec().getClusterIP());
        assertNull(service.getMetadata().getAnnotations().get(ORIGINAL), "the old Service was patched, not recreated");
    }

    @Test
    void reconcileLeavesAHeadlessServiceAlone() {
        var headless = new io.fabric8.kubernetes.api.model.ServiceBuilder(clusterIpService())
                .editSpec().withClusterIP("None").endSpec().build();
        client.services().inNamespace("default").resource(headless).create();

        resourceManager.reconcile(spec(), 1, persistedObjects());

        var service = client.services().inNamespace("default").withName("fn-echo").get();
        assertEquals("true", service.getMetadata().getAnnotations().get(ORIGINAL));
    }

    private static final String ORIGINAL = "test.nanofaas/original";

    private static io.fabric8.kubernetes.api.model.Service clusterIpService() {
        return new io.fabric8.kubernetes.api.model.ServiceBuilder()
                .withNewMetadata().withName("fn-echo").addToAnnotations(ORIGINAL, "true").endMetadata()
                .withNewSpec().withType("ClusterIP").withClusterIP("10.43.0.9")
                    .addToSelector("function", "echo")
                    .addNewPort().withPort(8080).withTargetPort(new io.fabric8.kubernetes.api.model.IntOrString(8080)).endPort()
                .endSpec().build();
    }

    private static Map<String, String> persistedObjects() {
        return Map.of(
                it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult.DEPLOYMENT, "fn-echo",
                it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult.SERVICE, "fn-echo",
                it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult.NAMESPACE, "default");
    }

    @Test
    void deploymentReplicaSetPodFlow_updatesReadyReplicasAndScaling() {
        resourceManager.provision(spec());

        Deployment deployment = client.apps().deployments().inNamespace("default").withName("fn-echo").get();
        assertNotNull(deployment);
        assertEquals(2, deployment.getSpec().getReplicas());

        ReplicaSet replicaSet = buildReplicaSet();
        client.apps().replicaSets().inNamespace("default").resource(replicaSet).create();

        client.pods().inNamespace("default").resource(buildPod("fn-echo-rs-pod-1")).create();
        client.pods().inNamespace("default").resource(buildPod("fn-echo-rs-pod-2")).create();

        Deployment readyTwo = new DeploymentBuilder(deployment)
                .editOrNewStatus()
                .withReadyReplicas(2)
                .withObservedGeneration(deployment.getMetadata().getGeneration())
                .endStatus()
                .build();
        client.apps().deployments().inNamespace("default").resource(readyTwo).updateStatus();
        assertEquals(2, resourceManager.getReplicaStatus("echo").readyReplicas());

        resourceManager.setReplicas("echo", 1);
        Deployment scaled = client.apps().deployments().inNamespace("default").withName("fn-echo").get();
        assertNotNull(scaled);
        assertEquals(1, scaled.getSpec().getReplicas());

        client.pods().inNamespace("default").withName("fn-echo-rs-pod-2").delete();
        Deployment readyOne = new DeploymentBuilder(scaled)
                .editOrNewStatus()
                .withReadyReplicas(1)
                .withObservedGeneration(scaled.getMetadata().getGeneration())
                .endStatus()
                .build();
        client.apps().deployments().inNamespace("default").resource(readyOne).updateStatus();

        assertEquals(1, resourceManager.getReplicaStatus("echo").readyReplicas());
        assertEquals(
                1,
                client.pods().inNamespace("default").withLabel("function", "echo").list().getItems().size()
        );
    }

    @Test
    void deploymentWithZeroMinReplicas_scalesToOneAndReportsReadyStatus() {
        resourceManager.provision(spec(0));

        Deployment deployment = client.apps().deployments().inNamespace("default").withName("fn-echo").get();
        assertNotNull(deployment);
        assertEquals(0, deployment.getSpec().getReplicas());

        resourceManager.setReplicas("echo", 1);
        Deployment scaled = client.apps().deployments().inNamespace("default").withName("fn-echo").get();
        assertNotNull(scaled);
        assertEquals(1, scaled.getSpec().getReplicas());

        client.apps().deployments().inNamespace("default")
                .resource(new DeploymentBuilder(scaled)
                        .editOrNewStatus()
                        .withReadyReplicas(1)
                .withObservedGeneration(scaled.getMetadata().getGeneration())
                        .endStatus()
                        .build())
                .updateStatus();

        assertEquals(new ReplicaStatus(1, 1), resourceManager.getReplicaStatus("echo"));
    }

    private ReplicaSet buildReplicaSet() {
        return new ReplicaSetBuilder()
                .withNewMetadata()
                .withName("fn-echo-rs")
                .withNamespace("default")
                .addToLabels("app", "nanofaas")
                .addToLabels("function", "echo")
                .endMetadata()
                .withNewSpec()
                .withReplicas(2)
                .withNewSelector()
                .addToMatchLabels("function", "echo")
                .endSelector()
                .withNewTemplate()
                .withNewMetadata()
                .addToLabels("function", "echo")
                .addToLabels("app", "nanofaas")
                .endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName("runtime")
                .withImage("nanofaas/java-warm-echo:0.5.0")
                .endContainer()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
    }

    private Pod buildPod(String name) {
        return new PodBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace("default")
                .addToLabels("function", "echo")
                .addToLabels("app", "nanofaas")
                .endMetadata()
                .withNewSpec()
                .addNewContainer()
                .withName("runtime")
                .withImage("nanofaas/java-warm-echo:0.5.0")
                .endContainer()
                .endSpec()
                .withNewStatus()
                .withPhase("Running")
                .addNewCondition()
                .withType("Ready")
                .withStatus("True")
                .endCondition()
                .endStatus()
                .build();
    }
}
