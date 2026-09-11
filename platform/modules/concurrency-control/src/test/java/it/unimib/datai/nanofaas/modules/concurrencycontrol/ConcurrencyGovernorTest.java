package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.registry.ManagedDeploymentCoordinator;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaObservation;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatus;
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
    private final SimpleMeterRegistry concurrencyRegistry = new SimpleMeterRegistry();
    private final ConcurrencyControlMetrics concurrencyMetrics =
            new ConcurrencyControlMetrics(concurrencyRegistry);
    private Metrics metrics;
    private ConcurrencyControlCoordinator coordinator;

    @BeforeEach
    void setUp() {
        metrics = new Metrics(new SimpleMeterRegistry());
        coordinator = new ConcurrencyControlCoordinator(
                metricsSource,
                concurrencyMetrics,
                properties,
                new StaticPerPodConcurrencyController(),
                new AdaptivePerPodConcurrencyController()
        );
    }

    @Test
    void dividesTheLimitAcrossTheReadyReplicasOfAManagedFunction() {
        FunctionSpec function = spec("echo", 12, staticControl(2));
        when(registry.listRegistered()).thenReturn(List.of(managed(function)));
        when(deploymentCoordinator.observeReplicaStatus(any(ManagedDeploymentTarget.class)))
                .thenReturn(observed(3));

        governor(deploymentCoordinator, 10_000).governLoop();

        assertThat(metricsSource.effectiveConcurrency).containsEntry("echo", 6);
        assertMode("echo", ConcurrencyControlMode.STATIC_PER_POD);
    }

    @Test
    void skipsAFunctionWithoutAReplicaReadingRatherThanGoverningItAsZeroReplicas() {
        FunctionSpec unreadable = spec("unreadable", 12, staticControl(2));
        FunctionSpec healthy = spec("healthy", 12, staticControl(2));
        when(registry.listRegistered()).thenReturn(List.of(managed(unreadable), managed(healthy)));
        when(deploymentCoordinator.observeReplicaStatus(new ManagedDeploymentTarget("unreadable", "k8s")))
                .thenReturn(ReplicaObservation.unavailable(Instant.EPOCH, "provider down"));
        when(deploymentCoordinator.observeReplicaStatus(new ManagedDeploymentTarget("healthy", "k8s")))
                .thenReturn(observed(2));

        governor(deploymentCoordinator, 10_000).governLoop();

        // No adjustment at all for the function with no measurement, and no collapse to the
        // single-replica limit either; the other function is governed in the same pass.
        assertThat(metricsSource.effectiveConcurrency)
                .containsOnlyKeys("healthy")
                .containsEntry("healthy", 4);
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
        assertThat(target("echo")).isEqualTo(3);

        // ten slower invocations against the freshly learnt baseline -> back off
        recordInvocations(10, Duration.ofMillis(30));
        governor(null, 12_500).governLoop();

        assertThat(target("echo")).isEqualTo(2);
    }

    @Test
    void keepsGoingWhenOneFunctionFails() {
        FunctionSpec broken = spec("broken", 12, staticControl(2));
        FunctionSpec healthy = spec("healthy", 12, staticControl(2));
        when(registry.listRegistered()).thenReturn(List.of(managed(broken), managed(healthy)));
        when(deploymentCoordinator.observeReplicaStatus(new ManagedDeploymentTarget("broken", "k8s")))
                .thenThrow(new IllegalStateException("backend down"));
        when(deploymentCoordinator.observeReplicaStatus(new ManagedDeploymentTarget("healthy", "k8s")))
                .thenReturn(observed(2));

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
        assertMode("a", ConcurrencyControlMode.BUDGETED);
    }

    @Test
    void theOtherModesAreUntouchedByTheBudget() {
        FunctionSpec fixedMode = spec("legacy", 12, ConcurrencySpecs.staticControl(2));
        when(registry.listRegistered()).thenReturn(List.of(RegisteredFunction.nonManaged(fixedMode)));

        budgetGovernor(4).governLoop();

        // Static per-pod still computes replicas x target and ignores the budget entirely.
        assertThat(metricsSource.effectiveConcurrency).containsEntry("legacy", 2);
        assertMode("legacy", ConcurrencyControlMode.STATIC_PER_POD);
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
                concurrencyMetrics,
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
                concurrencyMetrics,
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

        assertMode("echo", ConcurrencyControlMode.SOJOURN);
        assertThat(metricsSource.effectiveConcurrency.get("echo")).isGreaterThan(withinPromise);
    }

    @Test
    void removalCannotBeOvertakenByAnAlreadySampledControlCycle() throws Exception {
        FunctionSpec function = spec("echo", 12, staticControl(2));
        when(registry.listRegistered()).thenReturn(List.of(RegisteredFunction.nonManaged(function)));
        metrics.registerFunction("echo");
        var sampled = new java.util.concurrent.CountDownLatch(1);
        var finishSample = new java.util.concurrent.CountDownLatch(1);
        var removalStarted = new java.util.concurrent.CountDownLatch(1);
        coordinator = org.mockito.Mockito.spy(coordinator);
        var governor = new ConcurrencyGovernor(registry, name -> {
            var snapshot = metrics.snapshot(name);
            sampled.countDown();
            try { finishSample.await(); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
            return snapshot;
        }, new ConcurrencyGovernor.ConcurrencyControllers(coordinator, new BudgetedConcurrencyController()),
                properties, null, metricsSource, metricsSource, concurrencyMetrics,
                InstantSource.fixed(Instant.ofEpochMilli(10_000)));
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var cycle = workers.submit(governor::governLoop);
            assertThat(sampled.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var removal = workers.submit(() -> {
                removalStarted.countDown();
                governor.removeFunctionState("echo");
            });
            try {
                assertThat(removalStarted.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> removal.get(100, java.util.concurrent.TimeUnit.MILLISECONDS));
            } finally { finishSample.countDown(); }
            cycle.get(1, java.util.concurrent.TimeUnit.SECONDS);
            removal.get(1, java.util.concurrent.TimeUnit.SECONDS);
        }
        var order = org.mockito.Mockito.inOrder(coordinator);
        order.verify(coordinator).apply(function, 1, 0, 0.0, 10_000);
        order.verify(coordinator).removeFunctionState("echo");
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
                concurrencyMetrics,
                InstantSource.fixed(Instant.ofEpochMilli(nowEpochMs))
        );
    }

    private void assertMode(String functionName, ConcurrencyControlMode mode) {
        assertThat(concurrencyRegistry.get("function_concurrency_controller_mode")
                .tags("function", functionName, "mode", mode.name()).gauge().value()).isEqualTo(1.0);
    }

    private int target(String functionName) {
        return (int) concurrencyRegistry.get("function_target_inflight_per_pod")
                .tag("function", functionName).gauge().value();
    }

    private static RegisteredFunction managed(FunctionSpec spec) {
        return new RegisteredFunction(
                spec,
                new DeploymentMetadata(ExecutionMode.DEPLOYMENT, ExecutionMode.DEPLOYMENT, "k8s", null)
        );
    }

    private static final class RecordingMetricsSource implements RecordingWorkloadMetricsSource {
        private final Map<String, Integer> effectiveConcurrency = new HashMap<>();
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
    }
    /** A fresh observation of a managed function with the given ready replica count. */
    private static ReplicaObservation observed(int readyReplicas) {
        return ReplicaObservation.fresh(new ReplicaStatus(readyReplicas, readyReplicas), Instant.EPOCH);
    }
}
