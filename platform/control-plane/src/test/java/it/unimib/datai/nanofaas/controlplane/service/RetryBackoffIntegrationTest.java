package it.unimib.datai.nanofaas.controlplane.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.*;
import it.unimib.datai.nanofaas.controlplane.capacity.*;
import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;
import it.unimib.datai.nanofaas.controlplane.config.RetryProperties;
import it.unimib.datai.nanofaas.controlplane.dispatch.*;
import it.unimib.datai.nanofaas.controlplane.execution.*;
import it.unimib.datai.nanofaas.controlplane.scheduler.*;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueConfigSource;
import it.unimib.datai.nanofaas.execution.*;
import it.unimib.datai.nanofaas.execution.admission.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real HTTP, facade, coordinator and admission owners, driven without scheduler sleeps. */
class RetryBackoffIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

    static Stream<String> profiles() {
        var profiles = new ArrayList<>(List.of("direct"));
        for (String name : List.of("it.unimib.datai.nanofaas.modules.asyncqueue.PerFunctionSchedulingStrategy",
                "it.unimib.datai.nanofaas.modules.syncqueue.SharedQueueSchedulingStrategy")) {
            try { Class.forName(name); profiles.add(name); }
            catch (ClassNotFoundException absentModule) { /* This artifact excludes that strategy. */ }
        }
        return profiles.stream();
    }

    @ParameterizedTest(name = "hint recovery: {0}")
    @MethodSource("profiles")
    void retryHonorsHintAndPreservesIdentity(String profile) throws Exception {
        try (var f = new Fixture(profile, Duration.ofSeconds(30))) {
            f.server.enqueue(busy());
            f.server.enqueue(ok());
            f.start();
            assertThat(f.server.takeRequest(5, TimeUnit.SECONDS).getHeader("X-Dispatch-Attempt")).isEqualTo("1");
            assertThat(f.publications.poll(5, TimeUnit.SECONDS)).isEqualTo(NOW.plusSeconds(1));
            assertThat(f.capacity.inFlight("fn")).isZero();
            f.advance(NOW.plusMillis(999));
            assertThat(f.server.getRequestCount()).isEqualTo(1);
            f.advance(NOW.plusSeconds(1));
            var second = f.server.takeRequest(5, TimeUnit.SECONDS);
            assertThat(second).isNotNull();
            assertThat(second.getHeader("X-Dispatch-Attempt")).isEqualTo("2");
            assertThat(second.getHeader("X-Execution-Id")).isEqualTo("e1");
            assertThat(f.record.completion().get(5, TimeUnit.SECONDS).success()).isTrue();
            assertThat(f.meters.get("function_retry_total").tag("function", "fn").counter().count()).isEqualTo(1);
        }
    }

    @ParameterizedTest(name = "attempt budget: {0}")
    @MethodSource("profiles")
    void persistentFailureStopsAfterFourAttempts(String profile) throws Exception {
        try (var f = new Fixture(profile, Duration.ofSeconds(30))) {
            for (int i = 0; i < 4; i++) f.server.enqueue(busy());
            f.start();
            for (int attempt = 1; attempt <= 3; attempt++) {
                assertThat(f.publications.poll(5, TimeUnit.SECONDS)).isEqualTo(NOW.plusSeconds(attempt));
                f.advance(NOW.plusSeconds(attempt));
            }
            assertThat(f.record.completion().get(5, TimeUnit.SECONDS).success()).isFalse();
            assertThat(f.server.getRequestCount()).isEqualTo(4);
            assertThat(f.record.task().attempt()).isEqualTo(4);
        }
    }

    @ParameterizedTest(name = "function envelope: {0}")
    @MethodSource("profiles")
    void functionSelected429IsOneSuccessfulEnvelope(String profile) throws Exception {
        try (var f = new Fixture(profile, Duration.ofSeconds(30))) {
            f.server.enqueue(busy().addHeader("X-NanoFaaS-Function-Status", "true").setBody("{\"ok\":true}"));
            f.start();
            assertThat(f.record.completion().get(5, TimeUnit.SECONDS).success()).isTrue();
            assertThat(f.publications).isEmpty();
            assertThat(f.server.getRequestCount()).isEqualTo(1);
        }
    }

    @ParameterizedTest(name = "expiry: {0}")
    @MethodSource("profiles")
    void administrativeExpiryRetiresWaitingRetry(String profile) throws Exception {
        try (var f = new Fixture(profile, Duration.ofSeconds(30))) {
            f.server.enqueue(busy());
            f.start();
            assertThat(f.publications.poll(5, TimeUnit.SECONDS)).isNotNull();
            f.ticker.set(Duration.ofMinutes(2).toNanos());
            f.store.inFlightCount();
            assertThat(f.record.completion().get(5, TimeUnit.SECONDS).error().code()).isEqualTo("EXECUTION_EXPIRED");
            f.advance(NOW.plusSeconds(1));
            assertThat(f.server.getRequestCount()).isEqualTo(1);
            assertThat(f.capacity.inFlight("fn")).isZero();
        }
    }

    @ParameterizedTest(name = "waiter replay: {0}")
    @MethodSource("profiles")
    void shortWaiterBudgetDoesNotCancelSharedCompletion(String profile) throws Exception {
        try (var f = new Fixture(profile, Duration.ofSeconds(30))) {
            // Factory-created records use the system clock. Keep the controlled HTTP hint well
            // beyond their local backoff, then drive the scheduler to the captured due instant.
            f.now.set(Instant.now().plusSeconds(60));
            f.server.enqueue(busy());
            f.server.enqueue(ok());
            var metrics = new Metrics(f.meters);
            var keys = new IdempotencyStore();
            var runtime = TestWaiterCapacity.runtime(f.store, keys, metrics, "fn");
            var coordinator = new ReactiveInvocationCoordinator(InvocationEnqueuer.noOp(), metrics,
                    null, null, f.handler, new InvocationResponseMapper(), runtime.waiters());
            var spec = f.task.functionSpec();
            var request = new InvocationRequest("keyed payload", null);
            var initial = runtime.factory().createOrReuseExecution("fn", spec, request,
                    "retry-key", null, InvocationKind.SYNC);
            var record = initial.executionRecord();
            var executionId = record.executionId();
            var longWaiter = coordinator.invoke(initial, spec, 10_000).toFuture();

            Instant due = f.publications.poll(5, TimeUnit.SECONDS);
            assertThat(due).isEqualTo(f.now.get().plusSeconds(1));
            assertThat(record.state()).isEqualTo(ExecutionState.QUEUED);
            assertThat(record.task().attempt()).isEqualTo(2);
            var waitingLookup = runtime.factory().createOrReuseExecution("fn", spec, request,
                    "retry-key", null, InvocationKind.SYNC);
            assertThat(waitingLookup.isNew()).isFalse();
            assertThat(waitingLookup.executionRecord()).isSameAs(record);
            var shortWaiter = coordinator.invoke(waitingLookup, spec, 1).block(Duration.ofSeconds(5));
            assertThat(shortWaiter.response().status()).isEqualTo("timeout");
            assertThat(shortWaiter.response().executionId()).isEqualTo(executionId);
            assertThat(record.state()).isEqualTo(ExecutionState.QUEUED);
            assertThat(record.completion()).isNotDone();
            assertThat(f.server.getRequestCount()).isEqualTo(1);

            f.advance(due);
            var result = longWaiter.get(5, TimeUnit.SECONDS);
            assertThat(result.response().status()).isEqualTo("success");
            assertThat(result.response().output()).isEqualTo("ok");
            // Future completion precedes archival; wait for the real settlement boundary
            // before testing the archived-key replay branch.
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(f.store.outcomeOf(executionId)).isNotNull();
                assertThat(f.store.getOrNull(executionId)).isNull();
            });
            var replayLookup = runtime.factory().createOrReuseExecution("fn", spec, request,
                    "retry-key", null, InvocationKind.SYNC);
            assertThat(replayLookup.isNew()).isFalse();
            assertThat(replayLookup.settledExecutionId()).isEqualTo(executionId);
            var replay = coordinator.invoke(replayLookup, spec, 1).block(Duration.ofSeconds(5));
            assertThat(replay.response().executionId()).isEqualTo(executionId);
            assertThat(replay.response().status()).isEqualTo("success");
            assertThat(replay.response().output()).isEqualTo(result.response().output());
            f.advance(due.plusSeconds(5));
            assertThat(f.server.getRequestCount()).isEqualTo(2);
            assertThat(f.publications).isEmpty();
        }
    }

    @ParameterizedTest(name = "connection recovery: {0}")
    @MethodSource("profiles")
    void connectionFailureRecoversAfterLocalBackoff(String profile) throws Exception {
        try (var f = new Fixture(profile, Duration.ofSeconds(30))) {
            f.server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
            f.server.enqueue(ok());
            f.start();
            Instant due = f.publications.poll(5, TimeUnit.SECONDS);
            assertThat(due).isBetween(NOW.plusMillis(50), NOW.plusMillis(100));
            f.advance(due.minusNanos(1));
            assertThat(f.record.task().attempt()).isEqualTo(2);
            assertThat(f.record.completion()).isNotDone();
            f.advance(due);
            assertThat(f.record.completion().get(5, TimeUnit.SECONDS).success()).isTrue();
        }
    }

    static Stream<String> syncProfiles() {
        return profiles().filter(profile -> profile.contains("syncqueue.Shared"));
    }

    @ParameterizedTest(name = "queue deadline: {0}", allowZeroInvocations = true)
    @MethodSource("syncProfiles")
    void syncQueueDeadlineCanWinBeforeHint(String profile) throws Exception {
        try (var f = new Fixture(profile, Duration.ofMillis(100))) {
            f.server.enqueue(busy());
            f.start();
            assertThat(f.publications.poll(5, TimeUnit.SECONDS)).isEqualTo(NOW.plusSeconds(1));
            f.advance(NOW.plusMillis(101));
            assertThat(f.record.completion().get(5, TimeUnit.SECONDS).success()).isFalse();
            f.advance(NOW.plusSeconds(1));
            assertThat(f.server.getRequestCount()).isEqualTo(1);
        }
    }

    private static MockResponse busy() {
        return new MockResponse().setResponseCode(429).addHeader("Retry-After", "1").setBody("busy");
    }

    private static MockResponse ok() {
        return new MockResponse().addHeader("Content-Type", "application/json").setBody("\"ok\"");
    }

    private static final class Fixture implements AutoCloseable {
        final MockWebServer server = new MockWebServer();
        final AtomicReference<Instant> now = new AtomicReference<>(NOW);
        final AtomicLong ticker = new AtomicLong();
        final Clock clock = mock(Clock.class);
        final FunctionCapacityRegistry capacity = new FunctionCapacityRegistry();
        final ExecutionStore store = new ExecutionStore(ExecutionStoreProperties.of(Duration.ofMinutes(5),
                Duration.ofMinutes(1), Duration.ofSeconds(30), 100), ticker::get);
        final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        final BlockingQueue<Instant> publications = new LinkedBlockingQueue<>();
        final Queue<Runnable> timers = new ConcurrentLinkedQueue<>();
        final Queue<Runnable> workers = new ConcurrentLinkedQueue<>();
        final SchedulerEngine engine;
        final ExecutorBackedInvocationEnqueuer direct;
        final ExecutionCompletionHandler handler;
        final InvocationTask task;
        final ExecutionRecord record;

        @SuppressWarnings("unchecked")
        Fixture(String profile, Duration queueWait) throws Exception {
            server.start();
            when(clock.instant()).thenAnswer(call -> now.get());
            capacity.register("fn", 1);
            var handlerRef = new AtomicReference<ExecutionCompletionHandler>();
            RetryScheduler retry;
            if (profile.equals("direct")) {
                engine = null;
                var timer = mock(ScheduledExecutorService.class);
                var worker = mock(ExecutorService.class);
                doAnswer(call -> { timers.add(call.getArgument(0)); return mock(ScheduledFuture.class); })
                        .when(timer).schedule(any(Runnable.class), anyLong(), eq(TimeUnit.NANOSECONDS));
                doAnswer(call -> { workers.add(call.getArgument(0)); return null; }).when(worker).execute(any());
                direct = new ExecutorBackedInvocationEnqueuer(t -> handlerRef.get().dispatch(t),
                        capacity, worker, timer, 264, clock, store);
                retry = direct;
            } else {
                direct = null;
                var strategy = (SchedulingStrategy) Class.forName(profile).getConstructor().newInstance();
                var pending = new PendingWorkStore(100);
                var dispatch = mock(EngineDispatch.class);
                when(dispatch.tryAcquire(any())).thenAnswer(call -> capacity.tryAcquireLease("fn", 1));
                doAnswer(call -> { handlerRef.get().dispatch(call.getArgument(0)); return null; })
                        .when(dispatch).submit(any());
                doAnswer(call -> { recordTimeout(store, call.getArgument(0)); return null; })
                        .when(dispatch).expired(any());
                engine = new SchedulerEngine(pending, new StrategyRegistry(List.of(strategy)), strategy.id(),
                        dispatch, generation -> true, generation -> generation.equals(capacity.activeGeneration("fn")),
                        clock, ticker::get);
                store.onTerminal(r -> engine.removeExecution(r.executionId()));
                ObjectProvider<SchedulerEngine> provider = mock(ObjectProvider.class);
                when(provider.getObject()).thenReturn(engine);
                ObjectProvider<EngineSyncQueueGateway> syncProvider = mock(ObjectProvider.class);
                var admission = profile.contains("syncqueue.Shared")
                        ? EngineInvocationEnqueuer.AdmissionProfile.SYNC_QUEUE
                        : EngineInvocationEnqueuer.AdmissionProfile.FUNCTION_QUEUE;
                var sequence = new AtomicLong();
                if (admission == EngineInvocationEnqueuer.AdmissionProfile.SYNC_QUEUE) {
                    var config = mock(SyncQueueConfigSource.class);
                    when(config.syncQueueMaxQueueWait()).thenReturn(queueWait);
                    var controller = mock(SyncQueueAdmissionController.class);
                    when(controller.evaluate(any(), anyInt(), any())).thenReturn(SyncQueueAdmissionResult.accepted(0));
                    var gateway = new EngineSyncQueueGateway(config, controller, mock(WaitEstimator.class),
                            provider, pending, capacity, sequence::incrementAndGet, admission, name -> {}, name -> {}, clock);
                    when(syncProvider.getIfAvailable()).thenReturn(gateway);
                }
                retry = new EngineInvocationEnqueuer(provider, capacity, sequence::incrementAndGet,
                        admission, true, syncProvider, clock, store);
            }
            // Package-private clock injection is intentionally confined to this integration fixture.
            var constructor = ExternalDispatcher.class.getDeclaredConstructor(WebClient.class, Clock.class);
            constructor.setAccessible(true);
            var external = constructor.newInstance(WebClient.create(), clock);
            var metrics = new Metrics(meters);
            metrics.registerFunction("fn");
            handler = new ExecutionCompletionHandler(store, (next, due, rejected) -> {
                boolean accepted = retry.enqueue(next, due, rejected);
                publications.add(due); // Barrier: adapter insertion has completed.
                return accepted;
            }, new DispatcherRouter(mock(LocalDispatcher.class), external), metrics,
                    null, capacity, new RetryProperties(Duration.ofMillis(100), Duration.ofSeconds(2)));
            handlerRef.set(handler);
            var spec = new FunctionSpec("fn", "image", null, null, null, 30000, 1, 10, 3,
                    server.url("/invoke").toString(), ExecutionMode.EXTERNAL, null, null, null);
            task = new InvocationTask("e1", "fn", spec, new InvocationRequest("in", null),
                    null, null, NOW, 1, InvocationKind.SYNC);
            record = new ExecutionRecord("e1", task, new TimeSource(now::get, ticker::get));
            store.put(record);
        }

        void start() { handler.dispatchDirect(task); }

        void advance(Instant time) {
            now.set(time);
            if (engine != null) engine.tick();
            else {
                int count = timers.size();
                for (int i = 0; i < count; i++) timers.remove().run();
                Runnable worker;
                while ((worker = workers.poll()) != null) worker.run();
            }
        }

        private static void recordTimeout(ExecutionStore store, InvocationTask task) {
            var record = store.getOrNull(task.executionId());
            if (record != null) { record.markTimeout(); store.settle(record); }
        }

        @Override public void close() throws Exception {
            if (engine != null) engine.close();
            if (direct != null) direct.shutdown();
            server.shutdown();
            meters.close();
        }
    }
}
