package it.unimib.datai.nanofaas.controlplane.dispatch;

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
import it.unimib.datai.nanofaas.controlplane.config.KubernetesProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        ScalingConfig scaling = new ScalingConfig(
                ScalingStrategy.INTERNAL,
                2,
                10,
                List.of(new ScalingMetric("queue_depth", "5", null))
        );
        return new FunctionSpec(
                "echo",
                "nanofaas/function-runtime:0.5.0",
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

    @Test
    void deploymentReplicaSetPodFlow_updatesReadyReplicasAndScaling() {
        String serviceUrl = resourceManager.provision(spec());

        Deployment deployment = client.apps().deployments().inNamespace("default").withName("fn-echo").get();
        assertNotNull(deployment);
        assertEquals(2, deployment.getSpec().getReplicas());
        assertTrue(serviceUrl.contains("fn-echo.default.svc.cluster.local"));

        ReplicaSet replicaSet = buildReplicaSet();
        client.apps().replicaSets().inNamespace("default").resource(replicaSet).create();

        client.pods().inNamespace("default").resource(buildPod("fn-echo-rs-pod-1")).create();
        client.pods().inNamespace("default").resource(buildPod("fn-echo-rs-pod-2")).create();

        Deployment readyTwo = new DeploymentBuilder(deployment)
                .editOrNewStatus()
                .withReadyReplicas(2)
                .endStatus()
                .build();
        client.apps().deployments().inNamespace("default").resource(readyTwo).updateStatus();
        assertEquals(2, resourceManager.getReadyReplicas("echo"));

        resourceManager.setReplicas("echo", 1);
        Deployment scaled = client.apps().deployments().inNamespace("default").withName("fn-echo").get();
        assertNotNull(scaled);
        assertEquals(1, scaled.getSpec().getReplicas());

        client.pods().inNamespace("default").withName("fn-echo-rs-pod-2").delete();
        Deployment readyOne = new DeploymentBuilder(scaled)
                .editOrNewStatus()
                .withReadyReplicas(1)
                .endStatus()
                .build();
        client.apps().deployments().inNamespace("default").resource(readyOne).updateStatus();

        assertEquals(1, resourceManager.getReadyReplicas("echo"));
        assertEquals(
                1,
                client.pods().inNamespace("default").withLabel("function", "echo").list().getItems().size()
        );
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
                .withImage("nanofaas/function-runtime:0.5.0")
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
                .withImage("nanofaas/function-runtime:0.5.0")
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
