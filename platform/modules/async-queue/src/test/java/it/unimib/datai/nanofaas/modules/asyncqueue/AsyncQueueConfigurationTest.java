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
import it.unimib.datai.nanofaas.controlplane.service.EngineSyncQueueGateway;
import it.unimib.datai.nanofaas.controlplane.service.EngineWorkloadMetricsSource;
import it.unimib.datai.nanofaas.controlplane.service.SchedulerConfiguration;
import it.unimib.datai.nanofaas.execution.EngineDispatch;
import it.unimib.datai.nanofaas.execution.EngineReadiness;
import it.unimib.datai.nanofaas.execution.PendingEntry;
import it.unimib.datai.nanofaas.execution.PendingWorkStore;
import it.unimib.datai.nanofaas.execution.SchedulerEngine;
import it.unimib.datai.nanofaas.execution.StrategyRegistry;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsBinder;
import it.unimib.datai.nanofaas.workloadmetrics.WorkloadMetricsSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
                strategy.id(), dispatch, readiness,
                generation -> generation.equals(capacityRegistry.activeGeneration(generation.functionName())),
                Clock.systemUTC(), System::nanoTime);

        ObjectProvider<EngineSyncQueueGateway> noSyncGateway = noSyncGateway();
        FunctionRegistrationListener listener = new SchedulerConfiguration()
                .schedulerCapacityGenerationListener(capacityRegistry, engine,
                        noSyncGateway, testMetricsBinder());

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
                strategy.id(), dispatch, readiness,
                generation -> generation.equals(capacityRegistry.activeGeneration(generation.functionName())),
                Clock.systemUTC(), System::nanoTime);
        ObjectProvider<SchedulerEngine> engineProvider = new ObjectProvider<>() {
            @Override
            public SchedulerEngine getObject() {
                return engine;
            }
        };
        AtomicLong sequence = new AtomicLong();
        EngineInvocationEnqueuer enqueuer = new EngineInvocationEnqueuer(engineProvider, capacityRegistry,
                sequence::incrementAndGet, AdmissionProfile.FUNCTION_QUEUE, true, noSyncGateway());

        FunctionRegistrationListener listener = new SchedulerConfiguration()
                .schedulerCapacityGenerationListener(capacityRegistry, engine,
                        noSyncGateway(), testMetricsBinder());

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
        admitter.start();
        try {
            // Wait for the pre-state instead of sleeping for it: at least one admission must have
            // landed before the removal is raced against it, and a bounded await says that rather
            // than hoping 20 ms was long enough on this machine.
            Awaitility.await("a burst of admissions lands before the removal is raced against it")
                    .atMost(Duration.ofSeconds(2))
                    .until(() -> store.pendingCount() > 0);
            listener.onRemove("echo"); // must not throw: NEW-CRITICAL
            // The engine refuses a ticket whose generation is no longer active under its gate,
            // and the removal drained everything admitted before it, so the count is zero the
            // moment onRemove returns — not after some compensating removal catches up — and
            // stays there while the admitter keeps hammering a now-retired function.
            assertThat(engine.reservedCount("echo"))
                    .as("reservations for 'echo' immediately after onRemove")
                    .isZero();
            assertThat(store.pendingCount()).isZero();
        } finally {
            running.set(false);
            admitter.join(5_000);
        }

        assertThat(admitterFailure.get()).isNull();
        // Firm check once the admitter has fully stopped: nothing reappears afterward either.
        assertThat(store.pendingCount()).isZero();
        assertThat(engine.reservedCount("echo")).isZero();
    }

    /**
     * Written against a real {@link WorkloadMetricsBinder} bound to a real
     * {@link EngineWorkloadMetricsSource} (not the zero-source stand-in the other tests in this
     * file use) so it can observe actual meter registration/removal: a meter registered but never
     * recorded, or a {@code sync_queue_depth} never decremented, would both be visible here.
     *
     * <p>Falsifiable against the pre-fix code two different ways: (1) reverting the drain-listener
     * registration in {@code SchedulerConfiguration.schedulerCapacityGenerationListener} (calling
     * {@code metricsBinder.removeFunction} directly from {@code onRemove} instead of via
     * {@code engine.markDraining}/{@code addDrainListener}) makes the "still active" assertion
     * below fail — the meters would already be gone. (2) {@code PendingWorkStore} prunes a
     * function's reservation entry when it reaches zero; that has no externally observable effect
     * through {@code WorkloadMetricsBinder}, so it is verified by asserting
     * {@code engine.reservedCount} directly (and by {@code PendingWorkStoreTest}'s churn check).
     *
     * <p>Correction after this test's first draft (still fix round 1, caught by actually running
     * it rather than by inspection): the "still physically active" ticket cannot be represented
     * by a readiness-blocked selection — a ticket blocked that way never leaves "pending", and
     * {@code removeAllFor} purges pending work on removal by design (fix round C2), so it was
     * reaped exactly like an ordinary queued caller instead of surviving. Genuine "physically
     * active" here means claimed/submitting, which {@code removeAllFor}'s own contract leaves
     * untouched — and since {@code submit()} is contractually non-blocking (the reservation is
     * released in its own {@code finally}, on the same thread, immediately after it returns),
     * observing that state from outside needs a real interleaving: a latch parks the worker
     * thread inside {@code submit()} so the main thread can call {@code onRemove} while the
     * reservation is still open, then releases it to let the drain reconcile.
     */
    @Test
    void oneHundredGenerationsOfTheSameNameReturnMetersAndDrainSetToBaselineWhileAStillActiveLeaseSurvivesUntilDrain()
            throws InterruptedException {
        FunctionCapacityRegistry capacityRegistry = new FunctionCapacityRegistry();
        PendingWorkStore store = new PendingWorkStore(64);
        EngineReadiness readiness = generation -> true;
        // A ticket blocked by readiness never leaves "pending" (SchedulingIndex#select simply
        // will not offer it), and removeAllFor purges pending work on function removal by
        // design (fix round C2) — so "readiness=false" cannot stand in for "still physically
        // active" here; it stands in for "never got to run" and gets reaped exactly like every
        // other queued caller of a removed function. The ONLY reservation state removeAllFor
        // deliberately leaves untouched is "already claimed or submitting" (see its own javadoc),
        // and submit() itself is contractually non-blocking (finishSubmit — and the reservation
        // release with it — runs synchronously in submit()'s own finally, back-to-back, on
        // whichever thread called tick()). Observing "claimed/submitting, not yet settled" from
        // outside therefore needs a real interleaving: a latch holds the worker thread INSIDE
        // submit() so the main thread can call onRemove while the reservation is still open.
        CountDownLatch submitEntered = new CountDownLatch(1);
        CountDownLatch releaseSubmit = new CountDownLatch(1);
        AtomicBoolean holdNextSubmit = new AtomicBoolean(false);
        EngineDispatch dispatch = new EngineDispatch() {
            @Override
            public DispatchOwnership tryAcquire(SchedulingTicket ticket) {
                return new DispatchOwnership() {
                    private volatile boolean released;

                    @Override
                    public FunctionGeneration generation() {
                        return ticket.generation();
                    }

                    @Override
                    public void release() {
                        released = true;
                    }

                    @Override
                    public boolean isReleased() {
                        return released;
                    }
                };
            }

            @Override
            public void submit(InvocationTask task) {
                if (holdNextSubmit.compareAndSet(true, false)) {
                    submitEntered.countDown();
                    awaitUninterruptibly(releaseSubmit);
                    return;
                }
                // No-op success: the engine treats this as dispatched and the
                // engine settles the reservation (finishSubmit) synchronously within the same
                // tick — see SchedulerEngineQueueSnapshotTest for the same observation.
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
                strategy.id(), dispatch, readiness,
                generation -> generation.equals(capacityRegistry.activeGeneration(generation.functionName())),
                Clock.systemUTC(), System::nanoTime);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WorkloadMetricsBinder binder = new WorkloadMetricsBinder(
                registry, new EngineWorkloadMetricsSource(engine, capacityRegistry));
        FunctionRegistrationListener listener = new SchedulerConfiguration()
                .schedulerCapacityGenerationListener(capacityRegistry, engine,
                        noSyncGateway(), binder);

        int baseline = registry.getMeters().size();
        assertThat(baseline).isZero();

        // 99 churns with nothing admitted: the removal listener's own onRemove->markDraining
        // path finds reservedCount already zero, so the drain listener fires on the very next
        // tick and meters return to baseline every time — no leak across repeated
        // register/remove cycles of the SAME function name (a repeated re-registration of one
        // name, not 99 distinct names).
        for (int i = 0; i < 99; i++) {
            listener.onRegister(spec("echo"));
            assertThat(registry.getMeters())
                    .as("generation #%d: registration must publish exactly the 4 function_* gauges", i)
                    .hasSize(baseline + 4);

            listener.onRemove("echo");
            engine.tick();
            assertThat(registry.getMeters())
                    .as("generation #%d: meters must return to baseline after the drain reconciles", i)
                    .hasSize(baseline);
        }
        assertThat(engine.reservedCount("echo"))
                .as("the signal-set (draining/reservation bookkeeping) must also be back at baseline")
                .isZero();

        // The 100th generation: an admitted ticket the engine has already claimed and started
        // submitting — genuinely "still physically active", not merely queued — when the removal
        // arrives. Readiness stays true throughout: what keeps the reservation open here is the
        // held submit() call, not a blocked selection.
        FunctionSpec spec = spec("echo");
        listener.onRegister(spec);
        InvocationTask task = task("exec-100", spec);
        FunctionGeneration generation = capacityRegistry.activeGeneration("echo");
        SchedulingTicket ticket = new SchedulingTicket(new TicketId(task.executionId(), task.attempt()),
                generation, 0, Instant.now(), Instant.now(), null);
        assertThat(engine.enqueue(new PendingEntry(ticket, task))).isTrue();

        holdNextSubmit.set(true);
        Thread worker = new Thread(engine::tick, "test-engine-tick");
        worker.start();
        try {
            assertThat(submitEntered.await(5, TimeUnit.SECONDS))
                    .as("the worker thread must be parked inside submit(), ticket claimed")
                    .isTrue();

            // The removal arrives while the ticket is mid-submit: removeAllFor's own contract
            // ("a ticket already claimed or submitting is left untouched") is what this proves,
            // not a readiness gate.
            listener.onRemove("echo");

            assertThat(engine.reservedCount("echo"))
                    .as("the ticket is still physically reserved: submit() has not returned yet")
                    .isEqualTo(1);
            assertThat(registry.getMeters())
                    .as("meters must survive while a reservation from the retired generation is still open")
                    .hasSize(baseline + 4);
        } finally {
            releaseSubmit.countDown();
        }
        worker.join(TimeUnit.SECONDS.toMillis(5));
        assertThat(worker.isAlive()).isFalse();

        // submit() returning settles the reservation (finishSubmit) and reconciles the drain —
        // both inside the same tick() call, on the worker thread, before join() returns.
        assertThat(engine.reservedCount("echo")).isZero();
        assertThat(registry.getMeters())
                .as("meters must be retired once the drained generation's last reservation settles")
                .hasSize(baseline);
    }

    /**
     * Task 13b (issue #208): the migrated home of the dispatch-failure property that two retired
     * tests held on their own loops - {@code SchedulerResilienceTest
     * .dispatchExceptionRecordsSlotHoldAndReleasesTheAcquiredState} (async-queue) and
     * {@code SyncSchedulerDispatchExceptionTest.realDispatchFailure_releasesCapacitySlot}
     * (sync-queue). Both drove a per-module scheduler; both schedulers are gone, so the property
     * is pinned here instead, end to end on the product path: a submit that throws must release
     * the capacity lease and conclude the already-admitted execution as {@code DISPATCH_REJECTED},
     * leaving nothing pending.
     *
     * <p>The engine-level half of the same property - that the throwing submit is reported through
     * {@code EngineDispatch.rejected} with the task and the failure - is
     * {@code SchedulerEngineDispatchTest.aSubmitThatThrowsReturnsTheLeaseAndRejectsTheTask}. This
     * test supplies the other half: the real {@code ExecutionStore} as {@code QueueLifecycle}, so
     * the callout is proved to conclude the caller rather than only to have been made.
     */
    @Test
    void aFailingDispatchConcludesTheQueuedExecutionAsDispatchRejectedAndReleasesTheSlot() {
        FunctionCapacityRegistry capacityRegistry = new FunctionCapacityRegistry();
        PendingWorkStore store = new PendingWorkStore(8);
        RuntimeException failure = new IllegalStateException("transport is down");
        EngineDispatch dispatch = new EngineDispatch() {
            @Override
            public DispatchOwnership tryAcquire(SchedulingTicket ticket) {
                return capacityRegistry.tryAcquireLease(ticket.generation(), ignored -> { });
            }

            @Override
            public void submit(InvocationTask task) {
                throw failure;
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
            public void rejected(InvocationTask task, Throwable cause) {
                executionStore.rejected(task, cause);
            }
        };
        SchedulingStrategy strategy = new PerFunctionSchedulingStrategy();
        SchedulerEngine engine = new SchedulerEngine(store, new StrategyRegistry(List.of(strategy)),
                strategy.id(), dispatch, generation -> true,
                generation -> generation.equals(capacityRegistry.activeGeneration(generation.functionName())),
                Clock.systemUTC(), System::nanoTime);
        FunctionRegistrationListener listener = new SchedulerConfiguration()
                .schedulerCapacityGenerationListener(capacityRegistry, engine,
                        noSyncGateway(), testMetricsBinder());

        FunctionSpec spec = spec("echo");
        InvocationTask task = task("exec-rejected", spec);
        ExecutionRecord executionRecord = new ExecutionRecord(task.executionId(), task);

        listener.onRegister(spec);
        executionStore.put(executionRecord);
        SchedulingTicket ticket = new SchedulingTicket(
                new TicketId(task.executionId(), task.attempt()),
                capacityRegistry.activeGeneration("echo"), 0,
                Instant.now(), Instant.now(), null);
        assertThat(engine.enqueue(new PendingEntry(ticket, task))).isTrue();

        engine.tick();

        assertThat(capacityRegistry.inFlight("echo"))
                .as("the failed dispatch must not leak the capacity slot it acquired")
                .isZero();
        assertThat(store.pendingCount()).isZero();
        assertThat(executionRecord.state()).isEqualTo(ExecutionState.ERROR);
        assertThat(executionRecord.lastError().code()).isEqualTo("DISPATCH_REJECTED");
        assertThat(executionRecord.completion().isDone()).isTrue();
        assertThat(executionRecord.completion().join().success()).isFalse();
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch was never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static ObjectProvider<EngineSyncQueueGateway> noSyncGateway() {
        return new ObjectProvider<>() {
            @Override
            public EngineSyncQueueGateway getObject() {
                throw new NoSuchBeanDefinitionException(EngineSyncQueueGateway.class);
            }
        };
    }

    /** A throwaway binder/registry pair: this test exercises the listener's own drain/removal
     * behavior, not meter registration, so a real {@link WorkloadMetricsSource} is unnecessary. */
    private static WorkloadMetricsBinder testMetricsBinder() {
        WorkloadMetricsSource zeroSource = new WorkloadMetricsSource() {
            @Override public int queueDepth(String functionName) { return 0; }
            @Override public int inFlight(String functionName) { return 0; }
            @Override public int effectiveConcurrency(String functionName) { return 0; }
            @Override public int dispatchableBacklog(String functionName) { return 0; }
        };
        return new WorkloadMetricsBinder(new SimpleMeterRegistry(), zeroSource);
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
