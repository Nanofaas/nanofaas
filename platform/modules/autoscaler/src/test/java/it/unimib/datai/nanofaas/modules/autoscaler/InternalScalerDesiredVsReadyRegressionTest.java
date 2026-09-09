package it.unimib.datai.nanofaas.modules.autoscaler;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingMetric;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentWakeUpCoordinator;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaObservation;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatus;
import it.unimib.datai.nanofaas.controlplane.registry.DeploymentMetadata;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistry;
import it.unimib.datai.nanofaas.controlplane.registry.ManagedDeploymentCoordinator;
import it.unimib.datai.nanofaas.controlplane.registry.RegisteredFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import java.time.Instant;

/**
 * Regression coverage for the "High" priority optimization finding in
 * docs/control-plane-review-2026-09-05.md: "Distinguish desired and ready replicas in the
 * autoscaler" — {@code InternalScaler.evaluateAndScale} feeds only the ready count into
 * {@code ScalingDecisionCalculator} as "current replicas", then unconditionally overwrites the
 * deployment's desired replica count with the freshly computed recommendation instead of
 * reading desired and ready together from one {@code ReplicaStatus}. The review's literal
 * example: 10 desired, 2 ready (rollout still catching
 * up), ratio 2 -> the calculator recommends ceil(2*2)=4 and the scaler calls it a scale-up
 * (4 > 2 ready) and issues {@code setReplicas(target, 4)}, which actually *reduces* the real
 * outstanding target from 10 to 4 even though load pressure has not dropped. This finding was
 * not covered by the review's {@code Audit.java}/{@code ProxyAudit.java} harness and is
 * confirmed here directly against {@link InternalScaler}.
 */
@ExtendWith(MockitoExtension.class)
class InternalScalerDesiredVsReadyRegressionTest {

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
        scaler = new InternalScaler(registry, metricsReader, deploymentCoordinator, PROPS,
                coldStartTracker, new DeploymentWakeUpCoordinator());
    }

    private RegisteredFunction functionSpec(String name, ScalingConfig scaling) {
        FunctionSpec spec = new FunctionSpec(
                name, "image:latest",
                List.of(), Map.of(), null,
                30000, 4, 100, 3,
                "http://fn-" + name + ".default.svc:8080/invoke",
                ExecutionMode.DEPLOYMENT, RuntimeMode.HTTP, null, scaling);
        return new RegisteredFunction(
                spec, new DeploymentMetadata(ExecutionMode.DEPLOYMENT, ExecutionMode.DEPLOYMENT, "k8s", null));
    }

    /** Bypasses the real 30s scale-up cooldown, equivalent to letting real time pass. */
    private void clearScaleUpCooldown(String functionName) throws Exception {
        Field field = InternalScaler.class.getDeclaredField("cooldownTracker");
        field.setAccessible(true);
        ScalingCooldownTracker tracker = (ScalingCooldownTracker) field.get(scaler);
        tracker.clear(functionName);
    }

    @Test
    void ongoingRolloutWithStablePressure_shouldNeverReduceTheAlreadyCommandedTarget() throws Exception {
        ScalingConfig scaling = new ScalingConfig(ScalingStrategy.INTERNAL, 1, 20,
                List.of(new ScalingMetric("queue_depth", "5", null)));
        RegisteredFunction fn = functionSpec("echo", scaling);
        ManagedDeploymentTarget target = new ManagedDeploymentTarget(fn.name(), fn.deploymentMetadata().deploymentBackend());
        when(registry.listRegistered()).thenReturn(List.of(fn));
        // Steady load: queue_depth=10, target=5 -> ratio 2.0 throughout both rounds.
        when(metricsReader.readMetric("echo", scaling.metrics().get(0))).thenReturn(10.0);

        // Round 1: only 5 replicas ready so far -> recommended = ceil(2.0*5) = 10.
        // This issues the "real" desired target of 10.
        when(deploymentCoordinator.observeReplicaStatus(target)).thenReturn(observed(5, 5));
        scaler.scalingLoop();

        clearScaleUpCooldown("echo");

        // Round 2: rollout is still catching up, only 2 of the 10 requested replicas are
        // actually Ready yet. Load pressure (ratio) has NOT changed.
        when(deploymentCoordinator.observeReplicaStatus(target)).thenReturn(observed(10, 2));
        scaler.scalingLoop();

        ArgumentCaptor<Integer> replicaCounts = ArgumentCaptor.forClass(Integer.class);
        org.mockito.Mockito.verify(deploymentCoordinator, org.mockito.Mockito.atLeastOnce())
                .setReplicas(org.mockito.ArgumentMatchers.eq(target), replicaCounts.capture());

        int firstCommandedTarget = replicaCounts.getAllValues().get(0);
        int lastCommandedTarget = replicaCounts.getAllValues().get(replicaCounts.getAllValues().size() - 1);
        assertThat(firstCommandedTarget).isEqualTo(10);

        // BUG (reproduced on the pre-fix code; M2 closed it): round 2 recomputes from readyReplicas=2
        // (ceil(2.0*2)=4) and calls it a scale-up (4 > 2), overwriting the real target of 10
        // with 4 -- an unintended scale-DOWN mid-rollout despite unchanged load pressure.
        assertThat(lastCommandedTarget)
                .as("the autoscaler must not walk back an already-commanded desired replica "
                        + "count while the rollout is still catching up and pressure is unchanged")
                .isGreaterThanOrEqualTo(firstCommandedTarget);
    }
    /** A fresh observation carrying the replica counts the periodic path would read. */
    private static ReplicaObservation observed(int desiredReplicas, int readyReplicas) {
        return ReplicaObservation.fresh(new ReplicaStatus(desiredReplicas, readyReplicas), Instant.EPOCH);
    }
}
