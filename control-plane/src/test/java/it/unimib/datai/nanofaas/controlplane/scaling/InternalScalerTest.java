package it.unimib.datai.nanofaas.controlplane.scaling;

import it.unimib.datai.nanofaas.common.model.*;
import it.unimib.datai.nanofaas.controlplane.dispatch.KubernetesResourceManager;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InternalScalerTest {

    @Mock
    private FunctionRegistry registry;

    @Mock
    private ScalingMetricsReader metricsReader;

    @Mock
    private KubernetesResourceManager resourceManager;

    private InternalScaler scaler;

    private static final ScalingProperties PROPS = new ScalingProperties(5000L, 1, 10);

    @BeforeEach
    void setUp() {
        scaler = new InternalScaler(registry, metricsReader, resourceManager, PROPS);
    }

    private FunctionSpec functionSpec(String name, ExecutionMode mode, ScalingConfig scaling) {
        return new FunctionSpec(
                name, "image:latest",
                List.of(), Map.of(), null,
                30000, 4, 100, 3,
                "http://fn-" + name + ".default.svc:8080/invoke",
                mode, RuntimeMode.HTTP, null, scaling
        );
    }

    @Test
    void scalingLoop_scalesUpWhenMetricExceedsTarget() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.INTERNAL, 1, 10,
                List.of(new ScalingMetric("queue_depth", "5", null)));
        FunctionSpec spec = functionSpec("echo", ExecutionMode.DEPLOYMENT, scaling);

        when(registry.list()).thenReturn(List.of(spec));
        when(resourceManager.getReadyReplicas("echo")).thenReturn(1);
        // queue_depth = 15, target = 5, ratio = 3.0, desired = ceil(3.0 * 1) = 3
        when(metricsReader.readMetric("echo", scaling.metrics().get(0))).thenReturn(15.0);

        scaler.scalingLoop();

        verify(resourceManager).getReadyReplicas("echo");
        verify(resourceManager).setReplicas("echo", 3);
    }

    @Test
    void scalingLoop_ignoresFunctionsWithHpaStrategy() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.HPA, 1, 10,
                List.of(new ScalingMetric("cpu", "80", null)));
        FunctionSpec spec = functionSpec("echo", ExecutionMode.DEPLOYMENT, scaling);

        when(registry.list()).thenReturn(List.of(spec));

        scaler.scalingLoop();

        verify(resourceManager, never()).setReplicas(anyString(), anyInt());
        verify(resourceManager, never()).getReadyReplicas(anyString());
    }

    @Test
    void scalingLoop_ignoresFunctionsWithNoneStrategy() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.NONE, 2, 10, List.of());
        FunctionSpec spec = functionSpec("echo", ExecutionMode.DEPLOYMENT, scaling);

        when(registry.list()).thenReturn(List.of(spec));

        scaler.scalingLoop();

        verify(resourceManager, never()).setReplicas(anyString(), anyInt());
    }

    @Test
    void scalingLoop_ignoresNonDeploymentFunctions() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.INTERNAL, 1, 10,
                List.of(new ScalingMetric("queue_depth", "5", null)));
        FunctionSpec spec = functionSpec("echo", ExecutionMode.REMOTE, scaling);

        when(registry.list()).thenReturn(List.of(spec));

        scaler.scalingLoop();

        verify(resourceManager, never()).setReplicas(anyString(), anyInt());
    }

    @Test
    void scalingLoop_doesNotScaleWhenMetricMatchesTarget() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.INTERNAL, 1, 10,
                List.of(new ScalingMetric("queue_depth", "5", null)));
        FunctionSpec spec = functionSpec("echo", ExecutionMode.DEPLOYMENT, scaling);

        when(registry.list()).thenReturn(List.of(spec));
        when(resourceManager.getReadyReplicas("echo")).thenReturn(1);
        // queue_depth = 5, target = 5, ratio = 1.0, desired = ceil(1.0 * 1) = 1 (same as current)
        when(metricsReader.readMetric("echo", scaling.metrics().get(0))).thenReturn(5.0);

        scaler.scalingLoop();

        verify(resourceManager, never()).setReplicas(anyString(), anyInt());
    }

    @Test
    void scalingLoop_clampsToMaxReplicas() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.INTERNAL, 1, 5,
                List.of(new ScalingMetric("queue_depth", "1", null)));
        FunctionSpec spec = functionSpec("echo", ExecutionMode.DEPLOYMENT, scaling);

        when(registry.list()).thenReturn(List.of(spec));
        when(resourceManager.getReadyReplicas("echo")).thenReturn(3);
        // queue_depth = 100, target = 1, ratio = 100, desired = ceil(100*3)=300 → clamped to 5
        when(metricsReader.readMetric("echo", scaling.metrics().get(0))).thenReturn(100.0);

        scaler.scalingLoop();

        verify(resourceManager).setReplicas("echo", 5);
    }

    @Test
    void doesNotStartWithoutResourceManager() {
        InternalScaler noK8sScaler = new InternalScaler(registry, metricsReader, null, PROPS);
        noK8sScaler.start();
        assertFalse(noK8sScaler.isRunning());
    }
}
