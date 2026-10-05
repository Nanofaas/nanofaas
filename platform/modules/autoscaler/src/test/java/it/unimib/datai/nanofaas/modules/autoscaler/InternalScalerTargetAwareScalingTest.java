package it.unimib.datai.nanofaas.modules.autoscaler;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingMetric;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;
import it.unimib.datai.nanofaas.controlplane.registry.ManagedDeploymentCoordinator;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentWakeUpCoordinator;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaObservation;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatus;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.time.Instant;

/**
 * Target-aware scaling behaviour of {@link InternalScaler}: the scaler reads desired and ready
 * from the same {@link ReplicaStatus}, only scales up against the already-requested target, and
 * only scales down on an explicit signal (or on lack of progress), never walking back a still
 * in-progress rollout. A controllable clock crosses cooldowns and the progress window without
 * sleeping (plan §2 test discipline).
 */
@ExtendWith(MockitoExtension.class)
class InternalScalerTargetAwareScalingTest {

    private static final long START_EPOCH_MS = 1_700_000_000_000L;

    @Mock
    private FunctionRegistry registry;

    @Mock
    private ScalingMetricsReader metricsReader;

    @Mock
    private ManagedDeploymentCoordinator deploymentCoordinator;

    private final ColdStartTracker coldStartTracker = new ColdStartTracker();
    private final WakeUpTestResources wakeUpResources = new WakeUpTestResources();
    private final DeploymentWakeUpCoordinator wakeUpCoordinator = wakeUpResources.coordinator();
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START_EPOCH_MS);
        lenient().when(deploymentCoordinator.generationOf(any())).thenAnswer(invocation ->
                wakeUpResources.generation(invocation.getArgument(0, RegisteredFunction.class).name()));
        lenient().when(deploymentCoordinator.setReplicas(any(FunctionGeneration.class), any(), anyInt())).thenReturn(true);
    }

    @AfterEach
    void closeWakeUpResources() {
        wakeUpResources.close();
    }

    private InternalScaler scaler() {
        return new InternalScaler(registry, metricsReader, deploymentCoordinator,
                new ScalingProperties(5000L, 1, 10), coldStartTracker, wakeUpCoordinator, null,
                clock.instantSource());
    }

    private static RegisteredFunction function(String name, ScalingConfig scaling) {
        FunctionSpec spec = new FunctionSpec(
                name, "image:latest",
                List.of(), Map.of(), null,
                30000, 4, 100, 3,
                "http://fn-" + name + ".default.svc:8080/invoke",
                ExecutionMode.DEPLOYMENT, RuntimeMode.HTTP, null, scaling);
        return new RegisteredFunction(
                spec, new DeploymentMetadata(ExecutionMode.DEPLOYMENT, ExecutionMode.DEPLOYMENT, "k8s", null));
    }

    private static ManagedDeploymentTarget target(RegisteredFunction fn) {
        return new ManagedDeploymentTarget(fn.name(), fn.deploymentMetadata().deploymentBackend());
    }

    @Test
    void midRolloutRecommendation_doesNotWalkBackTheAlreadyRequestedTarget() {
        // The review's literal example: 10 desired, 2 ready, ratio 2 -> recommendation 4.
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.INTERNAL, 1, 20,
                List.of(new ScalingMetric("queue_depth", "5", null)));
        RegisteredFunction fn = function("echo", scaling);
        ManagedDeploymentTarget target = target(fn);
        when(registry.listRegistered()).thenReturn(List.of(fn));
        when(metricsReader.readMetric("echo", scaling.metrics().get(0))).thenReturn(10.0);

        InternalScaler scaler = scaler();

        // Round 1: 5 ready -> recommended ceil(2*5)=10, scale up to 10.
        when(deploymentCoordinator.observeReplicaStatus(target)).thenReturn(observed(5, 5));
        scaler.scalingLoop();
        verify(deploymentCoordinator).setReplicas(any(FunctionGeneration.class), eq(target), eq(10));

        // Round 2 (cooldown elapsed): rollout at 2 ready, pressure unchanged -> recommendation 4.
        clock.advanceMillis(31_000);
        when(deploymentCoordinator.observeReplicaStatus(target)).thenReturn(observed(10, 2));
        scaler.scalingLoop();

        // No further command: the already-requested 10 is not walked back to 4.
        verify(deploymentCoordinator, times(1)).setReplicas(any(FunctionGeneration.class), any(), anyInt());
    }

    @Test
    void slowStartup_readyAdvancing_keepsTheHigherTargetAcrossProgressWindows() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.INTERNAL, 1, 20,
                List.of(new ScalingMetric("queue_depth", "5", null)));
        RegisteredFunction fn = function("echo", scaling);
        ManagedDeploymentTarget target = target(fn);
        when(registry.listRegistered()).thenReturn(List.of(fn));
        when(metricsReader.readMetric("echo", scaling.metrics().get(0))).thenReturn(10.0);

        InternalScaler scaler = scaler();

        // A rollout that is slow but *progressing* (ready 2 -> 3 -> 5) must never be
        // reconciled down, even across multiple progress windows.
        when(deploymentCoordinator.observeReplicaStatus(target)).thenReturn(observed(10, 2));
        scaler.scalingLoop();

        clock.advanceMillis(ScalingProgressTracker.PROGRESS_WINDOW_MS + 1);
        when(deploymentCoordinator.observeReplicaStatus(target)).thenReturn(observed(10, 3));
        scaler.scalingLoop();

        clock.advanceMillis(ScalingProgressTracker.PROGRESS_WINDOW_MS + 1);
        when(deploymentCoordinator.observeReplicaStatus(target)).thenReturn(observed(10, 5));
        scaler.scalingLoop();

        verify(deploymentCoordinator, never()).setReplicas(any(FunctionGeneration.class), any(), anyInt());
    }

    @Test
    void stuckReplicas_doNotBlockAGenuineLoadDrivenDownscale() {
        // 10 requested, but the rollout is stuck at 2 ready. Load drops to zero: the downscale
        // to 0 must fire immediately, not wait for the 8 never-ready replicas to catch up.
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.INTERNAL, 0, 10,
                List.of(new ScalingMetric("in_flight", "2", null)));
        RegisteredFunction fn = function("echo", scaling);
        ManagedDeploymentTarget target = target(fn);
        when(registry.listRegistered()).thenReturn(List.of(fn));
        when(deploymentCoordinator.observeReplicaStatus(target)).thenReturn(observed(10, 2));
        when(metricsReader.readMetric("echo", scaling.metrics().get(0))).thenReturn(0.0);

        scaler().scalingLoop();

        verify(deploymentCoordinator).setReplicas(any(FunctionGeneration.class), eq(target), eq(0));
    }

    @Test
    void stuckRollout_isReconciledDownAfterTheProgressWindow() {
        // 10 requested, stuck at 2 ready, steady load (ratio 1.0 -> recommendation 2). While the
        // window has not elapsed the target is held; once the rollout shows no progress for a
        // full window, the phantom target is reconciled down to the recommendation.
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.INTERNAL, 1, 10,
                List.of(new ScalingMetric("queue_depth", "5", null)));
        RegisteredFunction fn = function("echo", scaling);
        ManagedDeploymentTarget target = target(fn);
        when(registry.listRegistered()).thenReturn(List.of(fn));
        when(deploymentCoordinator.observeReplicaStatus(target)).thenReturn(observed(10, 2));
        when(metricsReader.readMetric("echo", scaling.metrics().get(0))).thenReturn(5.0);

        InternalScaler scaler = scaler();
        scaler.scalingLoop();
        verify(deploymentCoordinator, never()).setReplicas(any(FunctionGeneration.class), any(), anyInt());

        clock.advanceMillis(ScalingProgressTracker.PROGRESS_WINDOW_MS + 1);
        scaler.scalingLoop();

        verify(deploymentCoordinator).setReplicas(any(FunctionGeneration.class), eq(target), eq(2));
    }

    @Test
    void concurrentRemoval_doesNotThrowWhenSetReplicasReturnsFalse() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.INTERNAL, 1, 10,
                List.of(new ScalingMetric("queue_depth", "5", null)));
        RegisteredFunction fn = function("echo", scaling);
        ManagedDeploymentTarget target = target(fn);
        when(registry.listRegistered()).thenReturn(List.of(fn));
        when(deploymentCoordinator.observeReplicaStatus(target)).thenReturn(observed(1, 1));
        when(metricsReader.readMetric("echo", scaling.metrics().get(0))).thenReturn(15.0);
        // The function is removed between the scaler's lookup and the apply: setReplicas no-ops.
        when(deploymentCoordinator.setReplicas(any(FunctionGeneration.class), eq(target), eq(3))).thenReturn(false);

        assertThatCode(() -> scaler().scalingLoop()).doesNotThrowAnyException();

        verify(deploymentCoordinator).setReplicas(any(FunctionGeneration.class), eq(target), eq(3));
    }

    @Test
    void downscaleEvaluatedForAnOldGenerationCannotMutateItsReplacement() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.INTERNAL, 0, 10,
                List.of(new ScalingMetric("in_flight", "2", null)));
        RegisteredFunction fn = function("echo", scaling);
        ManagedDeploymentTarget target = target(fn);
        FunctionGeneration stale = wakeUpResources.generation("echo");
        wakeUpResources.generations().remove("echo");
        wakeUpResources.generations().register("echo", 1);
        when(registry.listRegistered()).thenReturn(List.of(fn));
        when(deploymentCoordinator.generationOf(fn)).thenReturn(stale);
        when(deploymentCoordinator.observeReplicaStatus(target)).thenReturn(observed(2, 2));
        when(metricsReader.readMetric("echo", scaling.metrics().get(0))).thenReturn(0.0);

        scaler().scalingLoop();

        verify(deploymentCoordinator, never()).setReplicas(any(FunctionGeneration.class), eq(target), eq(0));
    }

    @Test
    void removeFunctionState_resetsLackOfProgressForARecreatedFunction() {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.INTERNAL, 1, 10,
                List.of(new ScalingMetric("queue_depth", "5", null)));
        RegisteredFunction fn = function("echo", scaling);
        ManagedDeploymentTarget target = target(fn);
        when(registry.listRegistered()).thenReturn(List.of(fn));
        when(deploymentCoordinator.observeReplicaStatus(target)).thenReturn(observed(10, 2));
        when(metricsReader.readMetric("echo", scaling.metrics().get(0))).thenReturn(5.0);

        InternalScaler scaler = scaler();
        scaler.scalingLoop();

        // Removal clears the progress observation. A recreated function starts a fresh window,
        // so even a whole window later it is not immediately reconciled down.
        scaler.removeFunctionState("echo");
        clock.advanceMillis(ScalingProgressTracker.PROGRESS_WINDOW_MS + 1);
        scaler.scalingLoop();

        verify(deploymentCoordinator, never()).setReplicas(any(FunctionGeneration.class), any(), anyInt());
    }
    /** A fresh observation carrying the replica counts the periodic path would read. */
    private static ReplicaObservation observed(int desiredReplicas, int readyReplicas) {
        return ReplicaObservation.fresh(new ReplicaStatus(desiredReplicas, readyReplicas), Instant.EPOCH);
    }
}
