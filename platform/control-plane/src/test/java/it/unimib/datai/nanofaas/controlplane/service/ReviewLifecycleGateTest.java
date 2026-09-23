package it.unimib.datai.nanofaas.controlplane.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.common.model.*;
import it.unimib.datai.nanofaas.controlplane.capacity.*;
import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;
import it.unimib.datai.nanofaas.controlplane.dispatch.*;
import it.unimib.datai.nanofaas.controlplane.execution.*;
import it.unimib.datai.nanofaas.controlplane.queue.QueueFullException;
import it.unimib.datai.nanofaas.controlplane.scheduler.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReviewLifecycleGateTest {
    private static FunctionSpec spec(ExecutionMode mode, int concurrency) {
        return new FunctionSpec("fn", "img", List.of(), Map.of(), null, 10000,
                concurrency, 100, 0, "http://unused/invoke", mode, null, null, null);
    }
    private static ExecutionRecord newRecord(String id, FunctionSpec spec) {
        return new ExecutionRecord(id, new InvocationTask(id, "fn", spec,
                new InvocationRequest("payload", Map.of()), null, null, Instant.now(),
                1, InvocationKind.ASYNC));
    }
    private static ExecutionStore timedStore(AtomicLong clock) {
        return new ExecutionStore(new ExecutionStoreProperties(Duration.ofMinutes(5),
                Duration.ofMinutes(30), Duration.ofSeconds(30), 100, 100, 11600), clock::get);
    }
    private static void expire(AtomicLong clock, ExecutionStore store, ExecutionRecord executionRecord) {
        clock.set(Duration.ofMinutes(31).toNanos());
        store.inFlightCount();
        await().atMost(Duration.ofSeconds(3)).until(() -> executionRecord.completion().isDone());
    }
    private static void awaitLatch(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("latch timeout"); }
        catch (InterruptedException e) { throw new RuntimeException(e); }
    }

    @Test
    void metricFailureMustNotLeaveATerminalExecutionLiveWithPendingFuture() {
        var store = new ExecutionStore();
        Metrics metrics = new Metrics(new SimpleMeterRegistry()) {
            @Override public void success(String fn) { throw new IllegalStateException("metric failure"); }
        };
        var handler = new ExecutionCompletionHandler(store, null, mock(DispatcherRouter.class), metrics);
        var rec = newRecord("metrics", spec(ExecutionMode.LOCAL, 1));
        store.put(rec);
        handler.completeExecution(rec.executionId(), InvocationResult.success("done"));
        System.out.println("METRIC_FAILURE state=" + rec.state() + " sharedDone="
                + rec.completion().isDone() + " live=" + store.inFlightCount());
        assertThat(rec.completion()).isDone();
        assertThat(store.inFlightCount()).isZero();
    }

    @Test
    void lateOffloadCompletionMustUseTheAlreadySelectedResult() throws Exception {
        var store = new ExecutionStore();
        CountDownLatch firstMarked = new CountDownLatch(1), continueFirst = new CountDownLatch(1);
        Metrics metrics = new Metrics(new SimpleMeterRegistry()) {
            @Override public void success(String fn) {
                firstMarked.countDown(); awaitLatch(continueFirst); super.success(fn);
            }
        };
        var handler = new ExecutionCompletionHandler(store, null, mock(DispatcherRouter.class), metrics);
        var rec = newRecord("offload-race", spec(ExecutionMode.LOCAL, 1));
        store.put(rec);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = executor.submit(() -> handler.completeOffloadedExecution(
                    rec.executionId(), InvocationResult.success("first")));
            assertThat(firstMarked.await(3, TimeUnit.SECONDS)).isTrue();
            handler.completeOffloadedExecution(rec.executionId(), InvocationResult.success("late"));
            continueFirst.countDown(); first.get(3, TimeUnit.SECONDS);
            System.out.println("OFFLOAD_RACE shared=" + rec.completion().join().output()
                    + " archived=" + store.outcomeOf(rec.executionId()).output());
            assertThat(rec.completion().join().output())
                    .isEqualTo(store.outcomeOf(rec.executionId()).output());
        } finally { continueFirst.countDown(); executor.shutdownNow(); }
    }

    @Test
    void newQueuedCompletionMustNotReleaseAnOldGenerationsLease() {
        var capacity = new FunctionCapacityRegistry();
        var oldState = capacity.register("fn", 1);
        var oldLease = capacity.tryAcquireLease("fn", 1);
        capacity.remove("fn");
        var freshState = capacity.register("fn", 1);
        var freshLease = capacity.tryAcquireLease(freshState.generation(), ignored -> { });
        assertThat(freshLease).isNotNull();
        freshLease.release(); // new queue attempt finishes first
        System.out.println("GENERATION_RELEASE oldActive=" + oldState.inFlight()
                + " newActive=" + freshState.inFlight());
        oldLease.release(); // old completion cannot recover the consumed generation
        assertThat(freshState.inFlight()).isZero();
    }

    @Test
    void directDispatchMustObserveAnUpdatedConcurrency() {
        var capacity = new FunctionCapacityRegistry();
        var store = new ExecutionStore();
        var router = mock(DispatcherRouter.class);
        var first = CompletableFuture.completedFuture(DispatchResult.warm(InvocationResult.success("done")));
        var blocked = new CompletableFuture<DispatchResult>();
        when(router.dispatchLocal(any())).thenReturn(first, blocked, blocked);
        var handler = new ExecutionCompletionHandler(store, null, router,
                new Metrics(new SimpleMeterRegistry()), null, capacity);
        var warm = newRecord("warm", spec(ExecutionMode.LOCAL, 2)); store.put(warm);
        handler.dispatchDirect(warm.task());
        var a = newRecord("new-a", spec(ExecutionMode.LOCAL, 1)); store.put(a);
        var b = newRecord("new-b", spec(ExecutionMode.LOCAL, 1)); store.put(b);
        handler.dispatchDirect(a.task());
        var taskB = b.task();
        assertThatThrownBy(() -> handler.dispatchDirect(taskB))
                .isInstanceOf(QueueFullException.class);
        System.out.println("DIRECT_RECONFIG requested=1 inFlight=" + capacity.inFlight("fn"));
        try { assertThat(capacity.inFlight("fn")).isEqualTo(1); }
        finally { blocked.complete(DispatchResult.warm(InvocationResult.success("done"))); }
    }

    @Test
    void administrativeExpiryMustCancelAHandlePublishedAfterTheExpiry() throws Exception {
        AtomicLong clock = new AtomicLong(); var store = timedStore(clock);
        var capacity = new FunctionCapacityRegistry();
        CountDownLatch entered = new CountDownLatch(1), allowReturn = new CountDownLatch(1);
        var raw = new CompletableFuture<DispatchResult>();
        var router = mock(DispatcherRouter.class);
        when(router.dispatchExternal(any())).thenAnswer(inv -> {
            entered.countDown(); awaitLatch(allowReturn); return raw;
        });
        var handler = new ExecutionCompletionHandler(store, null, router,
                new Metrics(new SimpleMeterRegistry()), null, capacity);
        var rec = newRecord("publish-race", spec(ExecutionMode.EXTERNAL, 1)); store.put(rec);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> dispatch = executor.submit(() -> handler.dispatchDirect(rec.task()));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            expire(clock, store, rec);
            allowReturn.countDown(); dispatch.get(3, TimeUnit.SECONDS);
            System.out.println("HANDLE_AFTER_EXPIRY sharedDone=" + rec.completion().isDone()
                    + " rawCancelled=" + raw.isCancelled());
            assertThat(raw).isCancelled();
        } finally { allowReturn.countDown(); raw.cancel(true); executor.shutdownNow(); }
    }

    @Test
    void deploymentExpiryMustCancelTheUnderlyingTransportSubscription() {
        AtomicLong clock = new AtomicLong(); var store = timedStore(clock);
        AtomicBoolean cancelled = new AtomicBoolean();
        var transport = Mono.<DispatchResult>never().doOnCancel(() -> cancelled.set(true)).toFuture();
        var gate = mock(DeploymentWakeUpGate.class);
        when(gate.ensureReady(any())).thenReturn(CompletableFuture.completedFuture(null));
        var router = mock(DispatcherRouter.class);
        when(router.dispatchExternal(any())).thenReturn(transport);
        var handler = new ExecutionCompletionHandler(store, null, router,
                new Metrics(new SimpleMeterRegistry()), gate, new FunctionCapacityRegistry());
        var rec = newRecord("deployment-expiry", spec(ExecutionMode.DEPLOYMENT, 1)); store.put(rec);
        handler.dispatchDirect(rec.task());
        expire(clock, store, rec);
        System.out.println("DEPLOYMENT_EXPIRY sharedDone=" + rec.completion().isDone()
                + " transportCancelled=" + cancelled.get());
        try { assertThat(cancelled).isTrue(); }
        finally { transport.cancel(true); }
    }

    @Test
    void expiryBeforeDeploymentWakeupMustNotCancelSharedReadinessOrStartHttp() {
        AtomicLong clock = new AtomicLong();
        var store = timedStore(clock);
        var readiness = new CompletableFuture<Void>();
        var gate = mock(DeploymentWakeUpGate.class);
        when(gate.ensureReady(any())).thenReturn(readiness);
        var router = mock(DispatcherRouter.class);
        var capacity = new FunctionCapacityRegistry();
        var handler = new ExecutionCompletionHandler(store, null, router,
                new Metrics(new SimpleMeterRegistry()), gate, capacity);
        var rec = newRecord("before-wakeup", spec(ExecutionMode.DEPLOYMENT, 1));
        store.put(rec);
        handler.dispatchDirect(rec.task());
        expire(clock, store, rec);
        assertThat(readiness).isNotDone();
        readiness.complete(null);
        verify(router, never()).dispatchExternal(any());
        assertThat(capacity.inFlight("fn")).isZero();
    }

    @Test
    void externalAttemptDeadlineMustDisposeItsTransport() {
        var store = new ExecutionStore();
        AtomicBoolean cancelled = new AtomicBoolean();
        var raw = Mono.<DispatchResult>never().doOnCancel(() -> cancelled.set(true)).toFuture();
        var router = mock(DispatcherRouter.class);
        when(router.dispatchExternal(any())).thenReturn(raw);
        var capacity = new FunctionCapacityRegistry();
        var handler = new ExecutionCompletionHandler(store, null, router,
                new Metrics(new SimpleMeterRegistry()), null, capacity);
        var spec = new FunctionSpec("fn", "img", List.of(), Map.of(), null, 50,
                1, 100, 0, "http://unused/invoke", ExecutionMode.EXTERNAL, null, null, null);
        var rec = newRecord("attempt-timeout", spec);
        store.put(rec);
        handler.dispatchDirect(rec.task());
        await().atMost(Duration.ofSeconds(3)).until(cancelled::get);
        assertThat(rec.completion().join().error().code()).isEqualTo("ATTEMPT_TIMEOUT");
        assertThat(capacity.inFlight("fn")).isZero();
    }

    @Test
    @SuppressWarnings("EmptyCatch") // Intentionally model local work that ignores cancellation interrupts.
    void administrativeExpiryMustKeepCapacityUntilNonCooperativeLocalWorkEnds() throws Exception {
        AtomicLong clock = new AtomicLong(); var store = timedStore(clock);
        var capacity = new FunctionCapacityRegistry();
        CountDownLatch started = new CountDownLatch(1), stop = new CountDownLatch(1);
        AtomicInteger actualRunning = new AtomicInteger();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        var work = CompletableFuture.supplyAsync(() -> {
            actualRunning.incrementAndGet(); started.countDown();
            while (stop.getCount() > 0) {
                try { stop.await(); } catch (InterruptedException ignored) { }
            }
            actualRunning.decrementAndGet();
            return DispatchResult.warm(InvocationResult.success("done"));
        }, worker);
        var router = mock(DispatcherRouter.class);
        when(router.dispatchLocal(any())).thenReturn(work);
        var handler = new ExecutionCompletionHandler(store, null, router,
                new Metrics(new SimpleMeterRegistry()), null, capacity);
        var rec = newRecord("local-expiry", spec(ExecutionMode.LOCAL, 1)); store.put(rec);
        try {
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            handler.dispatchDirect(rec.task());
            expire(clock, store, rec);
            System.out.println("LOCAL_EXPIRY actualRunning=" + actualRunning.get()
                    + " recordedCapacity=" + capacity.inFlight("fn"));
            assertThat(actualRunning.get()).isEqualTo(1);
            assertThat(capacity.inFlight("fn")).isEqualTo(1);
        } finally { stop.countDown(); worker.shutdownNow(); worker.awaitTermination(3, TimeUnit.SECONDS); }
    }
}
