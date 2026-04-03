package it.unimib.datai.nanofaas.modules.autoscaler;

import it.unimib.datai.nanofaas.common.model.*;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentCoordinator;
import it.unimib.datai.nanofaas.controlplane.registry.DeploymentMetadata;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistry;
import it.unimib.datai.nanofaas.controlplane.registry.RegisteredFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InternalScalerTest {

    @Mock
    private FunctionRegistry registry;

    @Mock
    private ScalingMetricsReader metricsReader;

    @Mock
    private ManagedDeploymentCoordinator deploymentCoordinator;

    private InternalScaler scaler;

    private static final ScalingProperties PROPS = new ScalingProperties(5000L, 1, 10);

    private final ColdStartTracker coldStartTracker = new ColdStartTracker();

    @BeforeEach
    void setUp() {
        scaler = new InternalScaler(registry, metricsReader, deploymentCoordinator, PROPS, coldStartTracker);
        lenient().when(deploymentCoordinator.isManagedDeployment(any())).thenAnswer(invocation -> {
            RegisteredFunction function = invocation.getArgument(0);
            return function != null
                    && function.deploymentMetadata().effectiveExecutionMode() == ExecutionMode.DEPLOYMENT
                    && function.deploymentMetadata().deploymentBackend() != null
                    && !function.deploymentMetadata().deploymentBackend().isBlank();
        });
    }

    private RegisteredFunction functionSpec(String name, ExecutionMode mode, ScalingConfig scaling) {
        FunctionSpec spec = new FunctionSpec(
                name, "image:latest",
                List.of(), Map.of(), null,
                30000, 4, 100, 3,
                "http://fn-" + name + ".default.svc:8080/invoke",
                mode, RuntimeMode.HTTP, null, scaling
        );
        return new RegisteredFunction(
                spec,
                new DeploymentMetadata(mode, mode, mode == ExecutionMode.DEPLOYMENT ? "k8s" : null, null)
        );
    }

    @Test
    void scalingLoop_scalesUpWhenMetricExceedsTarget() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.INTERNAL, 1, 10,
                List.of(new ScalingMetric("queue_depth", "5", null)));
        RegisteredFunction spec = functionSpec("echo", ExecutionMode.DEPLOYMENT, scaling);

        when(registry.listRegistered()).thenReturn(List.of(spec));
        when(deploymentCoordinator.getReadyReplicas(spec)).thenReturn(1);
        // queue_depth = 15, target = 5, ratio = 3.0, desired = ceil(3.0 * 1) = 3
        when(metricsReader.readMetric("echo", scaling.metrics().get(0))).thenReturn(15.0);

        scaler.scalingLoop();

        verify(deploymentCoordinator).getReadyReplicas(spec);
        verify(deploymentCoordinator).setReplicas(spec, 3);
    }

    @Test
    void scalingLoop_ignoresFunctionsWithHpaStrategy() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.HPA, 1, 10,
                List.of(new ScalingMetric("cpu", "80", null)));
        RegisteredFunction spec = functionSpec("echo", ExecutionMode.DEPLOYMENT, scaling);

        when(registry.listRegistered()).thenReturn(List.of(spec));

        scaler.scalingLoop();

        verify(deploymentCoordinator, never()).setReplicas(any(), anyInt());
        verify(deploymentCoordinator, never()).getReadyReplicas(any());
    }

    @Test
    void scalingLoop_ignoresFunctionsWithNoneStrategy() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.NONE, 2, 10, List.of());
        RegisteredFunction spec = functionSpec("echo", ExecutionMode.DEPLOYMENT, scaling);

        when(registry.listRegistered()).thenReturn(List.of(spec));

        scaler.scalingLoop();

        verify(deploymentCoordinator, never()).setReplicas(any(), anyInt());
    }

    @Test
    void scalingLoop_ignoresNonDeploymentFunctions() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.INTERNAL, 1, 10,
                List.of(new ScalingMetric("queue_depth", "5", null)));
        RegisteredFunction spec = functionSpec("echo", ExecutionMode.POOL, scaling);

        when(registry.listRegistered()).thenReturn(List.of(spec));

        scaler.scalingLoop();

        verify(deploymentCoordinator, never()).setReplicas(any(), anyInt());
    }

    @Test
    void scalingLoop_doesNotScaleWhenMetricMatchesTarget() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.INTERNAL, 1, 10,
                List.of(new ScalingMetric("queue_depth", "5", null)));
        RegisteredFunction spec = functionSpec("echo", ExecutionMode.DEPLOYMENT, scaling);

        when(registry.listRegistered()).thenReturn(List.of(spec));
        when(deploymentCoordinator.getReadyReplicas(spec)).thenReturn(1);
        // queue_depth = 5, target = 5, ratio = 1.0, desired = ceil(1.0 * 1) = 1 (same as current)
        when(metricsReader.readMetric("echo", scaling.metrics().get(0))).thenReturn(5.0);

        scaler.scalingLoop();

        verify(deploymentCoordinator, never()).setReplicas(any(), anyInt());
    }

    @Test
    void scalingLoop_clampsToMaxReplicas() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.INTERNAL, 1, 5,
                List.of(new ScalingMetric("queue_depth", "1", null)));
        RegisteredFunction spec = functionSpec("echo", ExecutionMode.DEPLOYMENT, scaling);

        when(registry.listRegistered()).thenReturn(List.of(spec));
        when(deploymentCoordinator.getReadyReplicas(spec)).thenReturn(3);
        // queue_depth = 100, target = 1, ratio = 100, desired = ceil(100*3)=300 → clamped to 5
        when(metricsReader.readMetric("echo", scaling.metrics().get(0))).thenReturn(100.0);

        scaler.scalingLoop();

        verify(deploymentCoordinator).setReplicas(spec, 5);
    }

    @Test
    void scalingLoop_scalesFromZeroWhenLoadPresent() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.INTERNAL, 0, 5,
                List.of(new ScalingMetric("in_flight", "2", null)));
        RegisteredFunction spec = functionSpec("echo", ExecutionMode.DEPLOYMENT, scaling);

        when(registry.listRegistered()).thenReturn(List.of(spec));
        // 0 ready replicas, minReplicas=0 → currentReplicas should be treated as 1
        when(deploymentCoordinator.getReadyReplicas(spec)).thenReturn(0);
        // in_flight = 4, target = 2, ratio = 2.0, desired = ceil(2.0 * 1) = 2
        when(metricsReader.readMetric("echo", scaling.metrics().get(0))).thenReturn(4.0);

        scaler.scalingLoop();

        verify(deploymentCoordinator).setReplicas(spec, 2);
    }

    @Test
    void scalingLoop_scalesToZeroWhenNoLoad() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.INTERNAL, 0, 5,
                List.of(new ScalingMetric("in_flight", "2", null)));
        RegisteredFunction spec = functionSpec("echo", ExecutionMode.DEPLOYMENT, scaling);

        when(registry.listRegistered()).thenReturn(List.of(spec));
        when(deploymentCoordinator.getReadyReplicas(spec)).thenReturn(2);
        // in_flight = 0, target = 2, ratio = 0.0, desired = ceil(0 * 2) = 0, clamped to min=0
        when(metricsReader.readMetric("echo", scaling.metrics().get(0))).thenReturn(0.0);

        scaler.scalingLoop();

        verify(deploymentCoordinator).setReplicas(spec, 0);
    }

    @Test
    void scalingLoop_adaptiveMode_reducesEffectiveConcurrencyWhenMaxedAndHot() {
        ConcurrencyControlConfig control = new ConcurrencyControlConfig(
                ConcurrencyControlMode.ADAPTIVE_PER_POD,
                4,
                1,
                6,
                0L,
                0L,
                0.8,
                0.3
        );
        ScalingConfig scaling = new ScalingConfig(
                ScalingStrategy.INTERNAL,
                1,
                4,
                List.of(new ScalingMetric("queue_depth", "5", null)),
                control
        );
        RegisteredFunction spec = new RegisteredFunction(new FunctionSpec(
                "echo",
                "image:latest",
                List.of(),
                Map.of(),
                null,
                30000,
                20,
                100,
                3,
                "http://fn-echo.default.svc:8080/invoke",
                ExecutionMode.DEPLOYMENT,
                RuntimeMode.HTTP,
                null,
                scaling
        ), new DeploymentMetadata(ExecutionMode.DEPLOYMENT, ExecutionMode.DEPLOYMENT, "k8s", null));

        when(registry.listRegistered()).thenReturn(List.of(spec));
        when(deploymentCoordinator.getReadyReplicas(spec)).thenReturn(4);
        when(metricsReader.readMetric("echo", scaling.metrics().get(0))).thenReturn(30.0);

        scaler.scalingLoop();

        verify(metricsReader).setEffectiveConcurrency(eq("echo"), eq(12));
    }

    @Test
    void concurrencyControlCoordinator_updatesMetricsReaderWithoutChangingReplicaDecision() {
        ConcurrencyControlConfig control = new ConcurrencyControlConfig(
                ConcurrencyControlMode.STATIC_PER_POD,
                4,
                1,
                6,
                0L,
                0L,
                0.8,
                0.3
        );
        ScalingConfig scaling = new ScalingConfig(
                ScalingStrategy.INTERNAL,
                1,
                4,
                List.of(new ScalingMetric("queue_depth", "5", null)),
                control
        );
        RegisteredFunction spec = new RegisteredFunction(new FunctionSpec(
                "echo",
                "image:latest",
                List.of(),
                Map.of(),
                null,
                30000,
                20,
                100,
                3,
                "http://fn-echo.default.svc:8080/invoke",
                ExecutionMode.DEPLOYMENT,
                RuntimeMode.HTTP,
                null,
                scaling
        ), new DeploymentMetadata(ExecutionMode.DEPLOYMENT, ExecutionMode.DEPLOYMENT, "k8s", null));
        ConcurrencyControlCoordinator coordinator = new ConcurrencyControlCoordinator(
                metricsReader,
                PROPS,
                new StaticPerPodConcurrencyController(),
                new AdaptivePerPodConcurrencyController()
        );

        coordinator.apply(spec.spec(), scaling, 0.5, 3, false, 3);

        verify(metricsReader).setEffectiveConcurrency("echo", 12);
        verify(metricsReader).updateConcurrencyControllerState("echo", ConcurrencyControlMode.STATIC_PER_POD, 4);
        verifyNoInteractions(deploymentCoordinator);
    }

    @Test
    void doesNotStartWithoutDeploymentCoordinator() {
        InternalScaler noK8sScaler = new InternalScaler(registry, metricsReader, null, PROPS, coldStartTracker);
        noK8sScaler.start();
        assertFalse(noK8sScaler.isRunning());
    }

    @Test
    void removeFunctionState_clearsScaleCooldownsForRecreatedFunction() throws Exception {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.INTERNAL, 1, 10,
                List.of(new ScalingMetric("queue_depth", "5", null)));
        RegisteredFunction spec = functionSpec("echo", ExecutionMode.DEPLOYMENT, scaling);

        when(registry.listRegistered()).thenReturn(List.of(spec));
        when(deploymentCoordinator.getReadyReplicas(spec)).thenReturn(1);
        when(metricsReader.readMetric("echo", scaling.metrics().get(0))).thenReturn(15.0);

        scaler.scalingLoop();
        scaler.scalingLoop();
        verify(deploymentCoordinator, times(1)).setReplicas(spec, 3);

        Method removeFunctionState = InternalScaler.class.getDeclaredMethod("removeFunctionState", String.class);
        removeFunctionState.setAccessible(true);
        removeFunctionState.invoke(scaler, "echo");

        scaler.scalingLoop();

        verify(deploymentCoordinator, times(2)).setReplicas(spec, 3);
    }
}
