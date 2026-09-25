package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.*;
import it.unimib.datai.nanofaas.controlplane.capacity.*;
import it.unimib.datai.nanofaas.controlplane.execution.*;
import it.unimib.datai.nanofaas.controlplane.scheduler.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExecutorBackedInvocationEnqueuerTest {
    private final Instant now = Instant.parse("2026-09-24T12:00:00Z");
    private final Clock clock = mock(Clock.class);
    private final AtomicReference<Instant> time = new AtomicReference<>(now);
    private final ScheduledExecutorService timer = mock(ScheduledExecutorService.class);
    private final ExecutorService executor = mock(ExecutorService.class);
    private final InvocationDispatch dispatch = mock(InvocationDispatch.class);
    private final FunctionCapacityRegistry capacity = spy(new FunctionCapacityRegistry());
    private final ExecutionStore executions = spy(new ExecutionStore());
    private final List<Runnable> timers = new ArrayList<>();
    private final List<Runnable> workers = new ArrayList<>();
    private final List<Long> delays = new ArrayList<>();
    private final ScheduledFuture<?> future = mock(ScheduledFuture.class);
    private final AtomicInteger rejected = new AtomicInteger();
    private final ExecutorBackedInvocationEnqueuer enqueuer;

    ExecutorBackedInvocationEnqueuerTest() {
        when(clock.instant()).thenAnswer(call -> time.get());
        doAnswer(call -> {
            timers.add(call.getArgument(0));
            delays.add(call.getArgument(1));
            return future;
        }).when(timer).schedule(any(Runnable.class), anyLong(), eq(TimeUnit.NANOSECONDS));
        doAnswer(call -> { workers.add(call.getArgument(0)); return null; }).when(executor).execute(any());
        capacity.register("fn", 1);
        enqueuer = new ExecutorBackedInvocationEnqueuer(dispatch, capacity, executor, timer, 1, clock, executions);
    }

    private InvocationTask task(String id) {
        FunctionSpec spec = new FunctionSpec("fn", "image", null, null, null,
                1000, 1, 1, 3, null, ExecutionMode.LOCAL, null, null, null);
        var task = new InvocationTask(id, "fn", spec, new InvocationRequest("in", null),
                null, null, now, 2, InvocationKind.SYNC);
        executions.put(new ExecutionRecord(id, task));
        return task;
    }

    private boolean enqueue(InvocationTask task) {
        return enqueuer.enqueue(task, now.plusSeconds(1), rejected::incrementAndGet);
    }

    @Test
    void delayHoldsNoLeaseAndDispatchesOnlyFromWorker() {
        assertThat(enqueue(task("e1"))).isTrue();
        assertThat(capacity.inFlight("fn")).isZero();
        assertThat(workers).isEmpty();
        time.set(now.plusSeconds(1));
        timers.getFirst().run();
        assertThat(capacity.inFlight("fn")).isZero();
        verifyNoInteractions(dispatch);
        workers.getFirst().run();
        verify(dispatch).dispatch(any());
        assertThat(capacity.inFlight("fn")).isEqualTo(1);
        assertThat(rejected).hasValue(0);
    }

    @Test
    void lateExecutorRejectionCallsCallbackOnce() {
        assertThat(enqueue(task("e1"))).isTrue();
        doThrow(new RejectedExecutionException("full")).when(executor).execute(any());
        time.set(now.plusSeconds(1));
        timers.getFirst().run();
        timers.getFirst().run();
        assertThat(rejected).hasValue(1);
        assertThat(capacity.inFlight("fn")).isZero();
        verifyNoInteractions(dispatch);
    }

    @Test
    void schedulingFailureReturnsFalseWithoutCallback() {
        doThrow(new RejectedExecutionException("closed")).when(timer)
                .schedule(any(Runnable.class), anyLong(), any());
        assertThat(enqueue(task("e1"))).isFalse();
        assertThat(rejected).hasValue(0);
    }

    @Test
    void reservationBoundsWaitingAndExecutorQueuedJobs() {
        assertThat(enqueue(task("e1"))).isTrue();
        assertThat(enqueue(task("e2"))).isFalse();
        time.set(now.plusSeconds(1));
        timers.getFirst().run();
        assertThat(enqueue(task("e3"))).isFalse();
        executions.getOrNull("e1").markSuccess("done");
        executions.settle(executions.getOrNull("e1"));
        assertThat(enqueue(task("e4"))).isTrue();
        workers.getFirst().run();
        verifyNoInteractions(dispatch);
    }

    @Test
    void capacityRefusalCallsCallbackWithoutDispatch() {
        DispatchOwnership occupied = capacity.tryAcquireLease("fn", 1);
        assertThat(enqueue(task("e1"))).isTrue();
        time.set(now.plusSeconds(1));
        timers.getFirst().run();
        workers.getFirst().run();
        assertThat(rejected).hasValue(1);
        verifyNoInteractions(dispatch);
        occupied.release();
    }

    @Test
    void backwardClockAtTimerAndWorkerRearms() {
        assertThat(enqueue(task("e1"))).isTrue();
        time.set(now.minusSeconds(1));
        timers.getFirst().run();
        assertThat(workers).isEmpty();
        assertThat(timers).hasSize(2);
        time.set(now.plusSeconds(1));
        timers.get(1).run();
        time.set(now);
        workers.getFirst().run();
        verifyNoInteractions(dispatch);
        assertThat(timers).hasSize(3);
    }

    @Test
    void farFutureUsesFiniteChunks() {
        assertThat(enqueuer.enqueue(task("e1"), Instant.MAX, rejected::incrementAndGet)).isTrue();
        assertThat(delays).containsExactly(TimeUnit.DAYS.toNanos(1));
    }

    @Test
    void removalCancelsAndReplacementCannotReviveJob() {
        assertThat(enqueue(task("e1"))).isTrue();
        enqueuer.onRemove("fn");
        capacity.remove("fn");
        capacity.register("fn", 1);
        time.set(now.plusSeconds(1));
        timers.getFirst().run();
        assertThat(rejected).hasValue(1);
        verify(future).cancel(false);
        verifyNoInteractions(dispatch);
    }

    @Test
    void generationChangeBetweenTimerAndWorkerRefuses() {
        assertThat(enqueue(task("e1"))).isTrue();
        time.set(now.plusSeconds(1));
        timers.getFirst().run();
        capacity.remove("fn");
        capacity.register("fn", 1);
        workers.getFirst().run();
        assertThat(rejected).hasValue(1);
        verifyNoInteractions(dispatch);
    }

    @Test
    void shutdownDuringFutureInstallationCancelsIt() {
        doAnswer(call -> { enqueuer.shutdown(); return future; }).when(timer)
                .schedule(any(Runnable.class), anyLong(), any());
        assertThat(enqueue(task("e1"))).isTrue();
        verify(future).cancel(false);
        assertThat(rejected).hasValue(1);
        assertThat(enqueue(task("e2"))).isFalse();
        verify(timer).shutdown();
        verify(executor).shutdown();
    }

    @Test
    void timerFiringBeforeScheduleReturnsSubmitsOnlyOnce() {
        doAnswer(call -> {
            time.set(now.plusSeconds(1));
            Runnable command = call.getArgument(0);
            command.run();
            command.run();
            return future;
        }).when(timer).schedule(any(Runnable.class), anyLong(), any());
        assertThat(enqueue(task("e1"))).isTrue();
        assertThat(workers).hasSize(1);
        workers.getFirst().run();
        verify(dispatch).dispatch(any());
    }

    @Test
    void synchronousDispatchFailureReleasesLeaseAndRejects() {
        doThrow(new IllegalStateException("failed")).when(dispatch).dispatch(any());
        assertThat(enqueue(task("e1"))).isTrue();
        time.set(now.plusSeconds(1));
        timers.getFirst().run();
        workers.getFirst().run();
        assertThat(rejected).hasValue(1);
        assertThat(capacity.inFlight("fn")).isZero();
    }
    @Test
    void shutdownDuringPostinsertCheckDoesNotReturnFalseAfterCallback() {
        InvocationTask task = task("e1");
        ExecutionRecord record = executions.getOrNull("e1");
        AtomicInteger lookups = new AtomicInteger();
        doAnswer(call -> {
            if (lookups.incrementAndGet() == 2) {
                enqueuer.shutdown();
                record.markSuccess("terminal");
            }
            return record;
        }).when(executions).getOrNull("e1");
        assertThat(enqueue(task)).isTrue();
        assertThat(rejected).hasValue(1);
        verifyNoInteractions(dispatch);
    }

    @Test
    void terminalCancellationReleasesQueuedInputOnceWithoutRejection() {
        InvocationTask task = spy(task("e1"));
        assertThat(enqueue(task)).isTrue();
        ExecutionRecord record = executions.getOrNull("e1");
        record.markSuccess("terminal");
        executions.settle(record);
        enqueuer.shutdown();
        verify(task).releaseQueuedInput();
        assertThat(rejected).hasValue(0);
        time.set(now.plusSeconds(1));
        timers.getFirst().run();
        verifyNoInteractions(dispatch);
    }

    @Test
    void cancellationDuringExecutorSubmissionKeepsTheBoundUntilAcceptance() {
        InvocationTask first = task("e1");
        InvocationTask second = task("e2");
        doAnswer(call -> {
            enqueuer.onRemove("fn");
            assertThat(enqueue(second)).isFalse();
            workers.add(call.getArgument(0));
            return null;
        }).when(executor).execute(any());
        assertThat(enqueue(first)).isTrue();
        time.set(now.plusSeconds(1));
        timers.getFirst().run();
        assertThat(enqueue(second)).isTrue();
        workers.getFirst().run();
        assertThat(rejected).hasValue(1);
        verifyNoInteractions(dispatch);
    }

    @Test
    void terminalBeforeAdmissionNeverSchedules() {
        InvocationTask task = task("e1");
        executions.getOrNull("e1").markSuccess("done");
        assertThat(enqueue(task)).isFalse();
        assertThat(timers).isEmpty();
        assertThat(rejected).hasValue(0);
    }

    @Test
    void cancellationAfterLeaseAcquisitionReleasesThatLease() {
        doAnswer(call -> {
            DispatchOwnership lease = (DispatchOwnership) call.callRealMethod();
            enqueuer.onRemove("fn");
            return lease;
        }).when(capacity).tryAcquireLease(any(FunctionGeneration.class), any());
        assertThat(enqueue(task("e1"))).isTrue();
        time.set(now.plusSeconds(1));
        timers.getFirst().run();
        workers.getFirst().run();
        assertThat(capacity.inFlight("fn")).isZero();
        assertThat(rejected).hasValue(1);
        verifyNoInteractions(dispatch);
    }

    @Test
    void executorExceptionAndErrorBothRejectAndReleaseInputOnce() {
        int index = 0;
        for (Throwable failure : List.of(new IllegalStateException("failed"), new AssertionError("failed"))) {
            InvocationTask task = spy(task("e" + index));
            doAnswer(call -> { throw failure; }).when(executor).execute(any());
            assertThat(enqueuer.enqueue(task, now.plusSeconds(1), () -> {
                task.releaseQueuedInput();
                rejected.incrementAndGet();
            })).isTrue();
            time.set(now.plusSeconds(1));
            timers.get(index).run();
            timers.get(index).run();
            verify(task).releaseQueuedInput();
            assertThat(rejected).hasValue(++index);
        }
        verifyNoInteractions(dispatch);
    }

    @Test
    void throwingRejectionCallbackCannotStrandOtherJobsAtShutdown() {
        var owner = new ExecutorBackedInvocationEnqueuer(dispatch, capacity, executor, timer, 2, clock, executions);
        InvocationTask first = spy(task("e1"));
        InvocationTask second = spy(task("e2"));
        Runnable refusal = () -> { rejected.incrementAndGet(); throw new IllegalStateException("callback failed"); };
        assertThat(owner.enqueue(first, now.plusSeconds(1), refusal)).isTrue();
        assertThat(owner.enqueue(second, now.plusSeconds(1), refusal)).isTrue();
        org.assertj.core.api.Assertions.assertThatCode(owner::shutdown).doesNotThrowAnyException();
        assertThat(rejected).hasValue(2);
        verify(first).releaseQueuedInput();
        verify(second).releaseQueuedInput();
        verify(future, times(2)).cancel(false);
        verify(timer).shutdown();
        verify(executor).shutdown();
    }

    @Test
    void rearmingWorkerKeepsEveryOverlappingExecutorSubmissionReserved() {
        InvocationTask first = task("e1");
        InvocationTask second = task("e2");
        AtomicInteger submissions = new AtomicInteger();
        AtomicReference<Boolean> acceptedDuringOuterSubmission = new AtomicReference<>();
        doAnswer(call -> {
            if (submissions.incrementAndGet() == 1) {
                time.set(now);
                ((Runnable) call.getArgument(0)).run();
                time.set(now.plusSeconds(1));
                timers.get(1).run();
                acceptedDuringOuterSubmission.set(enqueue(second));
            } else {
                enqueuer.onRemove("fn");
            }
            return null;
        }).when(executor).execute(any());
        assertThat(enqueue(first)).isTrue();
        time.set(now.plusSeconds(1));
        timers.getFirst().run();
        assertThat(acceptedDuringOuterSubmission.get()).isFalse();
        assertThat(enqueue(second)).isTrue();
        assertThat(rejected).hasValue(1);
        verifyNoInteractions(dispatch);
    }

}
