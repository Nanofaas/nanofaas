package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentWakeUpCoordinator;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentTarget;
import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatus;
import it.unimib.datai.nanofaas.controlplane.registry.ManagedDeploymentCoordinator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * One function generation's wake-up, shared by every caller waiting on it: read the replicas, ask
 * for one if there are none (under the scale-down protection), poll until one is ready.
 *
 * <p>Its deadline is the latest of its callers': a caller that joins late extends the wake-up, its
 * timeout and its protection, and each earlier caller then times out on its own budget. The result
 * completes once; the wake-up then retires and, once its running callbacks return, leaves its gate
 * and releases its protection.
 */
@SuppressWarnings("FutureReturnValueIgnored") // result owns both lifecycle callbacks.
final class DeploymentWakeUp {
    static final String TIMEOUT = "DEPLOYMENT_WAKE_UP_TIMEOUT";

    /**
     * What a wake-up uses of its gate. {@code stopReason} is null while the generation may still wake,
     * the failure code otherwise; {@code drained} is told once the wake-up holds nothing any more.
     */
    record Context(ManagedDeploymentCoordinator replicas,
                   DeploymentWakeUpCoordinator protection,
                   Executor executor,
                   ScheduledExecutorService timers,
                   LongSupplier nanoTime,
                   Duration pollInterval,
                   Function<FunctionGeneration, String> stopReason,
                   BiConsumer<FunctionGeneration, DeploymentWakeUp> drained) {
    }

    private record Waiter(CompletableFuture<Void> view, long deadline) {
    }

    private final FunctionGeneration generation;
    private final ManagedDeploymentTarget target;
    /** The last reading saw the function ready: a failed fresh read falls back on it. */
    private final boolean lastReadingWasReady;
    private final Context context;
    private final CompletableFuture<Void> result = new CompletableFuture<>();

    // Guarded by this.
    /** The latest deadline of the callers waiting. */
    private long deadline = Long.MIN_VALUE;
    /**
     * Callers whose deadline is the wake-up's own: its timeout bounds them, so they need no timer
     * until a later caller extends the wake-up past them.
     */
    private final List<Waiter> boundedByTheWakeUp = new ArrayList<>();
    private ScheduledFuture<?> timeoutTask;
    private ScheduledFuture<?> pollTask;
    private long pollVersion;
    private DeploymentWakeUpCoordinator.WakeUpLease lease;
    private int runningCallbacks;
    private boolean retired;
    private boolean drained;

    DeploymentWakeUp(FunctionGeneration generation, ManagedDeploymentTarget target, boolean lastReadingWasReady,
                     Context context) {
        this.generation = generation;
        this.target = target;
        this.lastReadingWasReady = lastReadingWasReady;
        this.context = context;
        result.whenComplete((ignored, failure) -> retire());
    }

    FunctionGeneration generation() {
        return generation;
    }

    void start(long callerDeadline) {
        try {
            synchronized (this) {
                deadline = Math.max(deadline, callerDeadline);
                timeoutTask = scheduleTimeout();
                if (result.isDone()) timeoutTask.cancel(false);
            }
            submit(this::readAndWake);
        } catch (RuntimeException | Error failure) { // NOSONAR (java:S1181): owned resources must be released or failed on an Error too
            result.completeExceptionally(failure);
        }
    }

    /** A later caller's deadline: the wake-up, its timeout and its protection last until it. */
    void extendTo(long callerDeadline) {
        DeploymentWakeUpCoordinator.WakeUpLease protection;
        try {
            synchronized (this) {
                if (retired || callerDeadline <= deadline) return;
                deadline = callerDeadline;
                if (timeoutTask != null) {
                    timeoutTask.cancel(false);
                    timeoutTask = scheduleTimeout();
                }
                // The callers so far are now earlier than the wake-up: each gets its own timeout.
                for (Waiter waiter : boundedByTheWakeUp) {
                    timeOutOnItsOwn(waiter);
                }
                boundedByTheWakeUp.clear();
                protection = lease;
            }
            if (protection != null) protection.extendTo(callerDeadline);
        } catch (RuntimeException | Error failure) { // NOSONAR (java:S1181): owned resources must be released or failed on an Error too
            result.completeExceptionally(failure);
        }
    }

    /** What one caller sees: the wake-up's outcome, or its own timeout if that comes first. */
    CompletableFuture<Void> callerView(long callerDeadline) {
        CompletableFuture<Void> view = new CompletableFuture<>();
        result.whenComplete((ignored, failure) -> {
            if (failure != null) view.completeExceptionally(failure);
            else view.complete(null);
        });
        synchronized (this) {
            if (!retired) {
                Waiter waiter = new Waiter(view, callerDeadline);
                if (callerDeadline >= deadline) boundedByTheWakeUp.add(waiter);
                else timeOutOnItsOwn(waiter);
            }
        }
        return view;
    }

    void fail(String code) {
        result.completeExceptionally(new IllegalStateException(code));
    }

    // ---- The wake-up itself, on the gate's executor

    private void readAndWake() {
        if (!canContinue()) return;
        ReplicaStatus status;
        try {
            status = context.replicas().getFreshReplicaStatus(target);
        } catch (RuntimeException failure) {
            // A read that fails is not an answer. When the last reading saw the function ready,
            // nothing this control plane did has changed that since (a scale invalidates it): serve
            // on it. A function seen at zero fails fast, as no scale-up has been asked yet.
            if (lastReadingWasReady) result.complete(null);
            else result.completeExceptionally(failure);
            return;
        } catch (Error failure) { // NOSONAR (java:S1181): owned resources must be released or failed on an Error too
            result.completeExceptionally(failure);
            return;
        }
        try {
            if (!canContinue()) return;
            if (isReady(status)) {
                result.complete(null);
                return;
            }
            // The reading only spares the function's lock when replicas are already asked for; whether
            // to ask for one is decided under that lock, so a target raised since is never lowered.
            installLease(context.protection().protectAndScaleUp(generation, target, deadline(), () -> {
                if (status.desiredReplicas() == 0 && canContinue()) {
                    context.replicas().scaleUpTo(generation, target, 1);
                }
            }));
            schedulePoll();
        } catch (RuntimeException | Error failure) { // NOSONAR (java:S1181): owned resources must be released or failed on an Error too
            result.completeExceptionally(failure);
        }
    }

    private void poll() {
        if (!canContinue()) return;
        try {
            ReplicaStatus status = context.replicas().getFreshReplicaStatus(target);
            if (!canContinue()) return;
            if (isReady(status)) result.complete(null);
            else schedulePoll();
        } catch (RuntimeException failure) {
            // The scale-up is under way; a read that fails is not an answer: ask again until the deadline.
            schedulePoll();
        } catch (Error failure) { // NOSONAR (java:S1181): owned resources must be released or failed on an Error too
            result.completeExceptionally(failure);
        }
    }

    private static boolean isReady(ReplicaStatus status) {
        return status.desiredReplicas() > 0 && status.readyReplicas() > 0;
    }

    private void schedulePoll() {
        long remaining = deadline() - context.nanoTime().getAsLong();
        if (remaining <= 0) {
            fail(TIMEOUT);
            return;
        }
        try {
            synchronized (this) {
                if (retired) return;
                long version = ++pollVersion;
                ScheduledFuture<?> previous = pollTask;
                pollTask = context.timers().schedule(() -> pollScheduled(version),
                        Math.min(context.pollInterval().toNanos(), remaining), TimeUnit.NANOSECONDS);
                if (previous != null) previous.cancel(false);
            }
        } catch (RuntimeException | Error failure) { // NOSONAR (java:S1181): owned resources must be released or failed on an Error too
            result.completeExceptionally(failure);
        }
    }

    private void pollScheduled(long version) {
        synchronized (this) {
            if (retired || version != pollVersion) return;
            pollTask = null;
        }
        submit(this::poll);
    }

    private boolean canContinue() {
        if (result.isDone()) return false;
        String stopReason = context.stopReason().apply(generation);
        if (stopReason != null) {
            fail(stopReason);
            return false;
        }
        return true;
    }

    // ---- Timers, protection and draining (all guarded by this)

    private ScheduledFuture<?> scheduleTimeout() {
        return context.timers().schedule(() -> fail(TIMEOUT),
                Math.max(0, deadline - context.nanoTime().getAsLong()), TimeUnit.NANOSECONDS);
    }

    private void timeOutOnItsOwn(Waiter waiter) {
        try {
            ScheduledFuture<?> own = context.timers().schedule(
                    () -> waiter.view().completeExceptionally(new IllegalStateException(TIMEOUT)),
                    Math.max(0, waiter.deadline() - context.nanoTime().getAsLong()), TimeUnit.NANOSECONDS);
            waiter.view().whenComplete((ignored, failure) -> own.cancel(false));
        } catch (RuntimeException | Error failure) { // NOSONAR (java:S1181): owned resources must be released or failed on an Error too
            waiter.view().completeExceptionally(failure);
        }
    }

    private synchronized long deadline() {
        return deadline;
    }

    private void installLease(DeploymentWakeUpCoordinator.WakeUpLease acquired) {
        long latest;
        synchronized (this) {
            if (retired) {
                acquired.close();
                return;
            }
            lease = acquired;
            latest = deadline;
        }
        // A caller may have extended the wake-up while the lease was being taken.
        acquired.extendTo(latest);
    }

    private synchronized boolean callbackStarted() {
        if (retired) return false;
        runningCallbacks++;
        return true;
    }

    private synchronized void callbackFinished() {
        runningCallbacks--;
        drainIfPossible();
    }

    private synchronized void retire() {
        if (retired) return;
        retired = true;
        boundedByTheWakeUp.clear();
        if (timeoutTask != null) timeoutTask.cancel(false);
        if (pollTask != null) pollTask.cancel(false);
        drainIfPossible();
    }

    private void drainIfPossible() {
        if (!retired || drained || runningCallbacks != 0) return;
        drained = true;
        context.drained().accept(generation, this);
        DeploymentWakeUpCoordinator.WakeUpLease held = lease;
        lease = null;
        if (held != null) held.close();
    }

    private void submit(Runnable action) {
        if (!callbackStarted()) {
            return;
        }
        try {
            context.executor().execute(() -> {
                try {
                    action.run();
                } finally {
                    callbackFinished();
                }
            });
        } catch (RuntimeException | Error failure) { // NOSONAR (java:S1181): owned resources must be released or failed on an Error too
            callbackFinished();
            result.completeExceptionally(failure);
        }
    }
}
