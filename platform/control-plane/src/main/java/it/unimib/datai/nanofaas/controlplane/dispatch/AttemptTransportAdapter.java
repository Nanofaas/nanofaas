package it.unimib.datai.nanofaas.controlplane.dispatch;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentReadiness;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.execution.AttemptCoordinator;
import it.unimib.datai.nanofaas.execution.AttemptHandle;
import it.unimib.datai.nanofaas.execution.AttemptTransport;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The control plane's {@link AttemptTransport}: mode-based dispatch selection —
 * LOCAL/EXTERNAL dispatch, and DEPLOYMENT dispatch behind a cancellable
 * wake-up wait. This is the ONLY place {@link DispatcherRouter} and {@link DeploymentReadiness}
 * are called from the attempt path: {@link AttemptCoordinator} (the selector/state machine, in
 * {@code :execution-runtime}) never imports either type, and never will — no GET and no provider
 * call may happen there. That separation is what keeps the engine's own gate discipline intact:
 * the gate already had to be weakened once to take a per-function lock (the {@code gate ->
 * entry.lock} invariant), and a provider call reachable from anywhere near the gate would be a
 * different and much worse class of problem than that lock.
 *
 * <p>Extracted from {@code ExecutionCompletionHandler}'s private {@code LegacyAttemptTransport},
 * essentially unchanged: same mode switch, same wake-up wrapper, same cancellation handles.
 */
@SuppressWarnings("FutureReturnValueIgnored") // Callback stages complete lifecycle-owned futures.
public final class AttemptTransportAdapter implements AttemptTransport {
    private final DispatcherRouter dispatcherRouter;
    private final DeploymentReadiness readiness;

    public AttemptTransportAdapter(DispatcherRouter dispatcherRouter, DeploymentReadiness readiness) {
        this.dispatcherRouter = dispatcherRouter;
        this.readiness = readiness;
    }

    @Override
    public AttemptHandle submit(InvocationTask task) {
        ExecutionMode mode = task.functionSpec().executionMode();
        PhysicalDispatch dispatch = switch (mode) {
            case LOCAL -> PhysicalDispatch.raw(dispatcherRouter.dispatchLocal(task));
            case EXTERNAL -> PhysicalDispatch.raw(dispatcherRouter.dispatchExternal(task));
            case DEPLOYMENT -> dispatchDeployment(task);
        };
        Future<?> cancellation = mode == ExecutionMode.LOCAL
                ? new LocalCancellationHandle(dispatch.outcome())
                : dispatch.outcome();
        return new AttemptHandle(dispatch.outcome(), dispatch.drained(), cancellation);
    }

    private PhysicalDispatch dispatchDeployment(InvocationTask task) {
        try {
            if (readiness.isImmediate()) {
                // Nothing to wake: dispatch directly rather than through the wake-up wrapper,
                // which exists only to make a wait cancellable.
                return PhysicalDispatch.raw(dispatcherRouter.dispatchExternal(task));
            }
            var result = new CancellableDispatchFuture();
            var drained = new CompletableFuture<Void>();
            readiness.ensureReady(task).whenComplete((ignored, error) -> {
                if (result.isCancelled()) {
                    drained.complete(null);
                    return;
                }
                if (error != null) {
                    result.completeExceptionally(new AttemptCoordinator.DeploymentWakeUpException(error));
                    drained.complete(null);
                    return;
                }
                try {
                    CompletableFuture<DispatchResult> transport = dispatcherRouter.dispatchExternal(task);
                    result.attach(transport);
                    transport.whenComplete((value, failure) -> {
                        try {
                            if (!result.cancellationRequested()) {
                                if (failure != null) result.completeExceptionally(failure);
                                else result.complete(value);
                            }
                        } finally {
                            drained.complete(null);
                        }
                    });
                } catch (RuntimeException | Error failure) {
                    result.completeExceptionally(failure);
                    drained.complete(null);
                }
            });
            return new PhysicalDispatch(result, drained);
        } catch (RuntimeException | Error error) {
            return PhysicalDispatch.raw(
                    CompletableFuture.failedFuture(new AttemptCoordinator.DeploymentWakeUpException(error)));
        }
    }

    private record PhysicalDispatch(
            CompletableFuture<DispatchResult> outcome,
            CompletableFuture<Void> drained) {
        private PhysicalDispatch {
            java.util.Objects.requireNonNull(outcome, "outcome");
            java.util.Objects.requireNonNull(drained, "drained");
        }

        private static PhysicalDispatch raw(CompletableFuture<DispatchResult> future) {
            return new PhysicalDispatch(future, future.handle((ignored, failure) -> null));
        }
    }

    /** Cancellation follows a DEPLOYMENT dispatch across the wake-up/HTTP boundary. */
    private static final class CancellableDispatchFuture extends CompletableFuture<DispatchResult> {
        private Future<?> transport;
        private boolean cancellationRequested;
        synchronized boolean cancellationRequested() { return cancellationRequested; }
        void attach(Future<?> handle) {
            boolean cancel;
            synchronized (this) {
                transport = handle;
                cancel = cancellationRequested;
            }
            if (cancel) handle.cancel(true);
        }
        @Override public boolean cancel(boolean interrupt) {
            Future<?> handle;
            synchronized (this) {
                if (isDone()) return isCancelled();
                cancellationRequested = true;
                handle = transport;
            }
            // Cancel the actual subscriber before publishing the composed cancellation.
            if (handle != null) handle.cancel(interrupt);
            return super.cancel(interrupt);
        }
    }

    /** A LOCAL future has no interrupt contract: only its real completion releases capacity. */
    private record LocalCancellationHandle(CompletableFuture<?> work) implements Future<Object> {
        @Override public boolean cancel(boolean interrupt) { return false; }
        @Override public boolean isCancelled() { return false; }
        @Override public boolean isDone() { return work.isDone(); }
        @Override public Object get() throws java.util.concurrent.ExecutionException, InterruptedException {
            return work.get();
        }
        @Override public Object get(long timeout, TimeUnit unit)
                throws java.util.concurrent.ExecutionException, InterruptedException, TimeoutException {
            return work.get(timeout, unit);
        }
    }
}
