package it.unimib.datai.nanofaas.modules.autoscaler;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingMetric;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentCoordinator;
import it.unimib.datai.nanofaas.controlplane.registry.DeploymentMetadata;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistry;
import it.unimib.datai.nanofaas.controlplane.registry.RegisteredFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InternalScalerBranchTest {

    @Mock
    private FunctionRegistry registry;

    @Mock
    private ScalingMetricsReader metricsReader;

    @Mock
    private ManagedDeploymentCoordinator deploymentCoordinator;

    private InternalScaler scaler;

    @BeforeEach
    void setUp() {
        scaler = new InternalScaler(
                registry,
                metricsReader,
                deploymentCoordinator,
                new ScalingProperties(5000L, 0, 10),
                new ColdStartTracker()
        );
        lenient().when(deploymentCoordinator.isManagedDeployment(any())).thenAnswer(invocation -> {
            RegisteredFunction function = invocation.getArgument(0);
            return function != null
                    && function.deploymentMetadata().effectiveExecutionMode() == ExecutionMode.DEPLOYMENT
                    && function.deploymentMetadata().deploymentBackend() != null
                    && !function.deploymentMetadata().deploymentBackend().isBlank();
        });
    }

    @Test
    void scalingLoop_registryFailure_isHandledWithoutThrowing() {
        when(registry.listRegistered()).thenThrow(new RuntimeException("registry down"));

        scaler.scalingLoop();
    }

    @Test
    void scalingLoop_functionFailure_doesNotBlockOtherFunctions() {
        RegisteredFunction bad = spec("bad", 1, 10, List.of(new ScalingMetric("queue_depth", "5", null)));
        RegisteredFunction good = spec("good", 1, 10, List.of(new ScalingMetric("queue_depth", "5", null)));

        when(registry.listRegistered()).thenReturn(List.of(bad, good));
        when(deploymentCoordinator.getReadyReplicas(bad)).thenReturn(1);
        when(deploymentCoordinator.getReadyReplicas(good)).thenReturn(1);
        when(metricsReader.readMetric(eq("bad"), any())).thenThrow(new RuntimeException("metric failure"));
        when(metricsReader.readMetric(eq("good"), any())).thenReturn(15.0);

        scaler.scalingLoop();

        verify(deploymentCoordinator).setReplicas(good, 3);
    }

    @Test
    void scalingLoop_scaleUpCooldown_preventsSecondScaleUp() {
        RegisteredFunction spec = spec("echo", 1, 10, List.of(new ScalingMetric("queue_depth", "5", null)));

        when(registry.listRegistered()).thenReturn(List.of(spec));
        when(deploymentCoordinator.getReadyReplicas(spec)).thenReturn(1);
        when(metricsReader.readMetric(eq("echo"), any())).thenReturn(15.0);

        scaler.scalingLoop();
        scaler.scalingLoop();

        verify(deploymentCoordinator, times(1)).setReplicas(spec, 3);
    }

    @Test
    void scalingLoop_scaleDownCooldown_preventsSecondScaleDown() {
        RegisteredFunction spec = spec("echo", 0, 10, List.of(new ScalingMetric("in_flight", "2", null)));

        when(registry.listRegistered()).thenReturn(List.of(spec));
        when(deploymentCoordinator.getReadyReplicas(spec)).thenReturn(3);
        when(metricsReader.readMetric(eq("echo"), any())).thenReturn(0.0);

        scaler.scalingLoop();
        scaler.scalingLoop();

        verify(deploymentCoordinator, times(1)).setReplicas(spec, 0);
    }

    @Test
    void scalingLoop_blankOrInvalidTarget_usesDefaultTarget() {
        RegisteredFunction blankTarget = spec("blank", 0, 10, List.of(new ScalingMetric("queue_depth", " ", null)));
        RegisteredFunction invalidTarget = spec("invalid", 0, 10, List.of(new ScalingMetric("queue_depth", "abc", null)));

        when(registry.listRegistered()).thenReturn(List.of(blankTarget, invalidTarget));
        when(deploymentCoordinator.getReadyReplicas(blankTarget)).thenReturn(1);
        when(deploymentCoordinator.getReadyReplicas(invalidTarget)).thenReturn(1);
        when(metricsReader.readMetric(eq("blank"), any())).thenReturn(10.0);   // 10/50 => 0.2 => no scale
        when(metricsReader.readMetric(eq("invalid"), any())).thenReturn(25.0); // 25/50 => 0.5 => no scale

        scaler.scalingLoop();

        verify(deploymentCoordinator, never()).setReplicas(any(), anyInt());
    }

    @Test
    void scalingDecisionCalculator_usesMaxMetricRatioAndClampsReplicas() {
        ScalingConfig scaling = new ScalingConfig(
                ScalingStrategy.INTERNAL,
                1,
                5,
                List.of(
                        new ScalingMetric("queue_depth", "5", null),
                        new ScalingMetric("cpu", "10", null)
                )
        );
        RegisteredFunction registered = spec("echo", 1, 5, scaling.metrics());
        FunctionSpec spec = registered.spec();
        ScalingDecisionCalculator calculator = new ScalingDecisionCalculator(metricsReader);

        when(metricsReader.readMetric("echo", scaling.metrics().get(0))).thenReturn(15.0);
        when(metricsReader.readMetric("echo", scaling.metrics().get(1))).thenReturn(40.0);

        ScalingDecision decision = calculator.calculate(spec, 2);

        assertThat(decision.currentReplicas()).isEqualTo(2);
        assertThat(decision.desiredReplicas()).isEqualTo(5);
        assertThat(decision.maxRatio()).isEqualTo(4.0);
        assertThat(decision.downscaleSignal()).isFalse();
    }

    @Test
    void cooldownTracker_blocksImmediateRepeatScaleUpUntilReset() {
        ScalingCooldownTracker tracker = new ScalingCooldownTracker();
        Instant now = Instant.parse("2026-03-06T10:00:00Z");

        assertThat(tracker.allowScaleUp("echo", now)).isTrue();
        tracker.recordScaleUp("echo", now);
        assertThat(tracker.allowScaleUp("echo", now.plusMillis(1_000))).isFalse();

        tracker.clear("echo");

        assertThat(tracker.allowScaleUp("echo", now.plusMillis(1_000))).isTrue();
    }

    private RegisteredFunction spec(String name, int minReplicas, int maxReplicas, List<ScalingMetric> metrics) {
        return new RegisteredFunction(new FunctionSpec(
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
        ), new DeploymentMetadata(ExecutionMode.DEPLOYMENT, ExecutionMode.DEPLOYMENT, "k8s", null));
    }
}
