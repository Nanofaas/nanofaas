package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.controlplane.capacity.DispatchOwnership;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionState;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistrationListener;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingStrategy;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingTicket;
import it.unimib.datai.nanofaas.controlplane.scheduler.TicketId;
import it.unimib.datai.nanofaas.controlplane.service.EngineInvocationEnqueuer;
import it.unimib.datai.nanofaas.controlplane.service.EngineInvocationEnqueuer.AdmissionProfile;
import it.unimib.datai.nanofaas.controlplane.service.EngineInvocationEnqueuer.PerFunctionDepth;
import it.unimib.datai.nanofaas.controlplane.service.EngineSyncQueueGateway;
import it.unimib.datai.nanofaas.controlplane.service.SchedulerConfiguration;
import it.unimib.datai.nanofaas.execution.EngineDispatch;
import it.unimib.datai.nanofaas.execution.EngineReadiness;
import it.unimib.datai.nanofaas.execution.PendingEntry;
import it.unimib.datai.nanofaas.execution.PendingWorkStore;
import it.unimib.datai.nanofaas.execution.SchedulerEngine;
import it.unimib.datai.nanofaas.execution.StrategyRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 8 fix round (issue #208), finding I4: this test used to assert an inline rebuild of the
 * retired {@code AsyncQueueConfiguration.queueLifecycleListener}, which proved nothing about
 * production — it could not have caught C2 (function removal no longer terminating queued
 * executions), because it never exercised the real listener. It now drives the actual production
 * bean, {@link SchedulerConfiguration#schedulerCapacityGenerationListener}, over a real (if
 * minimally wired) {@link SchedulerEngine}.
 */
class AsyncQueueConfigurationTest {
    private final ExecutionStore executionStore = new ExecutionStore();

    @Test
    void schedulerCapacityGenerationListener_marksDrainedQueuedExecutionAsFunctionRemoved() {
        FunctionCapacityRegistry capacityRegistry = new FunctionCapacityRegistry();
        PendingWorkStore store = new PendingWorkStore(8);
        // Never runnable: the ticket this test admits stays pending (never claimed), so the
        // removal listener's drain is what has to remove it, not an ordinary dispatch.
        EngineReadiness readiness = generation -> false;
        EngineDispatch dispatch = new EngineDispatch() {
            @Override
            public DispatchOwnership tryAcquire(SchedulingTicket ticket) {
                throw new AssertionError("never selected: readiness always refuses");
            }

            @Override
            public boolean isCurrent(SchedulingTicket ticket) {
                return true;
            }

            @Override
            public void submit(InvocationTask task) {
                throw new AssertionError("never selected: readiness always refuses");
            }

            @Override
            public void expired(InvocationTask task) {
                executionStore.expired(task);
            }

            @Override
            public void removed(InvocationTask task) {
                executionStore.removed(task);
            }

            @Override
            public void rejected(InvocationTask task, Throwable failure) {
                executionStore.rejected(task, failure);
            }
        };
        SchedulingStrategy strategy = new PerFunctionSchedulingStrategy();
        SchedulerEngine engine = new SchedulerEngine(store, new StrategyRegistry(List.of(strategy)),
                strategy.id(), dispatch, readiness, Clock.systemUTC(), System::nanoTime);

        ObjectProvider<EngineSyncQueueGateway> noSyncGateway = noSyncGateway();
        FunctionRegistrationListener listener = new SchedulerConfiguration()
                .schedulerCapacityGenerationListener(capacityRegistry, engine, new PerFunctionDepth(), noSyncGateway);

        FunctionSpec spec = spec("echo");
        InvocationTask task = task("exec-queued", spec);
        ExecutionRecord executionRecord = new ExecutionRecord(task.executionId(), task);

        listener.onRegister(spec);
        executionStore.put(executionRecord);
        FunctionGeneration generation = capacityRegistry.activeGeneration("echo");
        assertThat(generation).isNotNull();
        SchedulingTicket ticket = new SchedulingTicket(
                new TicketId(task.executionId(), task.attempt()), generation, 0,
                Instant.now(), Instant.now(), null);
        assertThat(engine.enqueue(new PendingEntry(ticket, task))).isTrue();

        listener.onRemove("echo");

        assertThat(executionRecord.state()).isEqualTo(ExecutionState.ERROR);
        assertThat(executionRecord.lastError().code()).isEqualTo("FUNCTION_REMOVED");
        assertThat(executionRecord.lastError().message()).contains("echo");
        assertThat(executionRecord.completion().isDone()).isTrue();
        InvocationResult result = executionRecord.completion().join();
        assertThat(result.success()).isFalse();
        assertThat(result.error().code()).isEqualTo("FUNCTION_REMOVED");
    }

    /**
     * Task 8 fix round 2 (issue #208): reproduces the shape both new findings shared — a removal
     * racing concurrent admissions for the same function — against the real production listener
     * and the real {@link EngineInvocationEnqueuer#enqueue} admission path (not a hand-rolled
     * stand-in), so it exercises exactly what {@code DELETE /v1/functions/{name}} does under
     * redeploy-churn traffic.
     *
     * <p>Against the pre-fix-round-2 listener (a raw {@code store.snapshotPending()} scan off the
     * engine's gate, draining before retiring capacity) this reliably reproduces the two
     * documented symptoms: {@code onRemove} throwing out of the concurrently-mutated
     * {@code LinkedHashMap} (NEW-CRITICAL), and/or a ticket admitted during the drain window
     * surviving it (NEW-IMPORTANT) — confirmed by running this test against that code during
     * development, both manually reverted and by construction (see the fix-round report). Against
     * the fix, neither {@code onRemove} nor the admitting thread may throw, and nothing may be
     * left pending for the function once both have settled.
     */
    @Test
    void concurrentRemovalRacingAdmissionNeitherThrowsNorStrandsATicket() throws InterruptedException {
        FunctionCapacityRegistry capacityRegistry = new FunctionCapacityRegistry();
        PendingWorkStore store = new PendingWorkStore(256);
        EngineReadiness readiness = generation -> false;
        EngineDispatch dispatch = new EngineDispatch() {
            @Override
            public DispatchOwnership tryAcquire(SchedulingTicket ticket) {
                throw new AssertionError("never selected: readiness always refuses");
            }

            @Override
            public boolean isCurrent(SchedulingTicket ticket) {
                return true;
            }

            @Override
            public void submit(InvocationTask task) {
                throw new AssertionError("never selected: readiness always refuses");
            }

            @Override
            public void expired(InvocationTask task) {
                executionStore.expired(task);
            }

            @Override
            public void removed(InvocationTask task) {
                executionStore.removed(task);
            }

            @Override
            public void rejected(InvocationTask task, Throwable failure) {
                executionStore.rejected(task, failure);
            }
        };
        SchedulingStrategy strategy = new PerFunctionSchedulingStrategy();
        SchedulerEngine engine = new SchedulerEngine(store, new StrategyRegistry(List.of(strategy)),
                strategy.id(), dispatch, readiness, Clock.systemUTC(), System::nanoTime);
        ObjectProvider<SchedulerEngine> engineProvider = new ObjectProvider<>() {
            @Override
            public SchedulerEngine getObject() {
                return engine;
            }
        };
        PerFunctionDepth perFunctionDepth = new PerFunctionDepth();
        AtomicLong sequence = new AtomicLong();
        EngineInvocationEnqueuer enqueuer = new EngineInvocationEnqueuer(engineProvider, capacityRegistry,
                sequence::incrementAndGet, AdmissionProfile.FUNCTION_QUEUE, true, noSyncGateway(), perFunctionDepth);

        FunctionRegistrationListener listener = new SchedulerConfiguration()
                .schedulerCapacityGenerationListener(capacityRegistry, engine, perFunctionDepth, noSyncGateway());

        // A large queueSize: the per-function cap (I1) must not be what stops the admitter mid-race
        // — this test is about the removal/admission race, not the per-function depth cap.
        FunctionSpec spec = new FunctionSpec("echo", "image", null, Map.of(), null,
                1000, 4, 10_000, 3, null, ExecutionMode.DEPLOYMENT, null, null, null);
        listener.onRegister(spec);

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<Throwable> admitterFailure = new AtomicReference<>();
        Thread admitter = new Thread(() -> {
            int i = 0;
            while (running.get()) {
                InvocationTask task = task("exec-race-" + i++, spec);
                try {
                    executionStore.put(new ExecutionRecord(task.executionId(), task));
                    enqueuer.enqueue(task);
                } catch (Throwable failure) {
                    admitterFailure.compareAndSet(null, failure);
                    return;
                }
            }
        });
        int pendingImmediatelyAfterRemoval;
        admitter.start();
        try {
            // Let a burst of admissions land before racing the removal against them.
            Thread.sleep(20);
            listener.onRemove("echo"); // must not throw: NEW-CRITICAL
        } finally {
            // Measured the instant onRemove returns, with the admitter still racing: retiring
            // capacity BEFORE draining (the fix) closes the door to further admissions almost
            // immediately, so this should be ~0. The pre-fix-round-2 order (drain, then retire
            // capacity last) leaves the door open for the admitter's entire time inside onRemove
            // — which does a store scan plus a capacity-registry mutation — so a burst of tickets
            // keeps being admitted throughout and is left behind by the single already-collected
            // snapshot the old drain iterated: this is where NEW-IMPORTANT shows up as a
            // reliably non-trivial backlog, not a one-off race.
            pendingImmediatelyAfterRemoval = store.pendingCount();
            running.set(false);
            admitter.join(5_000);
        }

        assertThat(admitterFailure.get()).isNull();
        assertThat(pendingImmediatelyAfterRemoval)
                .as("tickets still pending for 'echo' the instant onRemove returned — capacity "
                        + "must be retired before the drain so admission stops almost immediately")
                .isZero();
        // Cleanup courtesy for anything from the tiny residual window (disclosed, not what this
        // test targets) that may have landed after the measurement above.
        engine.removeAllFor("echo");
        assertThat(store.pendingCount()).isZero();
    }

    private static ObjectProvider<EngineSyncQueueGateway> noSyncGateway() {
        return new ObjectProvider<>() {
            @Override
            public EngineSyncQueueGateway getObject() {
                throw new NoSuchBeanDefinitionException(EngineSyncQueueGateway.class);
            }
        };
    }

    private static FunctionSpec spec(String name) {
        return new FunctionSpec(
                name,
                "image",
                null,
                Map.of(),
                null,
                1000,
                4,
                10,
                3,
                null,
                ExecutionMode.DEPLOYMENT,
                null,
                null,
                null
        );
    }

    private static InvocationTask task(String executionId, FunctionSpec spec) {
        return new InvocationTask(
                executionId,
                spec.name(),
                spec,
                new InvocationRequest("payload", Map.of()),
                null,
                null,
                Instant.now(),
                1
        ,
        InvocationKind.SYNC
    );
    }
}
