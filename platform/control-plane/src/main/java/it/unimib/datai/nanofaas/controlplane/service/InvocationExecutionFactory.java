package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore.AcquireResult;
import it.unimib.datai.nanofaas.controlplane.execution.Outcome;
import it.unimib.datai.nanofaas.controlplane.capacity.InvocationCapacity;
import it.unimib.datai.nanofaas.controlplane.input.InvocationInputRejectedException;
import it.unimib.datai.nanofaas.controlplane.input.RetainedInputEstimator;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.UUID;

@Service
public final class InvocationExecutionFactory {
    private final ExecutionStore executionStore;
    private final IdempotencyStore idempotencyStore;

    private final Metrics metrics;
    private final InvocationCapacity invocationCapacity;
    private final RetainedInputEstimator.Limits inputLimits;

    public InvocationExecutionFactory(ExecutionStore executionStore, IdempotencyStore idempotencyStore,
                                      Metrics metrics, InvocationCapacity invocationCapacity,
                                      RetainedInputEstimator.Limits inputLimits) {
        this.metrics = metrics;
        this.executionStore = executionStore;
        this.idempotencyStore = idempotencyStore;
        this.invocationCapacity = invocationCapacity;
        this.inputLimits = inputLimits;
    }

    public ExecutionLookup createOrReuseExecution(String functionName,
                                                  FunctionSpec spec,
                                                  InvocationRequest request,
                                                  String idempotencyKey,
                                                  String traceId,
                                                  InvocationKind kind) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            ExecutionRecord executionRecord = newExecutionRecord(functionName, spec, request, null, traceId, kind);
            publishRecord(executionRecord);
            return new ExecutionLookup.Fresh(executionRecord, executionStore, null, null, null, null);
        }

        // A duplicate waits on another claimant for at most the function's own timeout: past it the
        // caller could not use the answer anyway, and a claim leaked by some admission path would
        // otherwise park this (boundedElastic) thread until the pending key expires at max-lifetime.
        long waitDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(spec.timeoutMs());
        while (true) {
            AcquireResult acquire = idempotencyStore.acquireOrGet(functionName, idempotencyKey);
            if (acquire.state() == AcquireResult.State.CLAIMED) {
                return createClaimedRecord(
                        functionName,
                        spec,
                        request,
                        idempotencyKey,
                        traceId,
                        kind,
                        acquire.executionIdOrToken()
                );
            }
            if (acquire.state() == AcquireResult.State.PENDING) {
                parkPendingClaim(waitDeadline);
                continue;
            }
            if (acquire.state() == AcquireResult.State.BUDGET_EXHAUSTED) {
                // Reject BEFORE the dispatch, without touching the keys already present.
                throw new IdempotencyBudgetExhaustedException();
            }

            String existingExecutionId = acquire.executionIdOrToken();
            ExecutionLookup replay = replayOf(functionName, kind, existingExecutionId, acquire.terminal());
            if (replay != null) {
                return replay;
            }

            // A binding pointing at an execution that is neither alive nor archived. It is
            // re-claimable only if it was explicitly abandoned after publication (markReclaimable):
            // the function never ran, so a fresh execution may take the key. A still-published
            // binding - however absent its record and outcome look right now - is a live or
            // mid-transition execution, and the dedup guarantee forbids a new claim on the
            // strength of that absence (finding R2).
            AcquireResult reclaimed =
                    idempotencyStore.claimIfMatches(functionName, idempotencyKey, existingExecutionId);
            if (reclaimed.state() == AcquireResult.State.CLAIMED) {
                return createClaimedRecord(functionName, spec, request, idempotencyKey, traceId, kind,
                        reclaimed.executionIdOrToken());
            }
            // PENDING (another claimant), MISSING (the binding vanished), or EXISTING (still
            // published and not explicitly abandoned - the terminal transition is in flight and
            // will surface the tombstone): park briefly and re-read rather than authorising a
            // second execution on the strength of an absent record/outcome.
            parkPendingClaim(waitDeadline);
        }
    }

    /** The outcome a replay of an already-bound key gets, or null when the binding may be re-claimed. */
    private ExecutionLookup replayOf(String functionName, InvocationKind kind, String existingExecutionId,
                                     boolean terminal) {
        ExecutionRecord existing = executionStore.getOrNull(existingExecutionId);
        if (existing != null) {
            // The key did its job: a second arrival found the first execution and will
            // wait on its result instead of running the function again.
            metrics.replayed(functionName, kind);
            return new ExecutionLookup.Live(existing);
        }

        // Finished and archived. Looking only among the living would make this pass
        // for a stale claim, and the function would run a second time in silence:
        // exactly the failure the key exists to prevent.
        Outcome settledOutcome = executionStore.outcomeOf(existingExecutionId);
        if (settledOutcome != null) {
            metrics.replayed(functionName, kind);
            return new ExecutionLookup.Settled(existingExecutionId, settledOutcome);
        }

        // A TERMINAL binding pointing at an execution that is neither alive nor
        // archived: the execution concluded and its outcome payload was evicted for
        // capacity before the end of the window. The deduplication guarantee still
        // holds - the tombstone is the key itself - so the replay does NOT re-run the
        // function: it gets an explicit "no longer available" outcome (HTTP 410).
        if (terminal) {
            metrics.replayed(functionName, kind);
            return new ExecutionLookup.Gone(existingExecutionId);
        }
        return null;
    }

    private ExecutionLookup createClaimedRecord(String functionName,
                                                FunctionSpec spec,
                                                InvocationRequest request,
                                                String idempotencyKey,
                                                String traceId,
                                                InvocationKind kind,
                                                String claimToken) {
        ExecutionRecord executionRecord = null;
        try {
            executionRecord = newExecutionRecord(functionName, spec, request, idempotencyKey, traceId, kind);
            publishRecord(executionRecord);
            return new ExecutionLookup.Fresh(executionRecord, executionStore, idempotencyStore,
                    functionName, idempotencyKey, claimToken);
        } catch (RuntimeException | Error ex) { // NOSONAR (java:S1181): owned resources must be released or failed on an Error too
            if (executionRecord != null) {
                executionStore.remove(executionRecord.executionId());
                executionRecord.rollbackAdmissionResources();
            }
            idempotencyStore.abandonClaim(functionName, idempotencyKey, claimToken);
            throw ex;
        }
    }

    private ExecutionRecord newExecutionRecord(String functionName,
                                                      FunctionSpec spec,
                                                      InvocationRequest request,
                                                      String idempotencyKey,
                                                      String traceId,
                                                      InvocationKind kind) {
        String executionId = newExecutionId();
        RetainedInputEstimator.Result estimate = new RetainedInputEstimator(inputLimits).estimate(request.input());
        if (estimate instanceof RetainedInputEstimator.Rejected(var reason)) {
            throw new InvocationInputRejectedException(reason);
        }
        long retainedBytes = ((RetainedInputEstimator.Measured) estimate).retainedBytes();
        InvocationCapacity.Admission admission = invocationCapacity.reserve(
                functionName, executionId, retainedBytes);
        InvocationTask task = new InvocationTask(
                executionId,
                functionName,
                spec,
                request,
                idempotencyKey,
                traceId,
                Instant.now(),
                1,
                kind
        );
        try {
            return ExecutionRecord.withInputResources(executionId, task, admission);
        } catch (RuntimeException | Error failure) { // NOSONAR (java:S1181): owned resources must be released or failed on an Error too
            admission.rollback();
            throw failure;
        }
    }

    private void publishRecord(ExecutionRecord executionRecord) {
        try {
            executionStore.put(executionRecord);
            executionRecord.publishAdmissionResources();
        } catch (RuntimeException | Error failure) { // NOSONAR (java:S1181): owned resources must be released or failed on an Error too
            executionStore.remove(executionRecord.executionId());
            executionRecord.rollbackAdmissionResources();
            throw failure;
        }
    }

    /**
     * UUIDv4-shaped id from ThreadLocalRandom. Execution ids need uniqueness, not
     * unpredictability; this avoids the SecureRandom contention of UUID.randomUUID()
     * on the invocation hot path.
     */
    static String newExecutionId() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long msb = (random.nextLong() & 0xFFFF_FFFF_FFFF_0FFFL) | 0x0000_0000_0000_4000L; // version 4
        long lsb = (random.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L; // IETF variant
        return new UUID(msb, lsb).toString();
    }

    private static void parkPendingClaim(long waitDeadline) {
        if (System.nanoTime() - waitDeadline >= 0) {
            throw new IdempotencyKeyBusyException();
        }
        java.util.concurrent.locks.LockSupport.parkNanos(java.time.Duration.ofMillis(1).toNanos());
        if (Thread.currentThread().isInterrupted()) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Interrupted while waiting for idempotency claim");
        }
    }

    /**
     * What an admission found, one variant per case: a caller switches on the type instead of
     * probing nullable fields in the right order.
     */
    public sealed interface ExecutionLookup {
        /** The live record behind {@link Fresh} and {@link Live}; null once the execution is over. */
        ExecutionRecord executionRecord();

        /** Whether this is a new admission, which the caller must publish or abandon. */
        default boolean isNew() {
            return false;
        }

        /** Publishes a new admission's key claim; a replay has nothing to publish. */
        default void publishAdmission() {
        }

        /** Undoes a new admission; a replay has nothing to undo. */
        default void abandonAdmission() {
        }

        /** A replay that found its execution still running: it waits on the same result. */
        record Live(ExecutionRecord executionRecord) implements ExecutionLookup {
        }

        /** A replay of a finished execution: only its archived outcome is left to hand back. */
        record Settled(String executionId, Outcome outcome) implements ExecutionLookup {
            @Override
            public ExecutionRecord executionRecord() {
                return null;
            }
        }

        /**
         * A replay of a finished execution whose outcome was evicted for capacity. The key still
         * protects it: the function is not re-run, and the caller answers 410.
         */
        record Gone(String executionId) implements ExecutionLookup {
            @Override
            public ExecutionRecord executionRecord() {
                return null;
            }
        }

        /** A new execution, with the key claim (when keyed) the caller still has to publish or abandon. */
        final class Fresh implements ExecutionLookup {
            private final ExecutionRecord executionRecord;
            private final ExecutionStore executionStore;
            /** Null for an unkeyed admission, together with the three fields below. */
            private final IdempotencyStore idempotencyStore;
            private final String functionName;
            private final String idempotencyKey;
            private final String claimToken;
            private boolean claimPublished;

            private Fresh(ExecutionRecord executionRecord, ExecutionStore executionStore,
                          IdempotencyStore idempotencyStore, String functionName,
                          String idempotencyKey, String claimToken) {
                this.executionRecord = executionRecord;
                this.executionStore = executionStore;
                this.idempotencyStore = idempotencyStore;
                this.functionName = functionName;
                this.idempotencyKey = idempotencyKey;
                this.claimToken = claimToken;
            }

            @Override
            public ExecutionRecord executionRecord() {
                return executionRecord;
            }

            @Override
            public boolean isNew() {
                return true;
            }

            @Override
            public void publishAdmission() {
                if (idempotencyStore == null) {
                    return;
                }
                // Serialized against the lifecycle's key protection on the record monitor: a
                // terminal record published here can never expose a reclaimable published key.
                synchronized (executionRecord) {
                    if (claimPublished) {
                        return;
                    }
                    idempotencyStore.publishClaim(functionName, idempotencyKey, claimToken, executionRecord.executionId());
                    claimPublished = true;
                    // Admission dispatches BEFORE publishing (InvocationEnqueueSupport.admitIfNew),
                    // and with no queue module the dispatch is inline: the record may already be
                    // settled by the time we get here, and the owner's settle found the key still
                    // pending (no-op by design). Both orderings converge on the same terminal
                    // binding this way - under this monitor, so no intermediate published key is
                    // ever observable alongside a vanished execution.
                    if (executionRecord.isTerminal()) {
                        idempotencyStore.markTerminal(functionName, idempotencyKey, executionRecord.executionId());
                    }
                }
            }

            @Override
            public void abandonAdmission() {
                executionStore.remove(executionRecord.executionId());
                executionRecord.rollbackAdmissionResources();
                if (idempotencyStore == null) {
                    return;
                }
                if (!claimPublished) {
                    idempotencyStore.abandonClaim(functionName, idempotencyKey, claimToken);
                } else {
                    // Published and then abandoned: mark the binding explicitly reclaimable so
                    // a later replay may re-claim it for a fresh execution - never infer
                    // abandonment from the record's absence (finding R2).
                    idempotencyStore.markReclaimable(functionName, idempotencyKey, executionRecord.executionId());
                }
            }
        }
    }
}
