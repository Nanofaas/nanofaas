package it.unimib.datai.nanofaas.modules.autoscaler;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingMetric;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;
import it.unimib.datai.nanofaas.controlplane.registry.ManagedDeploymentCoordinator;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaObservation;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatus;
import it.unimib.datai.nanofaas.controlplane.registry.DeploymentMetadata;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistry;
import it.unimib.datai.nanofaas.controlplane.registry.RegisteredFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;
import java.time.Instant;

@ExtendWith(MockitoExtension.class)
class InternalScalerResilienceTest {

    @Mock
    private FunctionRegistry registry;

    @Mock
    private ScalingMetricsReader metricsReader;

    @Mock
    private ManagedDeploymentCoordinator deploymentCoordinator;

    private InternalScaler scaler;
    private final WakeUpTestResources wakeUpResources = new WakeUpTestResources();

    @BeforeEach
    void setUp() {
        scaler = new InternalScaler(
                registry,
                metricsReader,
                deploymentCoordinator,
                new ScalingProperties(5000L, 1, 10),
                new ColdStartTracker(),
                wakeUpResources.coordinator()
        );
        lenient().when(deploymentCoordinator.generationOf(any())).thenAnswer(invocation ->
                wakeUpResources.generation(invocation.getArgument(0, RegisteredFunction.class).name()));
        lenient().when(deploymentCoordinator.setReplicas(any(), any(), anyInt())).thenReturn(true);
    }

    @AfterEach
    void closeWakeUpResources() {
        wakeUpResources.close();
    }

    @Test
    void scalingLoop_continuesWhenOneFunctionHasInvalidMetricTarget() {
        RegisteredFunction broken = spec(
                "broken",
                new ScalingConfig(
                        ScalingStrategy.INTERNAL,
                        1,
                        10,
                        List.of(new ScalingMetric("queue_depth", null, null))
                )
        );
        RegisteredFunction healthy = spec(
                "healthy",
                new ScalingConfig(
                        ScalingStrategy.INTERNAL,
                        1,
                        10,
                        List.of(new ScalingMetric("queue_depth", "5", null))
                )
        );

        when(registry.listRegistered()).thenReturn(List.of(broken, healthy));
        when(deploymentCoordinator.observeReplicaStatus(target(broken))).thenReturn(observed(1, 1));
        when(deploymentCoordinator.observeReplicaStatus(target(healthy))).thenReturn(observed(1, 1));
        when(metricsReader.readMetric(eq("broken"), any())).thenReturn(10.0);
        when(metricsReader.readMetric(eq("healthy"), any())).thenReturn(15.0);

        scaler.scalingLoop();

        verify(deploymentCoordinator).setReplicas(any(), eq(target(healthy)), eq(3));
    }

    @Test
    void scalingLoop_skipsAFunctionWithoutAReplicaReadingInsteadOfTreatingItAsZeroReplicas() {
        RegisteredFunction unreadable = spec(
                "unreadable",
                new ScalingConfig(ScalingStrategy.INTERNAL, 1, 10,
                        List.of(new ScalingMetric("queue_depth", "5", null))));
        RegisteredFunction healthy = spec(
                "healthy",
                new ScalingConfig(ScalingStrategy.INTERNAL, 1, 10,
                        List.of(new ScalingMetric("queue_depth", "5", null))));

        when(registry.listRegistered()).thenReturn(List.of(unreadable, healthy));
        when(deploymentCoordinator.observeReplicaStatus(target(unreadable)))
                .thenReturn(ReplicaObservation.unavailable(Instant.EPOCH, "provider down"));
        when(deploymentCoordinator.observeReplicaStatus(target(healthy))).thenReturn(observed(1, 1));
        when(metricsReader.readMetric(eq("healthy"), any())).thenReturn(15.0);

        scaler.scalingLoop();

        // No decision at all for the unreadable one: not a scale to zero, not a scale to anything.
        verify(deploymentCoordinator, never()).setReplicas(any(), eq(target(unreadable)), anyInt());
        verify(metricsReader, never()).readMetric(eq("unreadable"), any());
        // ... and the loop still visits the function behind it in the same pass.
        verify(deploymentCoordinator).setReplicas(any(), eq(target(healthy)), eq(3));
    }

    @Test
    void scalingLoop_skipsAFunctionWhoseObservationThrowsInsteadOfBlockingTheLoop() {
        RegisteredFunction broken = spec(
                "broken",
                new ScalingConfig(ScalingStrategy.INTERNAL, 1, 10,
                        List.of(new ScalingMetric("queue_depth", "5", null))));
        RegisteredFunction healthy = spec(
                "healthy",
                new ScalingConfig(ScalingStrategy.INTERNAL, 1, 10,
                        List.of(new ScalingMetric("queue_depth", "5", null))));

        when(registry.listRegistered()).thenReturn(List.of(broken, healthy));
        when(deploymentCoordinator.observeReplicaStatus(target(broken)))
                .thenThrow(new IllegalStateException("backend down"));
        when(deploymentCoordinator.observeReplicaStatus(target(healthy))).thenReturn(observed(1, 1));
        when(metricsReader.readMetric(eq("healthy"), any())).thenReturn(15.0);

        scaler.scalingLoop();

        verify(deploymentCoordinator, never()).setReplicas(any(), eq(target(broken)), anyInt());
        verify(deploymentCoordinator).setReplicas(any(), eq(target(healthy)), eq(3));
    }

    private RegisteredFunction spec(String name, ScalingConfig scalingConfig) {
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
                scalingConfig
        ), new DeploymentMetadata(ExecutionMode.DEPLOYMENT, ExecutionMode.DEPLOYMENT, "k8s", null));
    }
    private static ManagedDeploymentTarget target(RegisteredFunction function) {
        return new ManagedDeploymentTarget(function.name(), function.deploymentMetadata().deploymentBackend());
    }
    /** A fresh observation carrying the replica counts the periodic path would read. */
    private static ReplicaObservation observed(int desiredReplicas, int readyReplicas) {
        return ReplicaObservation.fresh(new ReplicaStatus(desiredReplicas, readyReplicas), Instant.EPOCH);
    }
}
