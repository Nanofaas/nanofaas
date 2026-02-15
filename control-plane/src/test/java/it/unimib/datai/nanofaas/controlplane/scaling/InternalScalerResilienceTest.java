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

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InternalScalerResilienceTest {

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
                new ScalingProperties(5000L, 1, 10),
                new ColdStartTracker()
        );
    }

    @Test
    void scalingLoop_continuesWhenOneFunctionHasInvalidMetricTarget() {
        FunctionSpec broken = spec(
                "broken",
                new ScalingConfig(
                        ScalingStrategy.INTERNAL,
                        1,
                        10,
                        List.of(new ScalingMetric("queue_depth", null, null))
                )
        );
        FunctionSpec healthy = spec(
                "healthy",
                new ScalingConfig(
                        ScalingStrategy.INTERNAL,
                        1,
                        10,
                        List.of(new ScalingMetric("queue_depth", "5", null))
                )
        );

        when(registry.list()).thenReturn(List.of(broken, healthy));
        when(resourceManager.getReadyReplicas("broken")).thenReturn(1);
        when(resourceManager.getReadyReplicas("healthy")).thenReturn(1);
        when(metricsReader.readMetric(eq("broken"), any())).thenReturn(10.0);
        when(metricsReader.readMetric(eq("healthy"), any())).thenReturn(15.0);

        scaler.scalingLoop();

        verify(resourceManager).setReplicas("healthy", 3);
    }

    private FunctionSpec spec(String name, ScalingConfig scalingConfig) {
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
                scalingConfig
        );
    }
}
