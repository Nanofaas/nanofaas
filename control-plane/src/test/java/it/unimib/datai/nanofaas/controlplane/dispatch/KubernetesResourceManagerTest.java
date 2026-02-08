package it.unimib.datai.nanofaas.controlplane.dispatch;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import it.unimib.datai.nanofaas.common.model.*;
import it.unimib.datai.nanofaas.controlplane.config.KubernetesProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnableKubernetesMockClient(crud = true)
class KubernetesResourceManagerTest {
    KubernetesClient client;

    private KubernetesResourceManager resourceManager;

    @BeforeEach
    void setUp() {
        KubernetesProperties properties = new KubernetesProperties("default", null, 10, null);
        @SuppressWarnings("unchecked")
        ObjectProvider<KubernetesClient> clientProvider = mock(ObjectProvider.class);
        when(clientProvider.getObject()).thenReturn(client);
        resourceManager = new KubernetesResourceManager(clientProvider, properties);
    }

    private FunctionSpec spec(ScalingConfig scaling) {
        return new FunctionSpec(
                "echo", "nanofaas/function-runtime:0.5.0",
                List.of(), Map.of(),
                null, 30000, 4, 100, 3,
                null, ExecutionMode.DEPLOYMENT, RuntimeMode.HTTP, null,
                scaling
        );
    }

    @Test
    void provision_createsDeploymentAndService() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.INTERNAL, 1, 10,
                List.of(new ScalingMetric("queue_depth", "5", null)));

        String url = resourceManager.provision(spec(scaling));

        assertNotNull(url);
        assertTrue(url.contains("fn-echo"));
        assertTrue(url.endsWith("/invoke"));

        // Verify Deployment created
        Deployment dep = client.apps().deployments().inNamespace("default").withName("fn-echo").get();
        assertNotNull(dep);
        assertEquals(1, dep.getSpec().getReplicas());

        // Verify Service created
        var svc = client.services().inNamespace("default").withName("fn-echo").get();
        assertNotNull(svc);
        assertEquals("ClusterIP", svc.getSpec().getType());
    }

    @Test
    void provision_createsHpaForHpaStrategy() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.HPA, 1, 5,
                List.of(new ScalingMetric("cpu", "80", null)));

        resourceManager.provision(spec(scaling));

        HorizontalPodAutoscaler hpa = client.autoscaling().v2().horizontalPodAutoscalers()
                .inNamespace("default").withName("fn-echo").get();
        assertNotNull(hpa);
        assertEquals(1, hpa.getSpec().getMinReplicas());
        assertEquals(5, hpa.getSpec().getMaxReplicas());
    }

    @Test
    void provision_doesNotCreateHpaForInternalStrategy() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.INTERNAL, 1, 10,
                List.of(new ScalingMetric("queue_depth", "5", null)));

        resourceManager.provision(spec(scaling));

        HorizontalPodAutoscaler hpa = client.autoscaling().v2().horizontalPodAutoscalers()
                .inNamespace("default").withName("fn-echo").get();
        assertNull(hpa);
    }

    @Test
    void provision_doesNotCreateHpaForNoneStrategy() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.NONE, 2, 10, List.of());

        resourceManager.provision(spec(scaling));

        HorizontalPodAutoscaler hpa = client.autoscaling().v2().horizontalPodAutoscalers()
                .inNamespace("default").withName("fn-echo").get();
        assertNull(hpa);

        // But Deployment should still be created
        Deployment dep = client.apps().deployments().inNamespace("default").withName("fn-echo").get();
        assertNotNull(dep);
    }

    @Test
    void deprovision_deletesAllResources() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.HPA, 1, 5,
                List.of(new ScalingMetric("cpu", "80", null)));
        resourceManager.provision(spec(scaling));

        // Verify resources exist
        assertNotNull(client.apps().deployments().inNamespace("default").withName("fn-echo").get());
        assertNotNull(client.services().inNamespace("default").withName("fn-echo").get());

        resourceManager.deprovision("echo");

        // Verify resources deleted
        assertNull(client.apps().deployments().inNamespace("default").withName("fn-echo").get());
        assertNull(client.services().inNamespace("default").withName("fn-echo").get());
    }

    @Test
    void getReadyReplicas_returnsZeroWhenDeploymentNotFound() {
        assertEquals(0, resourceManager.getReadyReplicas("nonexistent"));
    }

    @Test
    void provision_isIdempotent() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.INTERNAL, 1, 10,
                List.of(new ScalingMetric("queue_depth", "5", null)));

        String url1 = resourceManager.provision(spec(scaling));
        String url2 = resourceManager.provision(spec(scaling));

        assertEquals(url1, url2);

        // Should still have exactly one Deployment
        var deps = client.apps().deployments().inNamespace("default").list().getItems();
        assertEquals(1, deps.size());
    }
}
