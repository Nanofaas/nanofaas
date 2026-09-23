package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentWakeUpProperties;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentWakeUpCoordinator;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProperties;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentProviderResolver;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.registry.ManagedDeploymentCoordinator;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaObservation;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatus;
import it.unimib.datai.nanofaas.controlplane.registry.DeploymentMetadata;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistry;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionOperationLocks;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistrationListener;
import it.unimib.datai.nanofaas.controlplane.registry.RegisteredFunction;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;

class DeploymentWakeUpGateTest {

    private final FunctionRegistry registry = mock(FunctionRegistry.class);
    private final ManagedDeploymentCoordinator coordinator = mock(ManagedDeploymentCoordinator.class);
    private final List<ScheduledThreadPoolExecutor> schedulers = new ArrayList<>();

    @BeforeEach
    void captureFirstGenerationForIsolatedFixtures() {
        when(coordinator.generationOf(any(RegisteredFunction.class)))
                .thenAnswer(invocation -> new FunctionGeneration(
                        invocation.getArgument(0, RegisteredFunction.class).name(), 1));
    }

    @Test
    @SuppressWarnings({"ReferenceEquality", "FutureReturnValueIgnored"})
    void registryObjectAndGenerationAreCapturedAtomicallyBeforeWakeUpPublication() throws Exception {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        ManagedDeploymentTarget target = new ManagedDeploymentTarget("echo", "k8s");
        RegisteredFunction oldRegistration = deployment("echo", "k8s", ScalingStrategy.INTERNAL, 0);
        RegisteredFunction replacement = deployment("echo", "k8s", ScalingStrategy.INTERNAL, 0);
        FunctionRegistry exactRegistry = mock(FunctionRegistry.class);
        FunctionCapacityRegistry generations = new FunctionCapacityRegistry();
        generations.register("echo", 1);
        ManagedDeploymentProvider provider = mock(ManagedDeploymentProvider.class);
        when(provider.backendId()).thenReturn("k8s");
        it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatusSnapshot snapshot =
                it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatusSnapshot
                        .withDefaults(InstantSource.system());
        ManagedDeploymentCoordinator exactCoordinator = spy(new ManagedDeploymentCoordinator(
                new DeploymentProviderResolver(List.of(provider), new DeploymentProperties(null)),
                exactRegistry, new FunctionOperationLocks(), generations, snapshot));
        CountDownLatch oldLookupEntered = new CountDownLatch(1);
        CountDownLatch replacementInstalled = new CountDownLatch(1);
        AtomicReference<RegisteredFunction> current = new AtomicReference<>(oldRegistration);
        when(exactRegistry.getRegistered("echo")).thenAnswer(invocation -> {
            RegisteredFunction observed = current.get();
            if (observed == oldRegistration) {
                oldLookupEntered.countDown();
                await(replacementInstalled);
            }
            return Optional.of(observed);
        });
        doReturn(ReplicaObservation.unavailable(Instant.EPOCH, "missing"))
                .when(exactCoordinator).observeReplicaStatus(target);
        doReturn(new ReplicaStatus(0, 0)).when(exactCoordinator).getFreshReplicaStatus(target);
        doThrow(new IllegalStateException("STALE_REPLACEMENT_WRITE"))
                .when(exactCoordinator).setReplicas(any(FunctionGeneration.class), eq(target), eq(1));
        ScheduledThreadPoolExecutor scheduler = scheduler();
        DeploymentWakeUpGate gate = new DeploymentWakeUpGate(
                exactRegistry, exactCoordinator, generations, new DeploymentWakeUpProperties(), Runnable::run,
                scheduler, new DeploymentWakeUpCoordinator(generations, scheduler),
                InstantSource.system(), System::nanoTime);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<CompletableFuture<Void>> invocation = executor.submit(() -> gate.ensureReady(task));
            assertThat(oldLookupEntered.await(1, TimeUnit.SECONDS)).isTrue();

            generations.remove("echo");
            generations.register("echo", 1);
            FunctionGeneration replacementGeneration = generations.activeGeneration("echo");
            current.set(replacement);
            replacementInstalled.countDown();

            CompletableFuture<Void> ready = invocation.get(1, TimeUnit.SECONDS);
            assertThatThrownBy(ready::join)
                    .hasRootCauseMessage("DEPLOYMENT_WAKE_UP_TARGET_UNAVAILABLE");
            verify(exactCoordinator, never()).setReplicas(replacementGeneration, target, 1);
            assertThat(gate.ownedWakeUpCount()).isZero();
            assertThat(scheduler.getQueue()).isEmpty();
        } finally {
            replacementInstalled.countDown();
            gate.close();
            snapshot.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("FutureReturnValueIgnored")
    void ownerPublicationIsAtomicWithRemovalAndClose(boolean close) throws Exception {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        ManagedDeploymentTarget target = new ManagedDeploymentTarget("echo", "k8s");
        FunctionCapacityRegistry generations = generations();
        when(registry.getRegistered("echo"))
                .thenReturn(Optional.of(deployment("echo", "k8s", ScalingStrategy.INTERNAL, 0)));
        when(coordinator.observeReplicaStatus(target))
                .thenReturn(ReplicaObservation.unavailable(Instant.EPOCH, "missing"));
        ScheduledThreadPoolExecutor scheduler = scheduler();
        DeploymentWakeUpCoordinator wakeUpCoordinator = mock(DeploymentWakeUpCoordinator.class);
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        DeploymentWakeUpGate gate = new DeploymentWakeUpGate(
                registry, coordinator, generations, new DeploymentWakeUpProperties(), submitted::set,
                scheduler, wakeUpCoordinator, InstantSource.system(), System::nanoTime);
        BlockingPublicationMap<FunctionGeneration, Object> owners = new BlockingPublicationMap<>();
        replaceMap(gate, "inFlight", owners);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<CompletableFuture<Void>> invocation = executor.submit(() -> gate.ensureReady(task));
            assertThat(owners.publicationEntered.await(1, TimeUnit.SECONDS)).isTrue();
            AtomicReference<Thread> lifecycleThread = new AtomicReference<>();
            Future<?> lifecycle = executor.submit(() -> {
                lifecycleThread.set(Thread.currentThread());
                if (close) gate.close();
                else gate.onRemove("echo");
            });

            awaitDoneOrMonitorBlocked(lifecycle, lifecycleThread);
            owners.allowPublication.countDown();
            lifecycle.get(1, TimeUnit.SECONDS);
            CompletableFuture<Void> ready = invocation.get(1, TimeUnit.SECONDS);

            assertThat(ready).isCompletedExceptionally();
            if (submitted.get() != null) submitted.get().run();
            assertThat(gate.ownedWakeUpCount()).isZero();
            assertThat(scheduler.getQueue()).isEmpty();
        } finally {
            owners.allowPublication.countDown();
            gate.close();
        }
    }

    @AfterEach
    void stopSchedulers() {
        schedulers.forEach(ScheduledThreadPoolExecutor::shutdownNow);
    }

    @Test
    void ensureReady_thousandsOfFreshReadyObservationsDoNotFetchOrRetainTimers() {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        ManagedDeploymentTarget target = new ManagedDeploymentTarget("echo", "k8s");
        when(registry.getRegistered("echo"))
                .thenReturn(Optional.of(deployment("echo", "k8s", ScalingStrategy.INTERNAL, 0)));
        when(coordinator.observeReplicaStatus(target))
                .thenReturn(ReplicaObservation.fresh(new ReplicaStatus(1, 1), Instant.now()));
        when(coordinator.getFreshReplicaStatus(target)).thenReturn(new ReplicaStatus(1, 1));

        ScheduledThreadPoolExecutor scheduler = scheduler();
        try {
            DeploymentWakeUpGate gate = gate(Duration.ofSeconds(30), Duration.ofSeconds(1), Runnable::run,
                    scheduler);

            for (int invocation = 0; invocation < 2_000; invocation++) {
                gate.ensureReady(task).join();
            }

            verify(coordinator, times(2_000)).observeReplicaStatus(target);
            verify(coordinator, never()).getFreshReplicaStatus(target);
            assertThat(scheduler.getQueue()).isEmpty();
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void ensureReady_oneHundredCallersShareWakeUpWithoutAShortCallerCompletingTheOwner() throws Exception {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        ManagedDeploymentTarget target = new ManagedDeploymentTarget("echo", "k8s");
        CountDownLatch readEntered = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        when(registry.getRegistered("echo"))
                .thenReturn(Optional.of(deployment("echo", "k8s", ScalingStrategy.INTERNAL, 0)));
        when(coordinator.observeReplicaStatus(target))
                .thenReturn(ReplicaObservation.unavailable(Instant.EPOCH, "missing"));
        when(coordinator.getFreshReplicaStatus(target)).thenAnswer(invocation -> {
            readEntered.countDown();
            await(releaseRead);
            return new ReplicaStatus(1, 1);
        });

        ScheduledThreadPoolExecutor scheduler = scheduler();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            DeploymentWakeUpGate gate = gate(Duration.ofSeconds(30), Duration.ofSeconds(1), executor,
                    scheduler);
            CompletableFuture<Void> shortCaller = gate.ensureReady(task);
            assertThat(readEntered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(gate.ownedWakeUpCount()).isEqualTo(1);
            List<CompletableFuture<Void>> longCallers = java.util.stream.IntStream.range(0, 99)
                    .mapToObj(ignored -> gate.ensureReady(task))
                    .toList();

            shortCaller.completeExceptionally(new TimeoutException("caller deadline"));
            releaseRead.countDown();

            for (CompletableFuture<Void> longCaller : longCallers) {
                longCaller.get(1, TimeUnit.SECONDS);
            }
            assertThatThrownBy(shortCaller::join).hasRootCauseInstanceOf(TimeoutException.class);
            // Caller futures can complete before the owner callback releases its resources.
            executor.submit(() -> {}).get(1, TimeUnit.SECONDS);
            verify(coordinator, times(1)).getFreshReplicaStatus(target);
            assertThat(gate.ownedWakeUpCount()).isZero();
            assertThat(scheduler.getQueue()).isEmpty();
        } finally {
            releaseRead.countDown();
            executor.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    @Test
    void ensureReady_doesNotLowerAnExistingDesiredTargetWhileWaitingForReadiness() throws Exception {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        ManagedDeploymentTarget target = new ManagedDeploymentTarget("echo", "k8s");
        when(registry.getRegistered("echo"))
                .thenReturn(Optional.of(deployment("echo", "k8s", ScalingStrategy.INTERNAL, 0)));
        when(coordinator.observeReplicaStatus(target))
                .thenReturn(ReplicaObservation.fresh(new ReplicaStatus(10, 0), Instant.now()));
        when(coordinator.getFreshReplicaStatus(target))
                .thenReturn(new ReplicaStatus(10, 0), new ReplicaStatus(10, 1));

        ScheduledThreadPoolExecutor scheduler = scheduler();
        try {
            gate(Duration.ofSeconds(2), Duration.ofMillis(1), Runnable::run,
                    scheduler)
                    .ensureReady(task).get(1, TimeUnit.SECONDS);

            verify(coordinator, never()).setReplicas(any(FunctionGeneration.class), eq(target), eq(1));
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void ensureReady_asyncReadFailureRemovesItsTimeoutTask() throws Exception {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        ManagedDeploymentTarget target = new ManagedDeploymentTarget("echo", "k8s");
        when(registry.getRegistered("echo"))
                .thenReturn(Optional.of(deployment("echo", "k8s", ScalingStrategy.INTERNAL, 0)));
        when(coordinator.observeReplicaStatus(target))
                .thenReturn(ReplicaObservation.unavailable(Instant.EPOCH, "missing"));
        when(coordinator.getFreshReplicaStatus(target)).thenThrow(new IllegalStateException("provider unavailable"));

        ScheduledThreadPoolExecutor scheduler = scheduler();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            DeploymentWakeUpGate gate = gate(Duration.ofSeconds(30), Duration.ofSeconds(1), executor,
                    scheduler);
            CompletableFuture<Void> ready = gate.ensureReady(task);

            assertThatThrownBy(() -> ready.get(1, TimeUnit.SECONDS))
                    .hasRootCauseMessage("provider unavailable");
            executor.submit(() -> { }).get(1, TimeUnit.SECONDS);
            assertThat(gate.ownedWakeUpCount()).isZero();
            assertThat(scheduler.getQueue()).isEmpty();
        } finally {
            executor.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    @Test
    void removalAndShutdownCancelPendingWakeUpsAndTheirTimers() throws Exception {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        ManagedDeploymentTarget target = new ManagedDeploymentTarget("echo", "k8s");
        CountDownLatch readEntered = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        when(registry.getRegistered("echo"))
                .thenReturn(Optional.of(deployment("echo", "k8s", ScalingStrategy.INTERNAL, 0)));
        when(coordinator.observeReplicaStatus(target))
                .thenReturn(ReplicaObservation.unavailable(Instant.EPOCH, "missing"));
        when(coordinator.getFreshReplicaStatus(target)).thenAnswer(invocation -> {
            readEntered.countDown();
            await(releaseRead);
            return new ReplicaStatus(1, 1);
        });

        ScheduledThreadPoolExecutor scheduler = scheduler();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            DeploymentWakeUpGate gate = gate(Duration.ofSeconds(30), Duration.ofSeconds(1), executor,
                    scheduler);
            CompletableFuture<Void> removed = gate.ensureReady(task);
            assertThat(readEntered.await(1, TimeUnit.SECONDS)).isTrue();

            ((FunctionRegistrationListener) gate).onRemove("echo");

            assertThatThrownBy(removed::join).hasRootCauseMessage("DEPLOYMENT_WAKE_UP_REMOVED");
            assertThat(gate.ownedWakeUpCount()).isEqualTo(1);
            assertThat(scheduler.getQueue()).isEmpty();
            releaseRead.countDown();
            executor.submit(() -> { }).get(1, TimeUnit.SECONDS);
            assertThat(gate.ownedWakeUpCount()).isZero();
            ((AutoCloseable) gate).close();
            assertThatThrownBy(() -> gate.ensureReady(task).join())
                    .hasRootCauseMessage("DEPLOYMENT_WAKE_UP_CLOSED");
        } finally {
            releaseRead.countDown();
            executor.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    @Test
    void synchronousSubmissionFailureAndContextCloseDrainOwnersBeforeLateWork() throws Exception {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        ManagedDeploymentTarget target = new ManagedDeploymentTarget("echo", "k8s");
        FunctionCapacityRegistry generations = new FunctionCapacityRegistry();
        generations.register("echo", 1);
        when(coordinator.generationOf(any(RegisteredFunction.class)))
                .thenAnswer(invocation -> generations.activeGeneration("echo"));
        when(registry.getRegistered("echo"))
                .thenReturn(Optional.of(deployment("echo", "k8s", ScalingStrategy.INTERNAL, 0)));
        when(coordinator.observeReplicaStatus(target))
                .thenReturn(ReplicaObservation.unavailable(Instant.EPOCH, "missing"));
        ScheduledThreadPoolExecutor scheduler = scheduler();
        try { // NOSONAR (java:S2093): teardown is shutdownNow(), not a blocking close()
            DeploymentWakeUpCoordinator wakeUpCoordinator =
                    new DeploymentWakeUpCoordinator(generations, scheduler);
            DeploymentWakeUpGate rejectedGate = new DeploymentWakeUpGate(
                    registry, coordinator, generations, new DeploymentWakeUpProperties(),
                    ignored -> { throw new IllegalStateException("executor rejected"); }, scheduler,
                    wakeUpCoordinator, InstantSource.system(), System::nanoTime);

            assertThatThrownBy(() -> rejectedGate.ensureReady(task).join())
                    .hasRootCauseMessage("executor rejected");
            assertThat(rejectedGate.ownedWakeUpCount()).isZero();
            assertThat(scheduler.getQueue()).isEmpty();

            AtomicReference<Runnable> submitted = new AtomicReference<>();
            DeploymentWakeUpGate closingGate = new DeploymentWakeUpGate(
                    registry, coordinator, generations, new DeploymentWakeUpProperties(), submitted::set,
                    scheduler, wakeUpCoordinator, InstantSource.system(), System::nanoTime);
            CompletableFuture<Void> pending = closingGate.ensureReady(task);
            assertThat(closingGate.ownedWakeUpCount()).isEqualTo(1);

            closingGate.close();

            assertThatThrownBy(pending::join).hasRootCauseMessage("DEPLOYMENT_WAKE_UP_CLOSED");
            assertThat(closingGate.ownedWakeUpCount()).isEqualTo(1);
            assertThat(scheduler.getQueue()).isEmpty();
            submitted.get().run();
            assertThat(closingGate.ownedWakeUpCount()).isZero();
            verify(coordinator, never()).getFreshReplicaStatus(target);
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void configuredSchedulerRemovesCancelledTasksAndDropsDelayedWorkOnShutdown() {
        ScheduledThreadPoolExecutor scheduler = (ScheduledThreadPoolExecutor)
                new ManagedDeploymentOrchestration().deploymentWakeUpTimeoutScheduler();
        try {
            assertThat(scheduler.getRemoveOnCancelPolicy()).isTrue();
            assertThat(scheduler.getExecuteExistingDelayedTasksAfterShutdownPolicy()).isFalse();
            assertThat(scheduler.getContinueExistingPeriodicTasksAfterShutdownPolicy()).isFalse();
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void properties_defaultAndRejectNonPositiveDurations() {
        assertThat(new DeploymentWakeUpProperties().timeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(new DeploymentWakeUpProperties().pollInterval()).isEqualTo(Duration.ofMillis(250));
        assertThat(new DeploymentWakeUpProperties().readyObservationMaxAge()).isEqualTo(Duration.ofSeconds(5));
        Duration oneMillis = Duration.ofMillis(1);
        assertThatThrownBy(() -> new DeploymentWakeUpProperties(Duration.ZERO, oneMillis))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeploymentWakeUpProperties(oneMillis, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeploymentWakeUpProperties(oneMillis, oneMillis, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ensureReady_routesObservationStateAndAgeThroughTheDeclaredPolicy() {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        ManagedDeploymentTarget target = new ManagedDeploymentTarget("echo", "k8s");
        FunctionCapacityRegistry generations = new FunctionCapacityRegistry();
        generations.register("echo", 1);
        AtomicReference<Instant> now = new AtomicReference<>(Instant.EPOCH.plusSeconds(4));
        InstantSource clock = now::get;
        when(registry.getRegistered("echo"))
                .thenReturn(Optional.of(deployment("echo", "k8s", ScalingStrategy.INTERNAL, 0)));
        when(coordinator.getFreshReplicaStatus(target)).thenReturn(new ReplicaStatus(1, 1));
        ScheduledThreadPoolExecutor scheduler = scheduler();
        try { // NOSONAR (java:S2093): teardown is shutdownNow(), not a blocking close()
            DeploymentWakeUpGate gate = new DeploymentWakeUpGate(
                    registry, coordinator, generations,
                    new DeploymentWakeUpProperties(Duration.ofSeconds(30), Duration.ofSeconds(1),
                            Duration.ofSeconds(5)),
                    Runnable::run, scheduler, new DeploymentWakeUpCoordinator(generations, scheduler),
                    clock, System::nanoTime);

            when(coordinator.observeReplicaStatus(target))
                    .thenReturn(ReplicaObservation.fresh(new ReplicaStatus(1, 1), Instant.EPOCH));
            gate.ensureReady(task).join();
            verify(coordinator, never()).getFreshReplicaStatus(target);

            now.set(Instant.EPOCH.plusSeconds(6));
            when(coordinator.observeReplicaStatus(target)).thenReturn(
                    ReplicaObservation.fresh(new ReplicaStatus(1, 1), Instant.EPOCH),
                    ReplicaObservation.stale(new ReplicaStatus(1, 1), now.get()),
                    ReplicaObservation.unavailable(now.get(), "provider unavailable"),
                    null,
                    ReplicaObservation.fresh(new ReplicaStatus(0, 0), now.get()));

            for (int observation = 0; observation < 5; observation++) {
                gate.ensureReady(task).join();
            }

            verify(coordinator, times(5)).getFreshReplicaStatus(target);
            assertThat(gate.ownedWakeUpCount()).isZero();
            assertThat(scheduler.getQueue()).isEmpty();
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void freshReadyObservationCannotBypassAConcurrentFunctionDetach() {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        ManagedDeploymentTarget target = new ManagedDeploymentTarget("echo", "k8s");
        FunctionCapacityRegistry generations = new FunctionCapacityRegistry();
        generations.register("echo", 1);
        RegisteredFunction function = deployment("echo", "k8s", ScalingStrategy.INTERNAL, 0);
        when(registry.getRegistered("echo")).thenReturn(Optional.of(function), Optional.empty());
        when(coordinator.observeReplicaStatus(target))
                .thenReturn(ReplicaObservation.fresh(new ReplicaStatus(1, 1), Instant.now()));
        ScheduledThreadPoolExecutor scheduler = scheduler();
        try { // NOSONAR (java:S2093): teardown is shutdownNow(), not a blocking close()
            DeploymentWakeUpGate gate = new DeploymentWakeUpGate(
                    registry, coordinator, generations, new DeploymentWakeUpProperties(), Runnable::run,
                    scheduler, new DeploymentWakeUpCoordinator(generations, scheduler),
                    InstantSource.system(), System::nanoTime);

            assertThatThrownBy(() -> gate.ensureReady(task).join())
                    .hasRootCauseMessage("DEPLOYMENT_WAKE_UP_REMOVED");
            assertThat(gate.ownedWakeUpCount()).isZero();
            assertThat(scheduler.getQueue()).isEmpty();
            verify(coordinator, never()).getFreshReplicaStatus(target);
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void removeAndReregisterFenceTheOldGenerationAndAllowOnlyTheReplacementToComplete() throws Exception {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        ManagedDeploymentTarget target = new ManagedDeploymentTarget("echo", "k8s");
        FunctionCapacityRegistry generations = new FunctionCapacityRegistry();
        generations.register("echo", 1);
        when(coordinator.generationOf(any(RegisteredFunction.class)))
                .thenAnswer(invocation -> generations.activeGeneration("echo"));
        CountDownLatch oldReadEntered = new CountDownLatch(1);
        CountDownLatch releaseOldRead = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        when(registry.getRegistered("echo"))
                .thenReturn(Optional.of(deployment("echo", "k8s", ScalingStrategy.INTERNAL, 0)));
        when(coordinator.observeReplicaStatus(target))
                .thenReturn(ReplicaObservation.unavailable(Instant.EPOCH, "missing"));
        when(coordinator.getFreshReplicaStatus(target)).thenAnswer(invocation -> {
            if (reads.incrementAndGet() == 1) {
                oldReadEntered.countDown();
                await(releaseOldRead);
            }
            return new ReplicaStatus(1, 1);
        });

        ScheduledThreadPoolExecutor scheduler = scheduler();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch callbacksDrained = new CountDownLatch(2);
        java.util.concurrent.Executor trackingExecutor = action -> executor.execute(() -> {
            try {
                action.run();
            } finally {
                callbacksDrained.countDown();
            }
        });
        try { // NOSONAR (java:S2093): teardown is shutdownNow(), not a blocking close()
            DeploymentWakeUpGate gate = new DeploymentWakeUpGate(
                    registry, coordinator, generations, new DeploymentWakeUpProperties(), trackingExecutor,
                    scheduler, new DeploymentWakeUpCoordinator(generations, scheduler),
                    InstantSource.system(), System::nanoTime);
            CompletableFuture<Void> old = gate.ensureReady(task);
            assertThat(oldReadEntered.await(1, TimeUnit.SECONDS)).isTrue();

            gate.onRemove("echo");
            generations.remove("echo");
            generations.register("echo", 1);
            CompletableFuture<Void> replacement = gate.ensureReady(task);

            replacement.get(1, TimeUnit.SECONDS);
            releaseOldRead.countDown();
            assertThat(callbacksDrained.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(old::join).hasRootCauseMessage("DEPLOYMENT_WAKE_UP_REMOVED");
            assertThat(gate.ownedWakeUpCount()).isZero();
            assertThat(scheduler.getQueue()).isEmpty();
            verify(coordinator, never()).setReplicas(any(FunctionGeneration.class), eq(target), eq(1));
        } finally {
            releaseOldRead.countDown();
            executor.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    @Test
    void absoluteTimeoutDrainsTheOwnerBeforeLateWorkCanPublish() {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        ManagedDeploymentTarget target = new ManagedDeploymentTarget("echo", "k8s");
        FunctionCapacityRegistry generations = new FunctionCapacityRegistry();
        generations.register("echo", 1);
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        when(registry.getRegistered("echo"))
                .thenReturn(Optional.of(deployment("echo", "k8s", ScalingStrategy.INTERNAL, 0)));
        when(coordinator.observeReplicaStatus(target))
                .thenReturn(ReplicaObservation.unavailable(Instant.EPOCH, "missing"));
        ScheduledThreadPoolExecutor scheduler = scheduler();
        try { // NOSONAR (java:S2093): teardown is shutdownNow(), not a blocking close()
            DeploymentWakeUpGate gate = new DeploymentWakeUpGate(
                    registry, coordinator, generations, new DeploymentWakeUpProperties(), submitted::set,
                    scheduler, new DeploymentWakeUpCoordinator(generations, scheduler),
                    InstantSource.system(), System::nanoTime);
            CompletableFuture<Void> ready = gate.ensureReady(task);
            assertThat(gate.ownedWakeUpCount()).isEqualTo(1);
            assertThat(scheduler.getQueue()).hasSize(1);

            ((Runnable) scheduler.getQueue().peek()).run();

            assertThatThrownBy(ready::join).hasRootCauseMessage("DEPLOYMENT_WAKE_UP_TIMEOUT");
            assertThat(gate.ownedWakeUpCount()).isEqualTo(1);
            assertThat(scheduler.getQueue()).isEmpty();
            submitted.get().run();
            assertThat(gate.ownedWakeUpCount()).isZero();
            verify(coordinator, never()).getFreshReplicaStatus(target);
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void pollThatExecutesBeforeScheduleReturnsCannotCancelItsSuccessorPublication() {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        ManagedDeploymentTarget target = new ManagedDeploymentTarget("echo", "k8s");
        FunctionCapacityRegistry generations = generations();
        when(registry.getRegistered("echo"))
                .thenReturn(Optional.of(deployment("echo", "k8s", ScalingStrategy.INTERNAL, 0)));
        when(coordinator.observeReplicaStatus(target))
                .thenReturn(ReplicaObservation.unavailable(Instant.EPOCH, "missing"));
        when(coordinator.getFreshReplicaStatus(target))
                .thenReturn(new ReplicaStatus(0, 0), new ReplicaStatus(0, 0), new ReplicaStatus(1, 1));
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        AtomicInteger pollSchedules = new AtomicInteger();
        AtomicReference<Runnable> successor = new AtomicReference<>();
        AtomicReference<ScheduledFuture<?>> successorFuture = new AtomicReference<>();
        when(scheduler.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.NANOSECONDS)))
                .thenAnswer(invocation -> {
                    ScheduledFuture<?> future = mock(ScheduledFuture.class);
                    if (invocation.getArgument(1, Long.class) == 1L) {
                        if (pollSchedules.incrementAndGet() == 1) {
                            invocation.getArgument(0, Runnable.class).run();
                        } else {
                            successor.set(invocation.getArgument(0, Runnable.class));
                            successorFuture.set(future);
                        }
                    }
                    return future;
                });
        DeploymentWakeUpCoordinator wakeUpCoordinator =
                new DeploymentWakeUpCoordinator(generations, scheduler);
        DeploymentWakeUpGate gate = new DeploymentWakeUpGate(
                registry, coordinator, generations,
                new DeploymentWakeUpProperties(Duration.ofSeconds(30), Duration.ofNanos(1)),
                Runnable::run, scheduler, wakeUpCoordinator, InstantSource.system(), System::nanoTime);

        CompletableFuture<Void> ready = gate.ensureReady(task);
        assertThat(successor.get()).isNotNull();
        verify(successorFuture.get(), never()).cancel(false);
        successor.get().run();

        assertThat(ready).isCompletedWithValue(null);
        assertThat(gate.ownedWakeUpCount()).isZero();
        verify(coordinator, times(3)).getFreshReplicaStatus(target);
    }

    @Test
    void ensureReady_scalesEligibleZeroReplicaDeploymentToOneAndWaitsForReadiness() {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        ManagedDeploymentTarget target = new ManagedDeploymentTarget("echo", "k8s");
        when(registry.getRegistered("echo")).thenReturn(Optional.of(deployment("echo", "k8s", ScalingStrategy.INTERNAL, 0)));
        when(coordinator.getFreshReplicaStatus(target))
                .thenReturn(new ReplicaStatus(0, 0), new ReplicaStatus(1, 1));

        CompletableFuture<Void> ready = gate().ensureReady(task);

        ready.join();

        var order = inOrder(coordinator);
        order.verify(coordinator).getFreshReplicaStatus(target);
        order.verify(coordinator).setReplicas(any(FunctionGeneration.class), eq(target), eq(1));
        order.verify(coordinator).getFreshReplicaStatus(target);
        assertThat(ready).isCompletedWithValue(null);
    }

    @Test
    void ensureReady_releasesScaleDownProtectionAfterReadinessCompletes() {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        ManagedDeploymentTarget target = new ManagedDeploymentTarget("echo", "k8s");
        when(registry.getRegistered("echo")).thenReturn(Optional.of(deployment("echo", "k8s", ScalingStrategy.INTERNAL, 0)));
        when(coordinator.getFreshReplicaStatus(target)).thenReturn(new ReplicaStatus(0, 0), new ReplicaStatus(1, 1));

        FunctionCapacityRegistry generations = generations();
        ScheduledThreadPoolExecutor scheduler = scheduler();
        DeploymentWakeUpCoordinator wakeUpCoordinator = new DeploymentWakeUpCoordinator(generations, scheduler);
        gate(Duration.ofSeconds(1), Duration.ofMillis(1), Runnable::run,
                scheduler, generations, wakeUpCoordinator).ensureReady(task).join();

        var order = inOrder(coordinator);
        order.verify(coordinator).getFreshReplicaStatus(target);
        order.verify(coordinator).setReplicas(any(FunctionGeneration.class), eq(target), eq(1));
        assertThat(wakeUpCoordinator.scaleDownIfUnprotected(
                generations.activeGeneration("echo"), target, () -> true)).isTrue();
    }

    @Test
    void ensureReady_doesNotScaleAnAlreadyReadyEligibleDeployment() {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        ManagedDeploymentTarget target = new ManagedDeploymentTarget("echo", "k8s");
        when(registry.getRegistered("echo")).thenReturn(Optional.of(deployment("echo", "k8s", ScalingStrategy.INTERNAL, 0)));
        when(coordinator.getFreshReplicaStatus(target)).thenReturn(new ReplicaStatus(1, 1));

        gate().ensureReady(task).join();

        verify(coordinator).getFreshReplicaStatus(target);
        verify(coordinator, never()).setReplicas(any(FunctionGeneration.class), eq(target), eq(1));
    }

    @Test
    void ensureReady_removesCompletedWakeUpSoALaterScaleToZeroCanWakeAgain() {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        ManagedDeploymentTarget target = new ManagedDeploymentTarget("echo", "k8s");
        when(registry.getRegistered("echo")).thenReturn(Optional.of(deployment("echo", "k8s", ScalingStrategy.INTERNAL, 0)));
        when(coordinator.getFreshReplicaStatus(target)).thenReturn(
                new ReplicaStatus(0, 0), new ReplicaStatus(1, 1),
                new ReplicaStatus(0, 0), new ReplicaStatus(1, 1));

        DeploymentWakeUpGate gate = gate();
        gate.ensureReady(task).join();
        gate.ensureReady(task).join();

        verify(coordinator, times(2)).setReplicas(any(FunctionGeneration.class), eq(target), eq(1));
    }

    @Test
    @SuppressWarnings("FutureReturnValueIgnored")
    void ensureReady_coalescesConcurrentWakeUpsForTheSameFunction() throws Exception {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        ManagedDeploymentTarget target = new ManagedDeploymentTarget("echo", "container-local");
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch firstStatusEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstStatus = new CountDownLatch(1);
        AtomicInteger statusCalls = new AtomicInteger();
        when(registry.getRegistered("echo")).thenReturn(Optional.of(deployment("echo", "container-local", ScalingStrategy.INTERNAL, 0)));
        when(coordinator.getFreshReplicaStatus(target)).thenAnswer(invocation -> {
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

            assertThat(first).isNotSameAs(second);
            verify(coordinator, times(1)).setReplicas(any(FunctionGeneration.class), eq(target), eq(1));
        }
    }

    @Test
    void ensureReady_timesOutDistinctlyWhenNoReplicaBecomesReady() {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        ManagedDeploymentTarget target = new ManagedDeploymentTarget("echo", "k8s");
        when(registry.getRegistered("echo")).thenReturn(Optional.of(deployment("echo", "k8s", ScalingStrategy.INTERNAL, 0)));
        when(coordinator.getFreshReplicaStatus(target)).thenReturn(new ReplicaStatus(0, 0));

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
        when(coordinator.getFreshReplicaStatus(target)).thenReturn(new ReplicaStatus(0, 0));
        long started = System.nanoTime();

        assertThatThrownBy(() -> gate(Duration.ofMillis(50), Duration.ofSeconds(1))
                .ensureReady(task).get(250, TimeUnit.MILLISECONDS))
                .isInstanceOf(java.util.concurrent.ExecutionException.class)
                .hasRootCauseMessage("DEPLOYMENT_WAKE_UP_TIMEOUT");

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(250));
    }

    @Test
    void ensureReady_timeoutDoesNotDependOnTheSharedCompletableFutureDelayScheduler() throws Exception {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        ManagedDeploymentTarget target = new ManagedDeploymentTarget("echo", "k8s");
        CountDownLatch delaySchedulerBlocked = new CountDownLatch(1);
        CountDownLatch releaseDelayScheduler = new CountDownLatch(1);
        when(registry.getRegistered("echo")).thenReturn(Optional.of(deployment("echo", "k8s", ScalingStrategy.INTERNAL, 0)));
        when(coordinator.getFreshReplicaStatus(target)).thenReturn(new ReplicaStatus(0, 0));
        CompletableFuture.delayedExecutor(0, TimeUnit.NANOSECONDS, Runnable::run)
                .execute(() -> {
                    delaySchedulerBlocked.countDown();
                    await(releaseDelayScheduler);
                });
        assertThat(delaySchedulerBlocked.await(1, TimeUnit.SECONDS)).isTrue();

        try {
            assertThatThrownBy(() -> gate(Duration.ofMillis(50), Duration.ofSeconds(1))
                    .ensureReady(task).get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .hasRootCauseMessage("DEPLOYMENT_WAKE_UP_TIMEOUT");
        } finally {
            releaseDelayScheduler.countDown();
        }
    }

    @Test
    void ensureReady_pollsIndependentlyOfTheSharedCompletableFutureDelayScheduler() throws Exception {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        ManagedDeploymentTarget target = new ManagedDeploymentTarget("echo", "k8s");
        CountDownLatch delaySchedulerBlocked = new CountDownLatch(1);
        CountDownLatch releaseDelayScheduler = new CountDownLatch(1);
        when(registry.getRegistered("echo")).thenReturn(Optional.of(deployment("echo", "k8s", ScalingStrategy.INTERNAL, 0)));
        when(coordinator.getFreshReplicaStatus(target)).thenReturn(new ReplicaStatus(0, 0), new ReplicaStatus(1, 1));
        CompletableFuture.delayedExecutor(0, TimeUnit.NANOSECONDS, Runnable::run)
                .execute(() -> {
                    delaySchedulerBlocked.countDown();
                    await(releaseDelayScheduler);
                });
        assertThat(delaySchedulerBlocked.await(1, TimeUnit.SECONDS)).isTrue();

        try {
            gate(Duration.ofMillis(200), Duration.ofMillis(5)).ensureReady(task).get(150, TimeUnit.MILLISECONDS);
        } finally {
            releaseDelayScheduler.countDown();
        }
    }

    @Test
    void ensureReady_timesOutWhileTheFirstProviderStatusCallIsBlocked() throws Exception {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        ManagedDeploymentTarget target = new ManagedDeploymentTarget("echo", "k8s");
        CountDownLatch statusEntered = new CountDownLatch(1);
        CountDownLatch releaseStatus = new CountDownLatch(1);
        when(registry.getRegistered("echo")).thenReturn(Optional.of(deployment("echo", "k8s", ScalingStrategy.INTERNAL, 0)));
        when(coordinator.getFreshReplicaStatus(target)).thenAnswer(invocation -> {
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
        verify(coordinator, never()).setReplicas(any(FunctionGeneration.class), eq(target), eq(1));
    }

    @Test
    void ensureReady_failsWhenTheDeploymentTargetIsMissing() {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        when(registry.getRegistered("echo")).thenReturn(Optional.empty());

        CompletableFuture<Void> ready = gate().ensureReady(task);
        assertThatThrownBy(ready::join)
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

        CompletableFuture<Void> ready = gate().ensureReady(task);
        assertThatThrownBy(ready::join)
                .isInstanceOf(CompletionException.class)
                .hasRootCauseMessage("DEPLOYMENT_WAKE_UP_TARGET_UNAVAILABLE");
        verifyNoInteractions(coordinator);
    }

    @Test
    void ensureReady_propagatesReplicaScaleFailure() {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        ManagedDeploymentTarget target = new ManagedDeploymentTarget("echo", "k8s");
        when(registry.getRegistered("echo")).thenReturn(Optional.of(deployment("echo", "k8s", ScalingStrategy.INTERNAL, 0)));
        when(coordinator.observeReplicaStatus(target))
                .thenReturn(ReplicaObservation.unavailable(Instant.EPOCH, "missing"));
        when(coordinator.getFreshReplicaStatus(target)).thenReturn(new ReplicaStatus(0, 0));
        doThrow(new IllegalStateException("provider unavailable"))
                .when(coordinator).setReplicas(any(FunctionGeneration.class), eq(target), eq(1));

        FunctionCapacityRegistry generations = new FunctionCapacityRegistry();
        generations.register("echo", 1);
        ScheduledThreadPoolExecutor scheduler = scheduler();
        try { // NOSONAR (java:S2093): teardown is shutdownNow(), not a blocking close()
            DeploymentWakeUpCoordinator wakeUpCoordinator =
                    new DeploymentWakeUpCoordinator(generations, scheduler);
            DeploymentWakeUpGate gate = new DeploymentWakeUpGate(
                    registry, coordinator, generations, new DeploymentWakeUpProperties(), Runnable::run,
                    scheduler, wakeUpCoordinator, InstantSource.system(), System::nanoTime);

            assertThatThrownBy(() -> gate.ensureReady(task).join())
                    .isInstanceOf(CompletionException.class)
                    .hasRootCauseMessage("provider unavailable");
            assertThat(gate.ownedWakeUpCount()).isZero();
            assertThat(scheduler.getQueue()).isEmpty();
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void ensureReady_propagatesReplicaStatusFailure() {
        InvocationTask task = task("echo", ExecutionMode.DEPLOYMENT, ScalingStrategy.INTERNAL, 0);
        ManagedDeploymentTarget target = new ManagedDeploymentTarget("echo", "k8s");
        when(registry.getRegistered("echo")).thenReturn(Optional.of(deployment("echo", "k8s", ScalingStrategy.INTERNAL, 0)));
        when(coordinator.observeReplicaStatus(target))
                .thenReturn(ReplicaObservation.unavailable(Instant.EPOCH, "missing"));
        when(coordinator.getFreshReplicaStatus(target)).thenThrow(new IllegalStateException("provider unavailable"));

        ScheduledThreadPoolExecutor scheduler = scheduler();
        try {
            DeploymentWakeUpGate gate = gate(Duration.ofSeconds(30), Duration.ofSeconds(1), Runnable::run,
                    scheduler);
            CompletableFuture<Void> ready = gate.ensureReady(task);

            assertThatThrownBy(ready::join)
                    .isInstanceOf(CompletionException.class)
                    .hasRootCauseMessage("provider unavailable");
            assertThat(gate.ownedWakeUpCount()).isZero();
            assertThat(scheduler.getQueue()).isEmpty();
        } finally {
            scheduler.shutdownNow();
        }
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
        return gate(timeout, pollInterval, Runnable::run, scheduler());
    }

    private DeploymentWakeUpGate gate(Duration timeout, Duration pollInterval, ExecutorService executor) {
        return gate(timeout, pollInterval, executor, scheduler());
    }

    private DeploymentWakeUpGate gate(Duration timeout,
                                      Duration pollInterval,
                                      java.util.concurrent.Executor executor,
                                      ScheduledThreadPoolExecutor scheduler) {
        FunctionCapacityRegistry generations = generations();
        return gate(timeout, pollInterval, executor, scheduler, generations,
                new DeploymentWakeUpCoordinator(generations, scheduler));
    }

    private DeploymentWakeUpGate gate(Duration timeout,
                                       Duration pollInterval,
                                       java.util.concurrent.Executor executor,
                                       ScheduledThreadPoolExecutor scheduler,
                                       FunctionCapacityRegistry generations,
                                       DeploymentWakeUpCoordinator wakeUpCoordinator) {
        return new DeploymentWakeUpGate(
                registry, coordinator, generations,
                new DeploymentWakeUpProperties(timeout, pollInterval), executor, scheduler,
                wakeUpCoordinator, InstantSource.system(), System::nanoTime);
    }

    private ScheduledThreadPoolExecutor scheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        scheduler.setRemoveOnCancelPolicy(true);
        schedulers.add(scheduler);
        return scheduler;
    }

    private static FunctionCapacityRegistry generations() {
        FunctionCapacityRegistry generations = new FunctionCapacityRegistry();
        generations.register("echo", 1);
        return generations;
    }

    private static void replaceMap(Object owner, String fieldName, Object replacement) throws Exception {
        java.lang.reflect.Field field = owner.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(owner, replacement);
    }

    private static void awaitDoneOrMonitorBlocked(Future<?> lifecycle, AtomicReference<Thread> thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!lifecycle.isDone()) {
            Thread current = thread.get();
            if (current != null && current.getState() == Thread.State.BLOCKED) return;
            if (System.nanoTime() >= deadline) throw new AssertionError("lifecycle did not return or block on admission");
            Thread.onSpinWait();
        }
    }

    private static final class BlockingPublicationMap<K, V> extends ConcurrentHashMap<K, V> {
        private final CountDownLatch publicationEntered = new CountDownLatch(1);
        private final CountDownLatch allowPublication = new CountDownLatch(1);

        @Override
        public V putIfAbsent(K key, V value) {
            publicationEntered.countDown();
            await(allowPublication);
            return super.putIfAbsent(key, value);
        }
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
        ,
        InvocationKind.SYNC
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
