package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.capacity.DispatchOwnership;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistrationListener;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationDispatch;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.TicketId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Bounded retry ownership for the direct profile. Waiting jobs hold no dispatch capacity. */
final class ExecutorBackedInvocationEnqueuer implements RetryScheduler, FunctionRegistrationListener {
    private static final Logger log = LoggerFactory.getLogger(ExecutorBackedInvocationEnqueuer.class);
    private final InvocationDispatch dispatch;
    private final FunctionCapacityRegistry capacity;
    private final ExecutorService executor;
    private final ScheduledExecutorService timer;
    private final int maxOutstanding;
    private final Clock clock;
    private final ExecutionStore executions;
    private final Object lock = new Object();
    private final Map<TicketId, RetryJob> jobs = new HashMap<>();
    private boolean closed;

    ExecutorBackedInvocationEnqueuer(InvocationDispatch dispatch, FunctionCapacityRegistry capacity,
            ExecutorService executor, ScheduledExecutorService timer, int maxOutstanding, Clock clock,
            ExecutionStore executions) {
        if (maxOutstanding < 1) throw new IllegalArgumentException("maxOutstanding must be positive");
        this.dispatch = dispatch;
        this.capacity = capacity;
        this.executor = executor;
        this.timer = timer;
        this.maxOutstanding = maxOutstanding;
        this.clock = clock;
        this.executions = executions;
        executions.onExecutionGone((function, execution) -> cancelExecution(execution));
    }

    @Override
    public boolean enqueue(InvocationTask task, Instant notBefore, Runnable onRejected) {
        FunctionGeneration generation = capacity.activeGeneration(task.functionName());
        RetryJob job = new RetryJob(task, notBefore, generation, onRejected);
        if (!live(task, generation)) return false;
        synchronized (lock) {
            if (closed || jobs.size() >= maxOutstanding || jobs.containsKey(job.id)) return false;
            jobs.put(job.id, job);
        }
        // The store's terminal listener may have run just before the insertion above.
        if (!live(task, generation)) {
            return !retire(job, false);
        }
        return arm(job, true);
    }

    private boolean live(InvocationTask task, FunctionGeneration generation) {
        if (generation == null || !generation.equals(capacity.activeGeneration(task.functionName()))) return false;
        ExecutionRecord record = executions.getOrNull(task.executionId());
        if (record == null) return false;
        synchronized (record) {
            return !record.isTerminal() && record.task().attempt() == task.attempt()
                    // Legacy test records have no captured admission generation.
                    && (record.currentGeneration() == null || generation.equals(record.currentGeneration()));
        }
    }

    private boolean arm(RetryJob job, boolean initial) {
        long version;
        synchronized (lock) {
            if (job.state == State.RETIRED) return true;
            job.state = State.WAITING;
            version = ++job.timerVersion;
        }
        Duration remaining = Duration.between(clock.instant(), job.due);
        long delay = remaining.isNegative() ? 0 : remaining.compareTo(Duration.ofDays(1)) > 0
                ? TimeUnit.DAYS.toNanos(1) : remaining.toNanos();
        ScheduledFuture<?> future;
        try {
            future = timer.schedule(() -> timerFired(job, version), delay, TimeUnit.NANOSECONDS);
        } catch (RuntimeException | Error failure) { // Resource cleanup also applies to executor Errors.
            boolean owned = retire(job, !initial);
            return !owned;
        }
        synchronized (lock) {
            if (job.state != State.WAITING || job.timerVersion != version) future.cancel(false);
            else job.future = future;
        }
        return true;
    }

    private void timerFired(RetryJob job, long version) {
        synchronized (lock) {
            if (job.state != State.WAITING || job.timerVersion != version) return;
            job.state = State.QUEUED;
            job.future = null;
        }
        if (clock.instant().isBefore(job.due)) {
            arm(job, false);
            return;
        }
        synchronized (lock) {
            if (job.state == State.RETIRED || jobs.get(job.id) != job) return;
            job.submissions++;
        }
        try {
            executor.execute(() -> run(job));
        } catch (RuntimeException | Error failure) {
            retire(job, true);
        } finally {
            synchronized (lock) {
                job.submissions--;
                if (job.state == State.RETIRED && job.submissions == 0) jobs.remove(job.id, job);
            }
        }
    }

    private void run(RetryJob job) {
        InvocationTask task;
        synchronized (lock) {
            if (job.state != State.QUEUED) return;
            job.state = State.RUNNING;
            task = job.task;
        }
        if (clock.instant().isBefore(job.due)) {
            arm(job, false);
            return;
        }
        if (!live(task, job.generation)) {
            retire(job, true);
            return;
        }
        DispatchOwnership lease = capacity.tryAcquireLease(job.generation, ignored -> { });
        if (lease == null) {
            retire(job, true);
            return;
        }
        if (!live(task, job.generation)) {
            lease.release();
            retire(job, true);
            return;
        }
        Runnable rejected;
        boolean transferred;
        synchronized (lock) {
            transferred = job.state != State.RETIRED;
            rejected = job.onRejected;
            if (transferred) detach(job);
        }
        if (!transferred) {
            lease.release();
            return;
        }
        try {
            dispatch.dispatch(task.withDispatchLease(lease));
        } catch (RuntimeException | Error failure) {
            lease.release();
            rejectOrRelease(task, rejected);
        }
    }

    /** Detaches payload references while keeping a racing execute() call's reservation. */
    private void detach(RetryJob job) {
        job.state = State.RETIRED;
        job.task = null;
        job.onRejected = null;
        if (job.submissions == 0) jobs.remove(job.id, job);
    }

    private boolean retire(RetryJob job, boolean reject) {
        InvocationTask task;
        Runnable rejected;
        ScheduledFuture<?> future;
        synchronized (lock) {
            if (job.state == State.RETIRED) return false;
            task = job.task;
            rejected = job.onRejected;
            future = job.future;
            job.future = null;
            detach(job);
        }
        if (future != null) future.cancel(false);
        if (reject) rejectOrRelease(task, rejected);
        // Initial publication refusal leaves cleanup with the caller.
        return true;
    }

    private void rejectOrRelease(InvocationTask task, Runnable rejected) {
        ExecutionRecord record = executions.getOrNull(task.executionId());
        if (record == null || record.isTerminal() || record.task().attempt() != task.attempt()) {
            task.releaseQueuedInput();
        } else {
            try {
                rejected.run();
            } catch (RuntimeException | Error failure) {
                // One failed observer must not strand the other jobs during shutdown/removal.
                task.releaseQueuedInput();
                log.warn("Retry rejection callback failed for execution {}", task.executionId(), failure);
            }
        }
    }

    private void cancelExecution(String executionId) {
        RetryJob[] snapshot;
        synchronized (lock) { snapshot = jobs.values().toArray(RetryJob[]::new); }
        for (RetryJob job : snapshot) if (job.id.executionId().equals(executionId)) retire(job, true);
    }

    @Override
    public void onRegister(FunctionSpec spec) { /* A generation is captured per admitted execution. */ }

    @Override
    public void onRemove(String functionName) {
        RetryJob[] snapshot;
        synchronized (lock) { snapshot = jobs.values().toArray(RetryJob[]::new); }
        for (RetryJob job : snapshot) if (job.generation.functionName().equals(functionName)) retire(job, true);
    }

    void shutdown() {
        RetryJob[] snapshot;
        synchronized (lock) {
            closed = true;
            snapshot = jobs.values().toArray(RetryJob[]::new);
        }
        try {
            for (RetryJob job : snapshot) retire(job, true);
        } finally {
            timer.shutdown();
            executor.shutdown();
        }
    }

    private enum State { WAITING, QUEUED, RUNNING, RETIRED }

    private static final class RetryJob {
        final TicketId id;
        final Instant due;
        final FunctionGeneration generation;
        InvocationTask task;
        Runnable onRejected;
        ScheduledFuture<?> future;
        long timerVersion;
        int submissions;
        State state = State.WAITING;

        RetryJob(InvocationTask task, Instant due, FunctionGeneration generation, Runnable onRejected) {
            this.id = new TicketId(task.executionId(), task.attempt());
            this.task = task;
            this.due = due;
            this.generation = generation;
            this.onRejected = onRejected;
        }
    }
}
