package it.unimib.datai.nanofaas.modules.syncqueue;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatcherRouter;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionState;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistrationListener;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.TicketId;
import it.unimib.datai.nanofaas.controlplane.service.EngineSyncQueueGateway;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectedException;
import it.unimib.datai.nanofaas.controlplane.service.ExecutionCompletionHandler;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationDispatch;
import it.unimib.datai.nanofaas.controlplane.service.Metrics;
import it.unimib.datai.nanofaas.controlplane.service.SchedulerConfiguration;
import it.unimib.datai.nanofaas.controlplane.service.SchedulerLifecycleAdapter;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueConfigSource;
import it.unimib.datai.nanofaas.execution.SchedulerEngine;
import it.unimib.datai.nanofaas.execution.admission.SyncQueueAdmissionController;
import it.unimib.datai.nanofaas.execution.admission.WaitEstimator;
import it.unimib.datai.nanofaas.modules.syncqueue.config.SyncQueueProperties;
import it.unimib.datai.nanofaas.modules.syncqueue.sync.SyncQueueMetrics;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * A4 lifecycle of the sync queue in a real Spring context: the runtime flag decides the
 * path of NEW invocations, while work already admitted before a deactivation keeps
 * draining (and its retries are never abandoned). The scheduler is created from module
 * load, so a runtime activation needs no restart.
 *
 * <p>Determinism: the draining worker runs on its own thread but is woken by the queue's
 * work signal, so no fragile sleeps are needed. A queued task whose function has no
 * capacity yet cannot be dispatched - that is what lets the test hold a task across the
 * enabled=true -&gt; false transition and only then release it.
 */
class SyncQueueRuntimeLifecycleTest {

    @Configuration
    static class TestSupport {
        @Bean
        ExecutionStore executionStore() {
            return new ExecutionStore();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        InvocationDispatch invocationService() {
            return mock(InvocationDispatch.class);
        }

        /**
         * A spy over the real registry, so {@link #theRemovalFenceRejectsAnAdmissionThatRacesTheCapacityRemoval}
         * can enter the one interval in which a concurrent admission is fenced by the removal
         * fence alone: the inside of {@code capacityRegistry.remove}, which
         * {@code SchedulerConfiguration.onRemove} calls after raising the fence and before the
         * name's generation stops being active. A spy (rather than a hand-written
         * {@code DispatchCapacity} decorator) because the registry's {@code remove} is a
         * pass-through for every other test here: an unstubbed call delegates to the real object.
         */
        @Bean
        FunctionCapacityRegistry functionCapacityRegistry() {
            FunctionCapacityRegistry real = new FunctionCapacityRegistry();
            FunctionCapacityRegistry spy = spy(real);
            doAnswer(invocation -> {
                EngineSyncQueueGateway gateway = PROBE_GATEWAY.getAndSet(null);
                InvocationTask task = PROBE_TASK.getAndSet(null);
                if (gateway != null && task != null) {
                    PROBE_GENERATION_WAS_LIVE.set(real.activeGeneration(task.functionName()) != null);
                    try {
                        gateway.enqueueOrThrow(task);
                        PROBE_OUTCOME.set(null);
                    } catch (SyncQueueRejectedException rejected) {
                        PROBE_OUTCOME.set(rejected);
                    }
                }
                return invocation.callRealMethod();
            }).when(spy).remove(anyString());
            return spy;
        }

        /**
         * The same spy trick as the registry above, on the one collaborator the gateway calls
         * strictly <em>between</em> its two {@code removalFences.contains} checks: the admission
         * controller, reached from {@code doEnqueueOrThrow} after the first check and before the
         * second. That makes it the one place a test can observe — or arrange — what the two checks
         * see, and so attribute a refusal to one of them rather than to "a fence check".
         * {@link AdmissionProbe} picks the mode; {@link #PROBE_IN_ADMISSION} arms it. Delegates to
         * the real controller for every other test here.
         *
         * <p>{@code @Primary} under its own name rather than overriding the bean by name:
         * {@code SyncQueueConfiguration}'s own {@code syncQueueAdmissionController} is not
         * {@code @ConditionalOnMissingBean}, so a same-named definition is a
         * {@code BeanDefinitionOverrideException}, while a second candidate the gateway's
         * constructor prefers by {@code @Primary} is not.
         */
        @Bean
        @Primary
        SyncQueueAdmissionController spiedSyncQueueAdmissionController(SyncQueueConfigSource configSource,
                SyncQueueProperties props, WaitEstimator estimator) {
            SyncQueueAdmissionController real =
                    new SyncQueueAdmissionController(configSource, props.maxDepth(), estimator);
            SyncQueueAdmissionController spy = spy(real);
            doAnswer(invocation -> {
                EngineSyncQueueGateway gateway = PROBE_GATEWAY_IN_ADMISSION.getAndSet(null);
                AdmissionProbe probe = PROBE_IN_ADMISSION.getAndSet(null);
                if (gateway != null && probe != null) {
                    PROBE_ADMISSION_REACHED.set(true);
                    if (probe == AdmissionProbe.RAISE_FENCE) {
                        gateway.raiseRemovalFence(PROBE_FUNCTION);
                    }
                }
                return invocation.callRealMethod();
            }).when(spy).evaluate(eq(PROBE_FUNCTION), anyInt(), any());
            return spy;
        }
    }

    /** What the admission-check probe does when an armed admission reaches the check. */
    enum AdmissionProbe {
        /**
         * Record that the check was reached, and raise the fence. The fence was provably absent at
         * the first {@code removalFences.contains} check, so only the second can refuse the
         * admission.
         */
        RAISE_FENCE,
        /**
         * Record that the check was reached and touch nothing. A refusal that happens with this
         * mode armed therefore happened <em>before</em> the admission check was consulted, which
         * leaves the first {@code removalFences.contains} check as the only one that could have
         * made it.
         */
        OBSERVE_ONLY
    }

    /** The probe's handshake with the registry spy above; set and cleared by the one test that
     * uses it, so every other test in this class drives an unstubbed pass-through registry. */
    private static final AtomicReference<EngineSyncQueueGateway> PROBE_GATEWAY = new AtomicReference<>();
    private static final AtomicReference<InvocationTask> PROBE_TASK = new AtomicReference<>();
    private static final AtomicReference<Throwable> PROBE_OUTCOME = new AtomicReference<>();
    private static final AtomicBoolean PROBE_GENERATION_WAS_LIVE = new AtomicBoolean();
    /** The second probe's handshake with the admission-controller spy; see that bean's javadoc. */
    private static final AtomicReference<EngineSyncQueueGateway> PROBE_GATEWAY_IN_ADMISSION = new AtomicReference<>();
    private static final AtomicReference<AdmissionProbe> PROBE_IN_ADMISSION = new AtomicReference<>();
    private static final AtomicBoolean PROBE_ADMISSION_REACHED = new AtomicBoolean();
    /**
     * The one function the admission probe is armed for, and the only name its stubbed
     * {@code evaluate} matches: a probe that matched {@code anyString()} would be consumed by the
     * first admission to any function that reached the check, so the second test to admit for a
     * different name would silently probe nothing — a false green, or worse, a false red against
     * the other name's fence. Every test here registers this name through {@link #registerEcho}.
     */
    private static final String PROBE_FUNCTION = "echo";
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestSupport.class)
            .withConfiguration(AutoConfigurations.of(SyncQueueConfiguration.class, SchedulerConfiguration.class))
            .withPropertyValues(
                    "sync-queue.enabled=true",
                    "sync-queue.admission-enabled=false",
                    "sync-queue.max-depth=10",
                    "sync-queue.max-estimated-wait=1s",
                    "sync-queue.max-queue-wait=5s",
                    "sync-queue.retry-after-seconds=1",
                    "sync-queue.throughput-window=1s",
                    "sync-queue.per-function-min-samples=1");

    @Test
    void disablingAfterAdmissionDrainsQueuedWorkAndReactivationDispatchesAgain() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();

            // A4's successor: the engine's lifecycle adapter, not a per-module scheduler, is
            // what stays running regardless of sync-queue.enabled.
            SchedulerLifecycleAdapter lifecycleAdapter = context.getBean(SchedulerLifecycleAdapter.class);
            assertThat(lifecycleAdapter.isRunning()).isTrue();

            MutableSyncQueueConfigSource configSource = context.getBean(MutableSyncQueueConfigSource.class);
            EngineSyncQueueGateway gateway = context.getBean(EngineSyncQueueGateway.class);
            ExecutionStore store = context.getBean(ExecutionStore.class);
            FunctionCapacityRegistry capacityRegistry = context.getBean(FunctionCapacityRegistry.class);

            Queue<String> dispatched = new ConcurrentLinkedQueue<>();
            InvocationDispatch invocationService = context.getBean(InvocationDispatch.class);
            doAnswer(invocation -> {
                InvocationTask dispatchedTask = invocation.getArgument(0);
                dispatched.add(dispatchedTask.executionId());
                // The mock stands in for the whole execution lifecycle, which is what would
                // normally release the dispatch lease on completion; releasing it here keeps
                // the function's one slot usable for the next task in this test, exactly as a
                // real (fast) completion would.
                dispatchedTask.dispatchLease().release();
                return null;
            }).when(invocationService).dispatch(any(InvocationTask.class));

            FunctionSpec spec = new FunctionSpec("fn", "image", null, Map.of(), null,
                    1000, 1, 2, 3, null, ExecutionMode.LOCAL, null, null, null);
            // The function must be capacity-registered before it can be admitted at all (the
            // engine's ticket carries a FunctionGeneration) — register it up front, then hold
            // its one slot so the engine cannot dispatch the first task yet.
            context.getBean("schedulerCapacityGenerationListener", FunctionRegistrationListener.class).onRegister(spec);
            var heldLease = capacityRegistry.tryAcquireLease("fn", 1);
            assertThat(heldLease).isNotNull();

            // Task admitted while the queue is enabled, but the function's one slot is held,
            // so the engine cannot dispatch it: it stays queued across the flip.
            InvocationTask admitted = task("admitted-while-enabled", spec);
            store.put(new ExecutionRecord(admitted.executionId(), admitted));
            assertThat(configSource.syncQueueEnabled()).isTrue();
            assertThat(gateway.enqueue(admitted)).isTrue();

            // Deactivate the queue. NEW invocations now take the non-queue path (the core
            // coordinator reads this flag); the already-admitted task must keep draining.
            configSource.apply(Map.of(MutableSyncQueueConfigSource.KEY_ENABLED, false));
            assertThat(configSource.syncQueueEnabled()).isFalse();

            // Release the held slot: the engine must drain the admitted task even though the
            // queue was deactivated before it could be dispatched.
            heldLease.release();
            Awaitility.await("admitted work drains after deactivation")
                    .atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(dispatched).contains(admitted.executionId()));

            // Re-activation: the flag routes NEW invocations back into the queue and the
            // still-running engine dispatches them, again without a restart.
            configSource.apply(Map.of(MutableSyncQueueConfigSource.KEY_ENABLED, true));
            assertThat(configSource.syncQueueEnabled()).isTrue();

            InvocationTask reactivated = task("admitted-after-reactivation", spec);
            store.put(new ExecutionRecord(reactivated.executionId(), reactivated));
            assertThat(gateway.enqueue(reactivated)).isTrue();

            Awaitility.await("reactivated queue dispatches new work")
                    .atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(dispatched).contains(reactivated.executionId()));
        });
    }

    /**
     * A1 of issue #208's final review: the live removal fence. Before this test the two
     * {@code removalFences.contains} checks in {@code EngineSyncQueueGateway.doEnqueueOrThrow}
     * and the {@code raise}/{@code clear} pair called from
     * {@code SchedulerConfiguration.schedulerCapacityGenerationListener} executed on no test path
     * at all — the two classes that build the real listener stub the gateway out.
     *
     * <p>What this pins is the observable contract of the listener: a removed function admits
     * nothing, and a re-registered one admits again. It is deliberately NOT presented as proof
     * that the fence is what refuses the concurrent admission: after {@code onRemove} returns,
     * {@code capacityRegistry} no longer has an active generation for the name either, so that
     * refusal has two possible causes. The test below is the one that isolates the fence.
     */
    @Test
    void aRemovalRejectsSyncAdmissionAndReRegistrationAcceptsItAgain() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            FunctionRegistrationListener listener = context.getBean(
                    "schedulerCapacityGenerationListener", FunctionRegistrationListener.class);
            EngineSyncQueueGateway gateway = context.getBean(EngineSyncQueueGateway.class);
            ExecutionStore store = context.getBean(ExecutionStore.class);
            FunctionSpec spec = new FunctionSpec("echo", "image", null, Map.of(), null,
                    1000, 1, 10, 3, null, ExecutionMode.LOCAL, null, null, null);

            listener.onRegister(spec);
            InvocationTask admitted = task("admitted-before-removal", spec);
            store.put(new ExecutionRecord(admitted.executionId(), admitted));
            assertThatCode(() -> gateway.enqueueOrThrow(admitted)).doesNotThrowAnyException();

            listener.onRemove(spec.name());
            InvocationTask duringRemoval = task("admitted-during-removal", spec);
            store.put(new ExecutionRecord(duringRemoval.executionId(), duringRemoval));
            assertThatThrownBy(() -> gateway.enqueueOrThrow(duringRemoval))
                    .isInstanceOf(SyncQueueRejectedException.class);

            listener.onRegister(spec);
            InvocationTask afterReRegistration = task("admitted-after-reregistration", spec);
            store.put(new ExecutionRecord(afterReRegistration.executionId(), afterReRegistration));
            assertThatCode(() -> gateway.enqueueOrThrow(afterReRegistration))
                    .doesNotThrowAnyException();
        });
    }

    /**
     * The removal fence itself, on the live listener path and with the rejection attributable to
     * it. The registry spy runs an admission from inside {@code capacityRegistry.remove} — the
     * one instant {@code SchedulerConfiguration.onRemove} reaches AFTER raising the fence and
     * BEFORE the name's generation stops being active — and asserts that this admission, which no
     * other check can refuse, is refused.
     *
     * <p>Non-vacuity: {@code PROBE_GENERATION_WAS_LIVE} is asserted alongside the rejection, so a
     * green run cannot be explained by the generation having already been retired. Drop
     * {@code raiseRemovalFence} from {@code onRemove} and this test goes red with a null outcome.
     *
     * <p>What it does <em>not</em> attribute, stated because the obvious reading is wrong: the
     * fence is raised before this admission starts and stays raised, so as long as <em>either</em>
     * {@code removalFences.contains} check is in place the admission is still refused. This test
     * goes red only when both are removed, so it pins the removal fence as a whole and neither
     * check specifically. Each check has its own probe:
     * {@link #theFirstRemovalFenceCheckRefusesAnAdmissionThatStartsFenced} and
     * {@link #theSecondRemovalFenceCheckRejectsAFenceRaisedWhileTheAdmissionIsInFlight}. Removing
     * the first check alone, or the second alone, leaves this test green and turns exactly one of
     * those two red — which is the pairing that makes each check separately falsifiable.
     */
    @Test
    void theRemovalFenceRejectsAnAdmissionThatRacesTheCapacityRemoval() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            FunctionRegistrationListener listener = context.getBean(
                    "schedulerCapacityGenerationListener", FunctionRegistrationListener.class);
            EngineSyncQueueGateway gateway = context.getBean(EngineSyncQueueGateway.class);
            ExecutionStore store = context.getBean(ExecutionStore.class);
            FunctionCapacityRegistry capacityRegistry = context.getBean(FunctionCapacityRegistry.class);
            FunctionSpec spec = new FunctionSpec("echo", "image", null, Map.of(), null,
                    1000, 1, 10, 3, null, ExecutionMode.LOCAL, null, null, null);
            listener.onRegister(spec);

            InvocationTask racing = task("admitted-in-the-removal-window", spec);
            store.put(new ExecutionRecord(racing.executionId(), racing));
            PROBE_TASK.set(racing);
            PROBE_GATEWAY.set(gateway);
            PROBE_OUTCOME.set(null);
            PROBE_GENERATION_WAS_LIVE.set(false);
            try {
                listener.onRemove(spec.name());
            } finally {
                PROBE_GATEWAY.set(null);
                PROBE_TASK.set(null);
            }

            assertThat(PROBE_GENERATION_WAS_LIVE)
                    .as("the probe must run while the name's generation is still active, "
                            + "otherwise the rejection proves nothing about the fence")
                    .isTrue();
            assertThat(capacityRegistry.activeGeneration(spec.name())).isNull();
            assertThat(PROBE_OUTCOME.get())
                    .as("an admission racing the removal must be refused by the removal fence")
                    .isInstanceOf(SyncQueueRejectedException.class);
        });
    }

    /**
     * The <em>second</em> {@code removalFences.contains} check, which the probe above cannot
     * separate from the first: that one admits after {@code raiseRemovalFence} has already run and
     * the fence stays raised, so removing <em>either</em> check alone still leaves it refused — and
     * so leaves it green.
     *
     * <p>This probe forces the other ordering. The fence is raised from inside
     * {@code SyncQueueAdmissionController.evaluate}, which {@code doEnqueueOrThrow} calls strictly
     * between its two checks — so the fence is provably absent when the first check runs, and the
     * second check is the only one that can refuse this admission. Everything after it would have
     * accepted: the name's generation is still active and the queue is below its cap, which the
     * assertions below check rather than assume. Remove the second check and this admission is
     * admitted, the rejection never happens, and the test goes red.
     */
    @Test
    void theSecondRemovalFenceCheckRejectsAFenceRaisedWhileTheAdmissionIsInFlight() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            EngineSyncQueueGateway gateway = context.getBean(EngineSyncQueueGateway.class);
            ExecutionStore store = context.getBean(ExecutionStore.class);
            FunctionCapacityRegistry capacityRegistry = context.getBean(FunctionCapacityRegistry.class);
            FunctionSpec spec = registerEcho(context);

            InvocationTask task = task("admitted-while-the-fence-is-raised", spec);
            store.put(new ExecutionRecord(task.executionId(), task));
            armAdmissionProbe(gateway, AdmissionProbe.RAISE_FENCE);
            try {
                assertThatThrownBy(() -> gateway.enqueueOrThrow(task))
                        .as("a fence raised while the admission is in flight must be refused")
                        .isInstanceOf(SyncQueueRejectedException.class);
            } finally {
                clearAdmissionProbe();
            }

            assertThat(PROBE_ADMISSION_REACHED)
                    .as("the fence must be raised from inside the admission check, i.e. after the "
                            + "first removalFences check and before the second, otherwise this "
                            + "test proves nothing about the second check")
                    .isTrue();
            assertThat(capacityRegistry.activeGeneration(spec.name()))
                    .as("nothing was removed here — only the fence was raised — so the refusal "
                            + "cannot have come from the generation check")
                    .isNotNull();
        });
    }

    /**
     * The <em>first</em> {@code removalFences.contains} check, on the ordering that leaves it as
     * the only guard: a fence raised before the admission starts, and still raised when the first
     * check runs.
     *
     * <p>No probe can raise that fence from "between the checks" — the first check is the refusal
     * point, so a refusal there never reaches any later collaborator. What the admission-check
     * probe can do is prove <em>where</em> the refusal happened: it is armed in {@link
     * AdmissionProbe#OBSERVE_ONLY}, so {@code PROBE_ADMISSION_REACHED} being false means the
     * admission was refused before the controller was consulted, and the first check is the only
     * check upstream of it. Non-vacuity comes from the rest of the admission being viable: the
     * name's generation is active and the same function admits again once the fence is cleared.
     *
     * <p>Remove the first check and the flow reaches the controller — the probe fires and the
     * admission is instead refused by the second check — so the assertion below goes red.
     */
    @Test
    void theFirstRemovalFenceCheckRefusesAnAdmissionThatStartsFenced() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            EngineSyncQueueGateway gateway = context.getBean(EngineSyncQueueGateway.class);
            ExecutionStore store = context.getBean(ExecutionStore.class);
            FunctionCapacityRegistry capacityRegistry = context.getBean(FunctionCapacityRegistry.class);
            FunctionSpec spec = registerEcho(context);
            gateway.raiseRemovalFence(spec.name());

            InvocationTask fenced = task("admitted-while-the-fence-is-raised", spec);
            store.put(new ExecutionRecord(fenced.executionId(), fenced));
            armAdmissionProbe(gateway, AdmissionProbe.OBSERVE_ONLY);
            try {
                assertThatThrownBy(() -> gateway.enqueueOrThrow(fenced))
                        .as("an admission that starts under a raised fence must be refused")
                        .isInstanceOf(SyncQueueRejectedException.class);
            } finally {
                clearAdmissionProbe();
            }

            assertThat(PROBE_ADMISSION_REACHED)
                    .as("the refusal came before the admission check was consulted, so the first "
                            + "removalFences check is the one that made it")
                    .isFalse();
            assertThat(capacityRegistry.activeGeneration(spec.name()))
                    .as("the generation is active, so the refusal cannot have come from the "
                            + "generation check that the first fence check precedes")
                    .isNotNull();

            // The fence really was the only obstacle: clear it and the same function admits.
            gateway.clearRemovalFence(spec.name());
            InvocationTask afterwards = task("admitted-after-the-fence-is-cleared", spec);
            store.put(new ExecutionRecord(afterwards.executionId(), afterwards));
            assertThatCode(() -> gateway.enqueueOrThrow(afterwards)).doesNotThrowAnyException();
        });
    }

    @ParameterizedTest(name = "removal during generation read = {0}")
    @ValueSource(booleans = {false, true})
    void removalSettlesSyncAdmissionEvenAfterTheGatewayReadsTheGeneration(boolean duringGenerationRead) {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            // Hold dispatch and expiry while arranging the admission/removal ordering.
            context.getBean(SchedulerLifecycleAdapter.class).stop();
            FunctionRegistrationListener listener = context.getBean(
                    "schedulerCapacityGenerationListener", FunctionRegistrationListener.class);
            EngineSyncQueueGateway gateway = context.getBean(EngineSyncQueueGateway.class);
            FunctionCapacityRegistry capacity = context.getBean(FunctionCapacityRegistry.class);
            SchedulerEngine engine = context.getBean(SchedulerEngine.class);
            ExecutionStore store = context.getBean(ExecutionStore.class);
            FunctionSpec spec = registerEcho(context);
            FunctionRegistrationListener metricsListener = context.getBean(
                    "syncQueueMetricsLifecycleListener", FunctionRegistrationListener.class);
            metricsListener.onRegister(spec);
            InvocationTask task = task("admission-racing-removal", spec);
            ExecutionRecord record = new ExecutionRecord(task.executionId(), task);
            store.put(record);

            AtomicBoolean armed = new AtomicBoolean(duringGenerationRead);
            AtomicBoolean removalCompleted = new AtomicBoolean();
            doAnswer(invocation -> {
                FunctionGeneration observed = (FunctionGeneration) invocation.callRealMethod();
                if (armed.compareAndSet(true, false)) {
                    assertThat(observed).isNotNull();
                    // Finish the real removal drain, then return the generation captured before it.
                    listener.onRemove(spec.name());
                    metricsListener.onRemove(spec.name());
                    removalCompleted.set(true);
                }
                return observed;
            }).when(capacity).activeGeneration(spec.name());

            assertThat(gateway.enqueue(task)).isEqualTo(!duringGenerationRead);
            if (!duringGenerationRead) {
                // Control: the same lifecycle must settle a ticket admitted before removal.
                listener.onRemove(spec.name());
                metricsListener.onRemove(spec.name());
                removalCompleted.set(true);
            }

            assertThat(removalCompleted).isTrue();
            assertThat(capacity.activeGeneration(spec.name())).isNull();
            assertAll(
                    () -> assertThat(engine.reservedCount(spec.name())).isZero(),
                    () -> assertThat(record.state()).isEqualTo(ExecutionState.ERROR),
                    () -> assertThat(record.completion().isDone()).isTrue(),
                    () -> assertThat(gateway.settleIfSyncOrigin(spec.name(),
                            new TicketId(task.executionId(), task.attempt()))).isFalse(),
                    () -> assertThat(context.getBean(MeterRegistry.class).get("sync_queue_depth")
                            .tag("function", "").gauge().value()).isZero());
        });
    }

    private static FunctionSpec registerEcho(org.springframework.context.ApplicationContext context) {
        FunctionRegistrationListener listener = context.getBean(
                "schedulerCapacityGenerationListener", FunctionRegistrationListener.class);
        FunctionSpec spec = new FunctionSpec(PROBE_FUNCTION, "image", null, Map.of(), null,
                1000, 1, 10, 3, null, ExecutionMode.LOCAL, null, null, null);
        listener.onRegister(spec);
        return spec;
    }

    private static void armAdmissionProbe(EngineSyncQueueGateway gateway, AdmissionProbe probe) {
        PROBE_GATEWAY_IN_ADMISSION.set(gateway);
        PROBE_IN_ADMISSION.set(probe);
        PROBE_ADMISSION_REACHED.set(false);
    }

    private static void clearAdmissionProbe() {
        PROBE_GATEWAY_IN_ADMISSION.set(null);
        PROBE_IN_ADMISSION.set(null);
    }

    private static InvocationTask task(String executionId, FunctionSpec spec) {
        return new InvocationTask(executionId, spec.name(), spec,
                new InvocationRequest("payload", Map.of()), null, null, Instant.now(), 1, InvocationKind.SYNC);
    }

    /**
     * A4: a retry of work that was admitted while the queue was enabled must keep being
     * drained after a runtime deactivation, not be abandoned. Runs on the real engine: the
     * first attempt disables admission before the real completion handler queues its retry.
     */
    @Test
    void retryOfAdmittedWorkIsNotAbandonedAfterRuntimeDeactivation() {
        runner.withPropertyValues("nanofaas.admission.profile=SYNC_QUEUE").run(context -> {
            context.getBean(SchedulerLifecycleAdapter.class).stop();
            FunctionSpec spec = spec(1);
            context.getBean("schedulerCapacityGenerationListener", FunctionRegistrationListener.class)
                    .onRegister(spec);
            context.getBean("syncQueueMetricsLifecycleListener", FunctionRegistrationListener.class)
                    .onRegister(spec);
            var engine = context.getBean(SchedulerEngine.class);
            var enqueuer = context.getBean(
                    it.unimib.datai.nanofaas.controlplane.service.EngineInvocationEnqueuer.class);
            var config = context.getBean(MutableSyncQueueConfigSource.class);
            var store = context.getBean(ExecutionStore.class);
            var attempts = new AtomicInteger();
            DispatcherRouter router = mock(DispatcherRouter.class);
            when(router.dispatchLocal(any())).thenAnswer(invocation -> {
                if (attempts.incrementAndGet() == 1) {
                    config.apply(Map.of(MutableSyncQueueConfigSource.KEY_ENABLED, false));
                    return CompletableFuture.completedFuture(
                            DispatchResult.warm(InvocationResult.error("ERROR", "attempt 1 failed")));
                }
                return CompletableFuture.completedFuture(DispatchResult.warm(InvocationResult.success("ok")));
            });
            var handler = new ExecutionCompletionHandler(store, enqueuer, router,
                    new Metrics(context.getBean(MeterRegistry.class)));
            doAnswer(invocation -> {
                handler.dispatch(invocation.getArgument(0));
                return null;
            }).when(context.getBean(InvocationDispatch.class)).dispatch(any(InvocationTask.class));
            InvocationTask admitted = task("retry-after-disable", spec);
            ExecutionRecord record = new ExecutionRecord(admitted.executionId(), admitted);
            store.put(record);
            assertThat(enqueuer.enqueue(admitted)).isTrue();
            for (int i = 0; i < 3 && !record.completion().isDone(); i++) {
                engine.tick();
            }
            assertThat(record.completion().isDone()).isTrue();
            assertThat(record.completion().join().success()).isTrue();
            assertThat(record.state()).isEqualTo(ExecutionState.SUCCESS);
            assertThat(attempts.get()).isEqualTo(2);
            assertThat(engine.reservedCount(spec.name())).isZero();
        });
    }

    private static FunctionSpec spec(int maxRetries) {
        return new FunctionSpec("fn", "image", null, Map.of(), null,
                1000, 1, 10, maxRetries, null, ExecutionMode.LOCAL, null, null, null);
    }
}
