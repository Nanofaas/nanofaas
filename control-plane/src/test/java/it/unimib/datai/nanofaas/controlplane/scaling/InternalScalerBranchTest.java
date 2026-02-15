package it.unimib.datai.nanofaas.controlplane.scaling;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingMetric;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;
import it.unimib.datai.nanofaas.controlplane.dispatch.KubernetesResourceManager;
import it.unimib.datai.nanofaas.controlplane.metrics.ColdStartTracker;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InternalScalerBranchTest {

    @Mock
    private FunctionRegistry registry;

    @Mock
    private ScalingMetricsReader metricsReader;

    @Mock
    private KubernetesResourceManager resourceManager;

    private InternalScaler scaler;

    @BeforeEach
    void setUp() {
        scaler = new InternalScaler(
                registry,
                metricsReader,
                resourceManager,
                new ScalingProperties(5000L, 0, 10),
                new ColdStartTracker()
        );
    }

    @Test
    void scalingLoop_registryFailure_isHandledWithoutThrowing() {
        when(registry.list()).thenThrow(new RuntimeException("registry down"));

        scaler.scalingLoop();
    }

    @Test
    void scalingLoop_functionFailure_doesNotBlockOtherFunctions() {
        FunctionSpec bad = spec("bad", 1, 10, List.of(new ScalingMetric("queue_depth", "5", null)));
        FunctionSpec good = spec("good", 1, 10, List.of(new ScalingMetric("queue_depth", "5", null)));

        when(registry.list()).thenReturn(List.of(bad, good));
        when(resourceManager.getReadyReplicas("bad")).thenReturn(1);
        when(resourceManager.getReadyReplicas("good")).thenReturn(1);
        when(metricsReader.readMetric(eq("bad"), any())).thenThrow(new RuntimeException("metric failure"));
        when(metricsReader.readMetric(eq("good"), any())).thenReturn(15.0);

        scaler.scalingLoop();

        verify(resourceManager).setReplicas("good", 3);
    }

    @Test
    void scalingLoop_scaleUpCooldown_preventsSecondScaleUp() {
        FunctionSpec spec = spec("echo", 1, 10, List.of(new ScalingMetric("queue_depth", "5", null)));

        when(registry.list()).thenReturn(List.of(spec));
        when(resourceManager.getReadyReplicas("echo")).thenReturn(1);
        when(metricsReader.readMetric(eq("echo"), any())).thenReturn(15.0);

        scaler.scalingLoop();
        scaler.scalingLoop();

        verify(resourceManager, times(1)).setReplicas("echo", 3);
    }

    @Test
    void scalingLoop_scaleDownCooldown_preventsSecondScaleDown() {
        FunctionSpec spec = spec("echo", 0, 10, List.of(new ScalingMetric("in_flight", "2", null)));

        when(registry.list()).thenReturn(List.of(spec));
        when(resourceManager.getReadyReplicas("echo")).thenReturn(3);
        when(metricsReader.readMetric(eq("echo"), any())).thenReturn(0.0);

        scaler.scalingLoop();
        scaler.scalingLoop();

        verify(resourceManager, times(1)).setReplicas("echo", 0);
    }

    @Test
    void scalingLoop_blankOrInvalidTarget_usesDefaultTarget() {
        FunctionSpec blankTarget = spec("blank", 0, 10, List.of(new ScalingMetric("queue_depth", " ", null)));
        FunctionSpec invalidTarget = spec("invalid", 0, 10, List.of(new ScalingMetric("queue_depth", "abc", null)));

        when(registry.list()).thenReturn(List.of(blankTarget, invalidTarget));
        when(resourceManager.getReadyReplicas("blank")).thenReturn(1);
        when(resourceManager.getReadyReplicas("invalid")).thenReturn(1);
        when(metricsReader.readMetric(eq("blank"), any())).thenReturn(10.0);   // 10/50 => 0.2 => no scale
        when(metricsReader.readMetric(eq("invalid"), any())).thenReturn(25.0); // 25/50 => 0.5 => no scale

        scaler.scalingLoop();

        verify(resourceManager, never()).setReplicas(anyString(), anyInt());
    }

    private FunctionSpec spec(String name, int minReplicas, int maxReplicas, List<ScalingMetric> metrics) {
        return new FunctionSpec(
                name,
                "image:latest",
                List.of(),
                Map.of(),
                null,
                30000,
                4,
                100,
                3,
                "http://fn-" + name + ".default.svc:8080/invoke",
                ExecutionMode.DEPLOYMENT,
                RuntimeMode.HTTP,
                null,
                new ScalingConfig(ScalingStrategy.INTERNAL, minReplicas, maxReplicas, metrics)
        );
    }
}
