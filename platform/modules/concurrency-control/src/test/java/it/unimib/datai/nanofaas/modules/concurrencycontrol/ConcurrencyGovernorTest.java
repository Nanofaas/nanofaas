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
import it.unimib.datai.nanofaas.controlplane.service.RecordingWorkloadMetricsSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.atomic.AtomicLong;
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
    private final ConcurrencyControlProperties properties =
            new ConcurrencyControlProperties(5000L, 2, 64);
    private Metrics metrics;
    private ConcurrencyControlCoordinator coordinator;

    @BeforeEach
    void setUp() {
        metrics = new Metrics(new SimpleMeterRegistry());
        coordinator = new ConcurrencyControlCoordinator(
                metricsSource,
                metricsSource,
                new ConcurrencyControlMetrics(new SimpleMeterRegistry(), metricsSource),
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

        recordInvocations(10, Duration.ofMillis(10));
        governor.governLoop();
        assertThat(metricsSource.targets).containsEntry("echo", 3);

        // ten slower invocations against the freshly learnt baseline -> back off
        recordInvocations(10, Duration.ofMillis(30));
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

        assertThat(metricsSource.effectiveConcurrency)
                .containsOnlyKeys("healthy")
                .containsEntry("healthy", 4);
    }

    @Test
    void budgetedFunctionsAreDecidedTogetherRatherThanOneAtATime() {
        // The whole point of the mode: a budget cannot be respected by a decision taken per
        // function, because no function knows what the others are asking for.
        FunctionSpec a = spec("a", 64, ConcurrencySpecs.budgetedControl(100, 1));
        FunctionSpec b = spec("b", 64, ConcurrencySpecs.budgetedControl(100, 1));
        when(registry.listRegistered()).thenReturn(List.of(
                RegisteredFunction.nonManaged(a), RegisteredFunction.nonManaged(b)));
        metrics.registerFunction("a");
        metrics.registerFunction("b");

        ConcurrencyGovernor governor = budgetGovernor(12);
        governor.governLoop();
        for (int i = 0; i < 10; i++) {
            metrics.latency("a").record(Duration.ofMillis(1));
            metrics.latency("b").record(Duration.ofMillis(1));
        }
        governor.governLoop();

        assertThat(metricsSource.effectiveConcurrency.values().stream()
                .mapToInt(Integer::intValue).sum()).isLessThanOrEqualTo(12);
        assertThat(metricsSource.modes).containsEntry("a", ConcurrencyControlMode.BUDGETED);
    }

    @Test
    void theOtherModesAreUntouchedByTheBudget() {
        FunctionSpec fixedMode = spec("legacy", 12, ConcurrencySpecs.staticControl(2));
        when(registry.listRegistered()).thenReturn(List.of(RegisteredFunction.nonManaged(fixedMode)));

        budgetGovernor(4).governLoop();

        // Static per-pod still computes replicas x target and ignores the budget entirely.
        assertThat(metricsSource.effectiveConcurrency).containsEntry("legacy", 2);
        assertThat(metricsSource.modes).containsEntry("legacy", ConcurrencyControlMode.STATIC_PER_POD);
    }

    private ConcurrencyGovernor budgetGovernor(int budget) {
        return new ConcurrencyGovernor(
                registry,
                metrics,
                new ConcurrencyGovernor.ConcurrencyControllers(
                        coordinator, new BudgetedConcurrencyController()),
                new ConcurrencyControlProperties(5000L, 2, budget),
                null,
                metricsSource,
                metricsSource,
                new ConcurrencyControlMetrics(new SimpleMeterRegistry(), metricsSource),
                InstantSource.fixed(Instant.ofEpochMilli(10_000))
        );
    }

    @Test
    void feedsTheEndToEndTimerToTheSojournController() {
        // The wiring, not the law: SOJOURN is the one mode that reads a different timer, and a
        // governor that handed it the service timer would look correct in every unit test of the
        // controller while measuring the wrong quantity in production.
        FunctionSpec function = spec("echo", 24, ConcurrencySpecs.sojournControl(100, 1, 16));
        when(registry.listRegistered()).thenReturn(List.of(RegisteredFunction.nonManaged(function)));
        metrics.registerFunction("echo");
        // A backlog to drain, because that is the term the end-to-end reading scales: with an empty
        // queue it multiplies nothing and the wiring would be unobservable either way.
        metricsSource.queueDepth = 80;
        // Service time well inside anything, end-to-end far outside it: only a controller reading
        // the end-to-end timer has any reason to hurry. The service time is 50ms because the drain
        // term is queue x service / horizon — with a fast function it is a fraction of one slot and
        // no change to the horizon could move a whole-number limit.
        for (int i = 0; i < 100; i++) {
            metrics.latency("echo").record(Duration.ofMillis(50));
            metrics.e2eLatency("echo").record(Duration.ofMillis(60));
        }
        // An advancing clock, because the controller works from intervals: handed the same instant
        // twice it has been shown nothing, and the limit would sit still for a reason that has
        // nothing to do with the signal under test.
        AtomicLong clock = new AtomicLong(10_000);
        ConcurrencyGovernor governor = new ConcurrencyGovernor(
                registry,
                metrics,
                new ConcurrencyGovernor.ConcurrencyControllers(
                        coordinator, new BudgetedConcurrencyController()),
                properties,
                null,
                metricsSource,
                metricsSource,
                new ConcurrencyControlMetrics(new SimpleMeterRegistry(), metricsSource),
                () -> Instant.ofEpochMilli(clock.getAndAdd(5_000))
        );

        governor.governLoop();
        for (int i = 0; i < 100; i++) {
            metrics.latency("echo").record(Duration.ofMillis(50));
            metrics.e2eLatency("echo").record(Duration.ofMillis(60));
        }
        governor.governLoop();
        int withinPromise = metricsSource.effectiveConcurrency.get("echo");

        // Same service time, same backlog, same throughput — only the end-to-end reading moves,
        // from just outside the promise to far outside it. A governor handing over the service
        // timer would produce the same limit twice.
        for (int i = 0; i < 100; i++) {
            metrics.latency("echo").record(Duration.ofMillis(50));
            metrics.e2eLatency("echo").record(Duration.ofMillis(2_000));
        }
        governor.governLoop();

        assertThat(metricsSource.modes).containsEntry("echo", ConcurrencyControlMode.SOJOURN);
        assertThat(metricsSource.effectiveConcurrency.get("echo")).isGreaterThan(withinPromise);
    }

    private void recordInvocations(int invocations, Duration each) {
        for (int i = 0; i < invocations; i++) {
            metrics.latency("echo").record(each);
        }
    }

    private ConcurrencyGovernor governor(ManagedDeploymentCoordinator coordinatorOrNull, long nowEpochMs) {
        return new ConcurrencyGovernor(
                registry,
                metrics,
                new ConcurrencyGovernor.ConcurrencyControllers(
                        coordinator, new BudgetedConcurrencyController()),
                properties,
                coordinatorOrNull,
                metricsSource,
                metricsSource,
                new ConcurrencyControlMetrics(new SimpleMeterRegistry(), metricsSource),
                InstantSource.fixed(Instant.ofEpochMilli(nowEpochMs))
        );
    }

    private static RegisteredFunction managed(FunctionSpec spec) {
        return new RegisteredFunction(
                spec,
                new DeploymentMetadata(ExecutionMode.DEPLOYMENT, ExecutionMode.DEPLOYMENT, "k8s", null)
        );
    }

    private static final class RecordingMetricsSource implements RecordingWorkloadMetricsSource {
        private final Map<String, Integer> effectiveConcurrency = new HashMap<>();
        private final Map<String, ConcurrencyControlMode> modes = new HashMap<>();
        private final Map<String, Integer> targets = new HashMap<>();
        private int queueDepth;

        @Override
        public int queueDepth(String functionName) {
            return queueDepth;
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
