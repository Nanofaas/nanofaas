package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentWakeUpProperties;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentWakeUpProtection;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentCoordinator;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatus;
import it.unimib.datai.nanofaas.controlplane.registry.DeploymentMetadata;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistry;
import it.unimib.datai.nanofaas.controlplane.registry.RegisteredFunction;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeploymentWakeUpGateTest {

    private final FunctionRegistry registry = mock(FunctionRegistry.class);
    private final ManagedDeploymentCoordinator coordinator = mock(ManagedDeploymentCoordinator.class);

    @Test
    void properties_defaultAndRejectNonPositiveDurations() {
        assertThat(new DeploymentWakeUpProperties().timeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(new DeploymentWakeUpProperties().pollInterval()).isEqualTo(Duration.ofMillis(250));
        assertThatThrownBy(() -> new DeploymentWakeUpProperties(Duration.ZERO, Duration.ofMillis(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeploymentWakeUpProperties(Duration.ofMillis(1), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ensureReady_scalesEligibleZeroReplicaDeploymentToOneAndWaitsForReadiness() {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        ManagedDeploymentTarget target = new ManagedDeploymentTarget("echo", "k8s");
        when(registry.getRegistered("echo")).thenReturn(Optional.of(deployment("echo", "k8s", ScalingStrategy.INTERNAL, 0)));
        when(coordinator.getReplicaStatus(target))
                .thenReturn(new ReplicaStatus(0, 0), new ReplicaStatus(1, 1));

        CompletableFuture<Void> ready = gate().ensureReady(task);

        ready.join();

        var order = inOrder(coordinator);
        order.verify(coordinator).getReplicaStatus(target);
        order.verify(coordinator).setReplicas(target, 1);
        order.verify(coordinator).getReplicaStatus(target);
        assertThat(ready).isCompletedWithValue(null);
    }

    @Test
    void ensureReady_publishesWakeUpProtectionBeforeScaling() {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        ManagedDeploymentTarget target = new ManagedDeploymentTarget("echo", "k8s");
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        when(registry.getRegistered("echo")).thenReturn(Optional.of(deployment("echo", "k8s", ScalingStrategy.INTERNAL, 0)));
        when(coordinator.getReplicaStatus(target)).thenReturn(new ReplicaStatus(0, 0), new ReplicaStatus(1, 1));

        gate(Duration.ofSeconds(1), Duration.ofMillis(1), Runnable::run, publisher).ensureReady(task).join();

        var order = inOrder(publisher, coordinator);
        order.verify(coordinator).getReplicaStatus(target);
        order.verify(publisher).publishEvent(argThat(event -> event instanceof DeploymentWakeUpProtection protection
                && protection.functionName().equals("echo")
                && protection.expiresAt().isAfter(Instant.now())));
        order.verify(coordinator).setReplicas(target, 1);
    }

    @Test
    void ensureReady_doesNotScaleAnAlreadyReadyEligibleDeployment() {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        ManagedDeploymentTarget target = new ManagedDeploymentTarget("echo", "k8s");
        when(registry.getRegistered("echo")).thenReturn(Optional.of(deployment("echo", "k8s", ScalingStrategy.INTERNAL, 0)));
        when(coordinator.getReplicaStatus(target)).thenReturn(new ReplicaStatus(1, 1));

        gate().ensureReady(task).join();

        verify(coordinator).getReplicaStatus(target);
        verify(coordinator, never()).setReplicas(target, 1);
    }

    @Test
    void ensureReady_removesCompletedWakeUpSoALaterScaleToZeroCanWakeAgain() {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        ManagedDeploymentTarget target = new ManagedDeploymentTarget("echo", "k8s");
        when(registry.getRegistered("echo")).thenReturn(Optional.of(deployment("echo", "k8s", ScalingStrategy.INTERNAL, 0)));
        when(coordinator.getReplicaStatus(target)).thenReturn(
                new ReplicaStatus(0, 0), new ReplicaStatus(1, 1),
                new ReplicaStatus(0, 0), new ReplicaStatus(1, 1));

        DeploymentWakeUpGate gate = gate();
        gate.ensureReady(task).join();
        gate.ensureReady(task).join();

        verify(coordinator, times(2)).setReplicas(target, 1);
    }

    @Test
    void ensureReady_coalescesConcurrentWakeUpsForTheSameFunction() throws Exception {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        ManagedDeploymentTarget target = new ManagedDeploymentTarget("echo", "container-local");
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch firstStatusEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstStatus = new CountDownLatch(1);
        AtomicInteger statusCalls = new AtomicInteger();
        when(registry.getRegistered("echo")).thenReturn(Optional.of(deployment("echo", "container-local", ScalingStrategy.INTERNAL, 0)));
        when(coordinator.getReplicaStatus(target)).thenAnswer(invocation -> {
            if (statusCalls.incrementAndGet() == 1) {
                firstStatusEntered.countDown();
                await(releaseFirstStatus);
            }
            return statusCalls.get() <= 2 ? new ReplicaStatus(0, 0) : new ReplicaStatus(1, 1);
        });

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            DeploymentWakeUpGate gate = gate(Duration.ofSeconds(1), Duration.ofMillis(1), executor);
            CompletableFuture<CompletableFuture<Void>> firstCaller = CompletableFuture.supplyAsync(() -> {
                await(start);
                return gate.ensureReady(task);
            });
            CompletableFuture<CompletableFuture<Void>> secondCaller = CompletableFuture.supplyAsync(() -> {
                await(start);
                return gate.ensureReady(task);
            });
            start.countDown();
            assertThat(firstStatusEntered.await(1, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<Void> first = firstCaller.get(1, TimeUnit.SECONDS);
            CompletableFuture<Void> second = secondCaller.get(1, TimeUnit.SECONDS);
            releaseFirstStatus.countDown();

            CompletableFuture.allOf(first, second).join();

            assertThat(first).isSameAs(second);
            verify(coordinator, times(1)).setReplicas(target, 1);
        }
    }

    @Test
    void ensureReady_timesOutDistinctlyWhenNoReplicaBecomesReady() {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        ManagedDeploymentTarget target = new ManagedDeploymentTarget("echo", "k8s");
        when(registry.getRegistered("echo")).thenReturn(Optional.of(deployment("echo", "k8s", ScalingStrategy.INTERNAL, 0)));
        when(coordinator.getReplicaStatus(target)).thenReturn(new ReplicaStatus(0, 0));

        assertThatThrownBy(() -> gate(Duration.ofMillis(100), Duration.ofMillis(5))
                .ensureReady(task).get(1, TimeUnit.SECONDS))
                .isInstanceOf(java.util.concurrent.ExecutionException.class)
                .hasRootCauseMessage("DEPLOYMENT_WAKE_UP_TIMEOUT");
    }

    @Test
    void ensureReady_timesOutWithinTheWakeUpBudgetWhenPollIntervalIsLonger() {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        ManagedDeploymentTarget target = new ManagedDeploymentTarget("echo", "k8s");
        when(registry.getRegistered("echo")).thenReturn(Optional.of(deployment("echo", "k8s", ScalingStrategy.INTERNAL, 0)));
        when(coordinator.getReplicaStatus(target)).thenReturn(new ReplicaStatus(0, 0));
        long started = System.nanoTime();

        assertThatThrownBy(() -> gate(Duration.ofMillis(50), Duration.ofSeconds(1))
                .ensureReady(task).get(250, TimeUnit.MILLISECONDS))
                .isInstanceOf(java.util.concurrent.ExecutionException.class)
                .hasRootCauseMessage("DEPLOYMENT_WAKE_UP_TIMEOUT");

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(250));
    }

    @Test
    void ensureReady_timesOutWhileTheFirstProviderStatusCallIsBlocked() throws Exception {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        ManagedDeploymentTarget target = new ManagedDeploymentTarget("echo", "k8s");
        CountDownLatch statusEntered = new CountDownLatch(1);
        CountDownLatch releaseStatus = new CountDownLatch(1);
        when(registry.getRegistered("echo")).thenReturn(Optional.of(deployment("echo", "k8s", ScalingStrategy.INTERNAL, 0)));
        when(coordinator.getReplicaStatus(target)).thenAnswer(invocation -> {
            statusEntered.countDown();
            await(releaseStatus);
            return new ReplicaStatus(0, 0);
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<Void> ready = gate(Duration.ofMillis(50), Duration.ofMillis(1), executor).ensureReady(task);
            assertThat(statusEntered.await(1, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> ready.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .hasRootCauseMessage("DEPLOYMENT_WAKE_UP_TIMEOUT");
        } finally {
            releaseStatus.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        }
        verify(coordinator, never()).setReplicas(target, 1);
    }

    @Test
    void ensureReady_failsWhenTheDeploymentTargetIsMissing() {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        when(registry.getRegistered("echo")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> gate().ensureReady(task).join())
                .isInstanceOf(CompletionException.class)
                .hasRootCauseMessage("DEPLOYMENT_WAKE_UP_TARGET_UNAVAILABLE");
        verifyNoInteractions(coordinator);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void ensureReady_failsWhenARegisteredDeploymentHasNoBackend(String backend) {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        when(registry.getRegistered("echo")).thenReturn(Optional.of(deployment("echo", backend, ScalingStrategy.INTERNAL, 0)));

        assertThatThrownBy(() -> gate().ensureReady(task).join())
                .isInstanceOf(CompletionException.class)
                .hasRootCauseMessage("DEPLOYMENT_WAKE_UP_TARGET_UNAVAILABLE");
        verifyNoInteractions(coordinator);
    }

    @Test
    void ensureReady_propagatesReplicaScaleFailure() {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        ManagedDeploymentTarget target = new ManagedDeploymentTarget("echo", "k8s");
        when(registry.getRegistered("echo")).thenReturn(Optional.of(deployment("echo", "k8s", ScalingStrategy.INTERNAL, 0)));
        when(coordinator.getReplicaStatus(target)).thenReturn(new ReplicaStatus(0, 0));
        doThrow(new IllegalStateException("provider unavailable"))
                .when(coordinator).setReplicas(target, 1);

        assertThatThrownBy(() -> gate().ensureReady(task).join())
                .isInstanceOf(CompletionException.class)
                .hasRootCauseMessage("provider unavailable");
    }

    @Test
    void ensureReady_propagatesReplicaStatusFailure() {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        ManagedDeploymentTarget target = new ManagedDeploymentTarget("echo", "k8s");
        when(registry.getRegistered("echo")).thenReturn(Optional.of(deployment("echo", "k8s", ScalingStrategy.INTERNAL, 0)));
        when(coordinator.getReplicaStatus(target)).thenThrow(new IllegalStateException("provider unavailable"));

        assertThatThrownBy(() -> gate().ensureReady(task).join())
                .isInstanceOf(CompletionException.class)
                .hasRootCauseMessage("provider unavailable");
    }

    @ParameterizedTest
    @MethodSource("ineligibleFunctions")
    void ensureReady_isNoOpForIneligibleFunctions(RegisteredFunction function) {
        when(registry.getRegistered(function.name())).thenReturn(Optional.of(function));

        gate().ensureReady(task(function.name(), function.spec().executionMode(), ScalingStrategy.INTERNAL, 0)).join();

        verifyNoInteractions(coordinator);
    }

    private static Stream<RegisteredFunction> ineligibleFunctions() {
        return Stream.of(
                registered("local", ExecutionMode.LOCAL, null, ScalingStrategy.INTERNAL, 0),
                registered("external", ExecutionMode.EXTERNAL, null, ScalingStrategy.INTERNAL, 0),
                registered("hpa", ExecutionMode.DEPLOYMENT, "k8s", ScalingStrategy.HPA, 0),
                registered("minimum-one", ExecutionMode.DEPLOYMENT, "k8s", ScalingStrategy.INTERNAL, 1)
        );
    }

    private DeploymentWakeUpGate gate() {
        return gate(Duration.ofSeconds(1));
    }

    private DeploymentWakeUpGate gate(Duration timeout) {
        return gate(timeout, Duration.ofMillis(1));
    }

    private DeploymentWakeUpGate gate(Duration timeout, Duration pollInterval) {
        return new DeploymentWakeUpGate(registry, coordinator, timeout, pollInterval);
    }

    private DeploymentWakeUpGate gate(Duration timeout, Duration pollInterval, ExecutorService executor) {
        return new DeploymentWakeUpGate(registry, coordinator, timeout, pollInterval, executor);
    }

    private DeploymentWakeUpGate gate(Duration timeout,
                                      Duration pollInterval,
                                      java.util.concurrent.Executor executor,
                                      ApplicationEventPublisher publisher) {
        return new DeploymentWakeUpGate(registry, coordinator, timeout, pollInterval, executor, publisher);
    }

    private static RegisteredFunction deployment(String name, String backend, ScalingStrategy strategy, int minReplicas) {
        return registered(name, ExecutionMode.DEPLOYMENT, backend, strategy, minReplicas);
    }

    private static RegisteredFunction registered(String name,
                                                 ExecutionMode mode,
                                                 String backend,
                                                 ScalingStrategy strategy,
                                                 int minReplicas) {
        FunctionSpec spec = functionSpec(name, mode, strategy, minReplicas);
        return new RegisteredFunction(spec, new DeploymentMetadata(mode, mode, backend, null));
    }

    private static InvocationTask task(String name, ExecutionMode mode, ScalingStrategy strategy, int minReplicas) {
        return new InvocationTask(
                "execution-" + name,
                name,
                functionSpec(name, mode, strategy, minReplicas),
                new InvocationRequest("payload", Map.of()),
                null,
                null,
                Instant.now(),
                1
        );
    }

    private static FunctionSpec functionSpec(String name, ExecutionMode mode, ScalingStrategy strategy, int minReplicas) {
        return new FunctionSpec(
                name,
                "image",
                null,
                Map.of(),
                null,
                1_000,
                1,
                10,
                0,
                null,
                mode,
                null,
                null,
                new ScalingConfig(strategy, minReplicas, 3, List.of())
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for concurrent wake-up caller");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
