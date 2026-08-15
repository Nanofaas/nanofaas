package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentCoordinator;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import it.unimib.datai.nanofaas.controlplane.registry.DeploymentMetadata;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistry;
import it.unimib.datai.nanofaas.controlplane.registry.RegisteredFunction;
import it.unimib.datai.nanofaas.controlplane.service.Metrics;
import it.unimib.datai.nanofaas.controlplane.service.ScalingMetricsSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static it.unimib.datai.nanofaas.modules.concurrencycontrol.ConcurrencySpecs.adaptiveControl;
import static it.unimib.datai.nanofaas.modules.concurrencycontrol.ConcurrencySpecs.spec;
import static it.unimib.datai.nanofaas.modules.concurrencycontrol.ConcurrencySpecs.staticControl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConcurrencyGovernorTest {

    private final RecordingMetricsSource metricsSource = new RecordingMetricsSource();
    private final FunctionRegistry registry = mock(FunctionRegistry.class);
    private final ManagedDeploymentCoordinator deploymentCoordinator = mock(ManagedDeploymentCoordinator.class);
    private final ConcurrencyControlProperties properties = new ConcurrencyControlProperties(5000L, 2);
    private Metrics metrics;
    private ConcurrencyControlCoordinator coordinator;

    @BeforeEach
    void setUp() {
        metrics = new Metrics(new SimpleMeterRegistry());
        coordinator = new ConcurrencyControlCoordinator(
                metricsSource,
                properties,
                new StaticPerPodConcurrencyController(),
                new AdaptivePerPodConcurrencyController()
        );
    }

    @Test
    void dividesTheLimitAcrossTheReadyReplicasOfAManagedFunction() {
        FunctionSpec function = spec("echo", 12, staticControl(2));
        when(registry.listRegistered()).thenReturn(List.of(managed(function)));
        when(deploymentCoordinator.getReadyReplicas(any(ManagedDeploymentTarget.class))).thenReturn(3);

        governor(deploymentCoordinator, 10_000).governLoop();

        assertThat(metricsSource.effectiveConcurrency).containsEntry("echo", 6);
        assertThat(metricsSource.modes).containsEntry("echo", ConcurrencyControlMode.STATIC_PER_POD);
    }

    @Test
    void governsFunctionsThatHaveNoManagedDeployment() {
        FunctionSpec function = spec("echo", 12, staticControl(2));
        when(registry.listRegistered()).thenReturn(List.of(RegisteredFunction.nonManaged(function)));

        governor(deploymentCoordinator, 10_000).governLoop();

        assertThat(metricsSource.effectiveConcurrency).containsEntry("echo", 2);
    }

    @Test
    void runsWithoutAnyDeploymentBackend() {
        FunctionSpec function = spec("echo", 12, staticControl(3));
        when(registry.listRegistered()).thenReturn(List.of(managed(function)));

        governor(null, 10_000).governLoop();

        assertThat(metricsSource.effectiveConcurrency).containsEntry("echo", 3);
    }

    @Test
    void feedsTheObservedServiceTimeToTheAdaptiveController() {
        FunctionSpec function = spec("echo", 24, adaptiveControl(2));
        when(registry.listRegistered()).thenReturn(List.of(RegisteredFunction.nonManaged(function)));
        metrics.registerFunction("echo");
        ConcurrencyGovernor governor = governor(null, 10_000);

        record(10, Duration.ofMillis(10));
        governor.governLoop();
        assertThat(metricsSource.targets).containsEntry("echo", 3);

        // ten slower invocations against the freshly learnt baseline -> back off
        record(10, Duration.ofMillis(30));
        governor(null, 12_500).governLoop();

        assertThat(metricsSource.targets).containsEntry("echo", 2);
    }

    @Test
    void keepsGoingWhenOneFunctionFails() {
        FunctionSpec broken = spec("broken", 12, staticControl(2));
        FunctionSpec healthy = spec("healthy", 12, staticControl(2));
        when(registry.listRegistered()).thenReturn(List.of(managed(broken), managed(healthy)));
        when(deploymentCoordinator.getReadyReplicas(new ManagedDeploymentTarget("broken", "k8s")))
                .thenThrow(new IllegalStateException("backend down"));
        when(deploymentCoordinator.getReadyReplicas(new ManagedDeploymentTarget("healthy", "k8s")))
                .thenReturn(2);

        governor(deploymentCoordinator, 10_000).governLoop();

        assertThat(metricsSource.effectiveConcurrency).containsOnlyKeys("healthy");
        assertThat(metricsSource.effectiveConcurrency).containsEntry("healthy", 4);
    }

    private void record(int invocations, Duration each) {
        for (int i = 0; i < invocations; i++) {
            metrics.latency("echo").record(each);
        }
    }

    private ConcurrencyGovernor governor(ManagedDeploymentCoordinator coordinatorOrNull, long nowEpochMs) {
        return new ConcurrencyGovernor(
                registry,
                metrics,
                coordinator,
                properties,
                coordinatorOrNull,
                InstantSource.fixed(Instant.ofEpochMilli(nowEpochMs))
        );
    }

    private static RegisteredFunction managed(FunctionSpec spec) {
        return new RegisteredFunction(
                spec,
                new DeploymentMetadata(ExecutionMode.DEPLOYMENT, ExecutionMode.DEPLOYMENT, "k8s", null)
        );
    }

    private static final class RecordingMetricsSource implements ScalingMetricsSource {
        private final Map<String, Integer> effectiveConcurrency = new HashMap<>();
        private final Map<String, ConcurrencyControlMode> modes = new HashMap<>();
        private final Map<String, Integer> targets = new HashMap<>();

        @Override
        public int queueDepth(String functionName) {
            return 0;
        }

        @Override
        public int inFlight(String functionName) {
            return 0;
        }

        @Override
        public void setEffectiveConcurrency(String functionName, int value) {
            effectiveConcurrency.put(functionName, value);
        }

        @Override
        public void updateConcurrencyController(String functionName, ConcurrencyControlMode mode, int target) {
            modes.put(functionName, mode);
            targets.put(functionName, target);
        }
    }
}
