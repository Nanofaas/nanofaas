package it.unimib.datai.nanofaas.controlplane.dispatch;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.api.model.autoscaling.v2.MetricSpec;
import it.unimib.datai.nanofaas.common.model.*;
import it.unimib.datai.nanofaas.controlplane.config.KubernetesProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KubernetesDeploymentBuilderTest {
    private KubernetesDeploymentBuilder builder;

    @BeforeEach
    void setUp() {
        KubernetesProperties properties = new KubernetesProperties("default", null, 10, null);
        builder = new KubernetesDeploymentBuilder(properties);
    }

    private FunctionSpec spec(ScalingConfig scaling) {
        return new FunctionSpec(
                "echo", "nanofaas/function-runtime:0.5.0",
                List.of(), Map.of("MY_VAR", "hello"),
                new ResourceSpec("250m", "128Mi"),
                30000, 4, 100, 3,
                null, ExecutionMode.DEPLOYMENT, RuntimeMode.HTTP, null,
                scaling
        );
    }

    @Test
    void buildDeployment_correctMetadata() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.INTERNAL, 2, 10,
                List.of(new ScalingMetric("queue_depth", "5", null)));
        Deployment deployment = builder.buildDeployment(spec(scaling));

        assertEquals("fn-echo", deployment.getMetadata().getName());
        assertEquals("nanofaas", deployment.getMetadata().getLabels().get("app"));
        assertEquals("echo", deployment.getMetadata().getLabels().get("function"));
    }

    @Test
    void buildDeployment_usesMinReplicasFromScalingConfig() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.INTERNAL, 3, 10,
                List.of(new ScalingMetric("queue_depth", "5", null)));
        Deployment deployment = builder.buildDeployment(spec(scaling));

        assertEquals(3, deployment.getSpec().getReplicas());
    }

    @Test
    void buildDeployment_defaultsTo1ReplicaWhenNoScalingConfig() {
        Deployment deployment = builder.buildDeployment(spec(null));
        assertEquals(1, deployment.getSpec().getReplicas());
    }

    @Test
    void buildDeployment_containsRequiredEnvVars() {
        Deployment deployment = builder.buildDeployment(spec(null));
        Container container = deployment.getSpec().getTemplate().getSpec().getContainers().get(0);
        List<EnvVar> envVars = container.getEnv();

        assertTrue(envVars.stream().anyMatch(e -> "FUNCTION_NAME".equals(e.getName()) && "echo".equals(e.getValue())));
        assertTrue(envVars.stream().anyMatch(e -> "WARM".equals(e.getName()) && "true".equals(e.getValue())));
        assertTrue(envVars.stream().anyMatch(e -> "TIMEOUT_MS".equals(e.getName()) && "30000".equals(e.getValue())));
        assertTrue(envVars.stream().anyMatch(e -> "EXECUTION_MODE".equals(e.getName()) && "HTTP".equals(e.getValue())));
    }

    @Test
    void buildDeployment_filtersReservedEnvVars() {
        FunctionSpec specWithReserved = new FunctionSpec(
                "echo", "nanofaas/function-runtime:0.5.0",
                List.of(), Map.of("FUNCTION_NAME", "hacked", "MY_VAR", "ok"),
                null, 30000, 4, 100, 3,
                null, ExecutionMode.DEPLOYMENT, RuntimeMode.HTTP, null, null
        );
        Deployment deployment = builder.buildDeployment(specWithReserved);
        Container container = deployment.getSpec().getTemplate().getSpec().getContainers().get(0);

        // FUNCTION_NAME should be "echo" (set by builder), not "hacked" (from user env)
        long fnNameCount = container.getEnv().stream()
                .filter(e -> "FUNCTION_NAME".equals(e.getName())).count();
        assertEquals(1, fnNameCount);
        assertEquals("echo", container.getEnv().stream()
                .filter(e -> "FUNCTION_NAME".equals(e.getName())).findFirst().get().getValue());

        // MY_VAR should be present
        assertTrue(container.getEnv().stream().anyMatch(e -> "MY_VAR".equals(e.getName()) && "ok".equals(e.getValue())));
    }

    @Test
    void buildDeployment_hasReadinessProbe() {
        Deployment deployment = builder.buildDeployment(spec(null));
        Container container = deployment.getSpec().getTemplate().getSpec().getContainers().get(0);

        assertNotNull(container.getReadinessProbe());
        assertEquals("/invoke", container.getReadinessProbe().getHttpGet().getPath());
        assertEquals(8080, container.getReadinessProbe().getHttpGet().getPort().getIntVal());
    }

    @Test
    void buildDeployment_setsResources() {
        Deployment deployment = builder.buildDeployment(spec(null));
        Container container = deployment.getSpec().getTemplate().getSpec().getContainers().get(0);

        assertNotNull(container.getResources().getRequests());
        assertEquals("250m", container.getResources().getRequests().get("cpu").toString());
        assertEquals("128Mi", container.getResources().getRequests().get("memory").toString());
    }

    @Test
    void buildService_correctStructure() {
        var service = builder.buildService(spec(null));

        assertEquals("fn-echo", service.getMetadata().getName());
        assertEquals("ClusterIP", service.getSpec().getType());
        assertEquals("echo", service.getSpec().getSelector().get("function"));
        assertEquals(8080, service.getSpec().getPorts().get(0).getPort());
    }

    @Test
    void buildHpa_returnsNullForNonHpaStrategy() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.INTERNAL, 1, 10,
                List.of(new ScalingMetric("queue_depth", "5", null)));
        assertNull(builder.buildHpa(spec(scaling)));
    }

    @Test
    void buildHpa_returnsNullForNoneStrategy() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.NONE, 1, 10,
                List.of(new ScalingMetric("queue_depth", "5", null)));
        assertNull(builder.buildHpa(spec(scaling)));
    }

    @Test
    void buildHpa_createsHpaForHpaStrategy() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.HPA, 1, 10,
                List.of(new ScalingMetric("cpu", "80", null)));
        HorizontalPodAutoscaler hpa = builder.buildHpa(spec(scaling));

        assertNotNull(hpa);
        assertEquals("fn-echo", hpa.getMetadata().getName());
        assertEquals(1, hpa.getSpec().getMinReplicas());
        assertEquals(10, hpa.getSpec().getMaxReplicas());
        assertEquals("Deployment", hpa.getSpec().getScaleTargetRef().getKind());
        assertEquals("fn-echo", hpa.getSpec().getScaleTargetRef().getName());
    }

    @Test
    void buildHpa_cpuMetricTranslation() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.HPA, 1, 10,
                List.of(new ScalingMetric("cpu", "80", null)));
        HorizontalPodAutoscaler hpa = builder.buildHpa(spec(scaling));

        List<MetricSpec> metrics = hpa.getSpec().getMetrics();
        assertEquals(1, metrics.size());
        assertEquals("Resource", metrics.get(0).getType());
        assertEquals("cpu", metrics.get(0).getResource().getName());
        assertEquals(80, metrics.get(0).getResource().getTarget().getAverageUtilization());
    }

    @Test
    void buildHpa_externalMetricTranslation() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.HPA, 1, 10,
                List.of(new ScalingMetric("queue_depth", "5", null)));
        HorizontalPodAutoscaler hpa = builder.buildHpa(spec(scaling));

        List<MetricSpec> metrics = hpa.getSpec().getMetrics();
        assertEquals(1, metrics.size());
        assertEquals("External", metrics.get(0).getType());
        assertEquals("nanofaas_queue_depth", metrics.get(0).getExternal().getMetric().getName());
        assertEquals("echo", metrics.get(0).getExternal().getMetric().getSelector().getMatchLabels().get("function"));
    }

    @Test
    void buildHpa_returnsNullWhenScalingConfigNull() {
        assertNull(builder.buildHpa(spec(null)));
    }

    @Test
    void deploymentName_format() {
        assertEquals("fn-myFunc", KubernetesDeploymentBuilder.deploymentName("myFunc"));
    }

    @Test
    void serviceName_format() {
        assertEquals("fn-myFunc", KubernetesDeploymentBuilder.serviceName("myFunc"));
    }
}
