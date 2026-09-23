package it.unimib.datai.nanofaas.execution;

import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * What {@link AttemptTransport#submit} hands back for one attempt (issue #208, Task 10):
 * {@code outcome} is the logical dispatch result, {@code drained} is the independent signal
 * that the raw transport has actually stopped retaining the request (physical capacity), and
 * {@code cancellation} is however the transport wants cancellation requested for this attempt.
 * The three are deliberately independent: {@link AttemptCoordinator} may observe {@code outcome}
 * complete before {@code drained} does, and must not release the dispatch lease or the retained
 * input until {@code drained} completes, whatever order they arrive in.
 */
public record AttemptHandle(CompletableFuture<DispatchResult> outcome,
                             CompletableFuture<Void> drained,
                             Future<?> cancellation) {
}
