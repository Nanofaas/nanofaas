package it.unimib.datai.nanofaas.modules.autoscaler;

import it.unimib.datai.nanofaas.common.model.*;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentCoordinator;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentWakeUpCoordinator;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatus;
import it.unimib.datai.nanofaas.controlplane.registry.DeploymentMetadata;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistry;
import it.unimib.datai.nanofaas.controlplane.registry.RegisteredFunction;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.service.DeploymentWakeUpGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.assertj.core.api.Assertions.assertThat;
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
    private DeploymentWakeUpCoordinator wakeUpCoordinator;

    private static final ScalingProperties PROPS = new ScalingProperties(5000L, 1, 10);

    private final ColdStartTracker coldStartTracker = new ColdStartTracker();

    @BeforeEach
    void setUp() {
        wakeUpCoordinator = new DeploymentWakeUpCoordinator();
        scaler = new InternalScaler(registry, metricsReader, deploymentCoordinator, PROPS, coldStartTracker, wakeUpCoordinator);
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

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for scale-down");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    @Test
    void scalingLoop_scalesUpWhenMetricExceedsTarget() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.INTERNAL, 1, 10,
                List.of(new ScalingMetric("queue_depth", "5", null)));
        RegisteredFunction spec = functionSpec("echo", ExecutionMode.DEPLOYMENT, scaling);

        when(registry.listRegistered()).thenReturn(List.of(spec));
        when(deploymentCoordinator.getReadyReplicas(target(spec))).thenReturn(1);
        // queue_depth = 15, target = 5, ratio = 3.0, desired = ceil(3.0 * 1) = 3
        when(metricsReader.readMetric("echo", scaling.metrics().get(0))).thenReturn(15.0);

        scaler.scalingLoop();

        verify(deploymentCoordinator).getReadyReplicas(target(spec));
        verify(deploymentCoordinator).setReplicas(target(spec), 3);
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
    void scalingLoop_ignoresFunctionsWithoutScalingConfig() {
        RegisteredFunction spec = functionSpec("echo", ExecutionMode.DEPLOYMENT, null);

        when(registry.listRegistered()).thenReturn(List.of(spec));

        scaler.scalingLoop();

        verify(deploymentCoordinator, never()).setReplicas(any(), anyInt());
        verify(deploymentCoordinator, never()).getReadyReplicas(any());
    }

    @Test
    void scalingLoop_ignoresNonDeploymentFunctions() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.INTERNAL, 1, 10,
                List.of(new ScalingMetric("queue_depth", "5", null)));
        RegisteredFunction spec = functionSpec("echo", ExecutionMode.EXTERNAL, scaling);

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
        when(deploymentCoordinator.getReadyReplicas(target(spec))).thenReturn(1);
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
        when(deploymentCoordinator.getReadyReplicas(target(spec))).thenReturn(3);
        // queue_depth = 100, target = 1, ratio = 100, desired = ceil(100*3)=300 → clamped to 5
        when(metricsReader.readMetric("echo", scaling.metrics().get(0))).thenReturn(100.0);

        scaler.scalingLoop();

        verify(deploymentCoordinator).setReplicas(target(spec), 5);
    }

    @Test
    void scalingLoop_scalesFromZeroWhenLoadPresent() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.INTERNAL, 0, 5,
                List.of(new ScalingMetric("in_flight", "2", null)));
        RegisteredFunction spec = functionSpec("echo", ExecutionMode.DEPLOYMENT, scaling);

        when(registry.listRegistered()).thenReturn(List.of(spec));
        // 0 ready replicas, minReplicas=0 → currentReplicas should be treated as 1
        when(deploymentCoordinator.getReadyReplicas(target(spec))).thenReturn(0);
        // in_flight = 4, target = 2, ratio = 2.0, desired = ceil(2.0 * 1) = 2
        when(metricsReader.readMetric("echo", scaling.metrics().get(0))).thenReturn(4.0);

        scaler.scalingLoop();

        verify(deploymentCoordinator).setReplicas(target(spec), 2);
    }

    @Test
    void scalingLoop_scalesToZeroWhenNoLoad() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.INTERNAL, 0, 5,
                List.of(new ScalingMetric("in_flight", "2", null)));
        RegisteredFunction spec = functionSpec("echo", ExecutionMode.DEPLOYMENT, scaling);

        when(registry.listRegistered()).thenReturn(List.of(spec));
        when(deploymentCoordinator.getReadyReplicas(target(spec))).thenReturn(2);
        // in_flight = 0, target = 2, ratio = 0.0, desired = ceil(0 * 2) = 0, clamped to min=0
        when(metricsReader.readMetric("echo", scaling.metrics().get(0))).thenReturn(0.0);

        scaler.scalingLoop();

        verify(deploymentCoordinator).setReplicas(target(spec), 0);
    }

    @Test
    void scalingLoop_doesNotScaleDownWhileWakeUpProtectionIsActive() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.INTERNAL, 0, 5,
                List.of(new ScalingMetric("in_flight", "2", null)));
        RegisteredFunction spec = functionSpec("echo", ExecutionMode.DEPLOYMENT, scaling);
        when(registry.listRegistered()).thenReturn(List.of(spec));
        when(deploymentCoordinator.getReadyReplicas(target(spec))).thenReturn(0);
        when(metricsReader.readMetric("echo", scaling.metrics().get(0))).thenReturn(0.0);
        wakeUpCoordinator.protectAndScaleUp(target(spec), System.nanoTime() + TimeUnit.SECONDS.toNanos(1), () -> { });

        scaler.scalingLoop();

        verify(deploymentCoordinator, never()).setReplicas(target(spec), 0);
    }

    @Test
    void scalingLoop_scalesDownAfterWakeUpProtectionExpires() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.INTERNAL, 0, 5,
                List.of(new ScalingMetric("in_flight", "2", null)));
        RegisteredFunction spec = functionSpec("echo", ExecutionMode.DEPLOYMENT, scaling);
        when(registry.listRegistered()).thenReturn(List.of(spec));
        when(deploymentCoordinator.getReadyReplicas(target(spec))).thenReturn(0);
        when(metricsReader.readMetric("echo", scaling.metrics().get(0))).thenReturn(0.0);
        scaler.scalingLoop();

        verify(deploymentCoordinator).setReplicas(target(spec), 0);
    }

    @Test
    void scalingLoop_cannotScaleDownAfterWakeUpScalesToOne() throws Exception {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.INTERNAL, 0, 5,
                List.of(new ScalingMetric("in_flight", "2", null)));
        RegisteredFunction function = functionSpec("echo", ExecutionMode.DEPLOYMENT, scaling);
        ManagedDeploymentTarget target = target(function);
        InvocationTask task = new InvocationTask("execution", "echo", function.spec(), null, null, null, Instant.now(), 1);
        CountDownLatch zeroEntered = new CountDownLatch(1);
        CountDownLatch releaseZero = new CountDownLatch(1);

        when(registry.listRegistered()).thenReturn(List.of(function));
        when(registry.getRegistered("echo")).thenReturn(Optional.of(function));
        when(deploymentCoordinator.getReadyReplicas(target)).thenReturn(2);
        when(metricsReader.readMetric("echo", scaling.metrics().get(0))).thenReturn(0.0);
        when(deploymentCoordinator.getReplicaStatus(target)).thenReturn(new ReplicaStatus(0, 0), new ReplicaStatus(1, 1));
        doAnswer(invocation -> {
            if ((int) invocation.getArgument(1) == 0) {
                zeroEntered.countDown();
                await(releaseZero);
            }
            return null;
        }).when(deploymentCoordinator).setReplicas(eq(target), anyInt());

        DeploymentWakeUpGate gate = new DeploymentWakeUpGate(
                registry, deploymentCoordinator, Duration.ofSeconds(1), Duration.ofMillis(1), Runnable::run, wakeUpCoordinator);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var scale = executor.submit(scaler::scalingLoop);
            assertThat(zeroEntered.await(1, TimeUnit.SECONDS)).isTrue();
            var wake = executor.submit(() -> gate.ensureReady(task).join());
            releaseZero.countDown();
            scale.get(1, TimeUnit.SECONDS);
            wake.get(1, TimeUnit.SECONDS);
        }

        var order = inOrder(deploymentCoordinator);
        order.verify(deploymentCoordinator).setReplicas(target, 0);
        order.verify(deploymentCoordinator).setReplicas(target, 1);
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
        when(deploymentCoordinator.getReadyReplicas(target(spec))).thenReturn(1);
        when(metricsReader.readMetric("echo", scaling.metrics().get(0))).thenReturn(15.0);

        scaler.scalingLoop();
        scaler.scalingLoop();
        verify(deploymentCoordinator, times(1)).setReplicas(target(spec), 3);

        Method removeFunctionState = InternalScaler.class.getDeclaredMethod("removeFunctionState", String.class);
        removeFunctionState.setAccessible(true);
        removeFunctionState.invoke(scaler, "echo");

        scaler.scalingLoop();

        verify(deploymentCoordinator, times(2)).setReplicas(target(spec), 3);
    }
    private static ManagedDeploymentTarget target(RegisteredFunction function) {
        return new ManagedDeploymentTarget(function.name(), function.deploymentMetadata().deploymentBackend());
    }
}
