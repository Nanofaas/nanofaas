package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionState;
import it.unimib.datai.nanofaas.controlplane.queue.QueueFullException;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueGateway;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectReason;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueRejectedException;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public final class SyncInvocationCoordinator {
    private final InvocationEnqueuer enqueuer;
    private final Metrics metrics;
    private final SyncQueueGateway syncQueueGateway;
    private final ExecutionCompletionHandler completionHandler;
    private final InvocationResponseMapper responseMapper;

    public SyncInvocationCoordinator(@Nullable InvocationEnqueuer enqueuer,
                                     Metrics metrics,
                                     @Nullable SyncQueueGateway syncQueueGateway,
                                     ExecutionCompletionHandler completionHandler,
                                     InvocationResponseMapper responseMapper) {
        this.enqueuer = enqueuer == null ? InvocationEnqueuer.noOp() : enqueuer;
        this.metrics = metrics;
        this.syncQueueGateway = syncQueueGateway == null ? SyncQueueGateway.noOp() : syncQueueGateway;
        this.completionHandler = completionHandler;
        this.responseMapper = responseMapper;
    }

    public InvocationResponse invoke(InvocationExecutionFactory.ExecutionLookup lookup,
                                     FunctionSpec spec,
                                     Integer timeoutOverrideMs) throws InterruptedException {
        ExecutionRecord record = lookup.record();
        InvocationResponse replay = terminalResponse(record);
        if (replay != null) {
            return replay;
        }

        if (lookup.isNew()) {
            if (syncQueueGateway.enabled()) {
                syncQueueGateway.enqueueOrThrow(record.task());
            } else if (enqueuer.enabled()) {
                enqueueOrThrow(record);
            } else {
                completionHandler.dispatch(record.task());
            }
        }

        int timeoutMs = timeoutOverrideMs == null ? spec.timeoutMs() : timeoutOverrideMs;
        try {
            InvocationResult result = record.completion().get(timeoutMs, TimeUnit.MILLISECONDS);
            if (result.error() != null && "QUEUE_TIMEOUT".equals(result.error().code())) {
                throw new SyncQueueRejectedException(SyncQueueRejectReason.TIMEOUT, syncQueueGateway.retryAfterSeconds());
            }
            return responseMapper.toResponse(record, result);
        } catch (SyncQueueRejectedException ex) {
            throw ex;
        } catch (Exception ex) {
            record.markTimeout();
            metrics.timeout(record.task().functionName());
            return responseMapper.timeoutResponse(record);
        }
    }

    private void enqueueOrThrow(ExecutionRecord record) {
        boolean enqueued = enqueuer.enqueue(record.task());
        if (!enqueued) {
            metrics.queueRejected(record.task().functionName());
            throw new QueueFullException();
        }
        metrics.enqueue(record.task().functionName());
    }

    private InvocationResponse terminalResponse(ExecutionRecord record) {
        if (record.state() == ExecutionState.SUCCESS || record.state() == ExecutionState.ERROR) {
            InvocationResult result = record.lastError() == null
                    ? InvocationResult.success(record.output())
                    : new InvocationResult(false, null, record.lastError());
            return responseMapper.toResponse(record, result);
        }
        if (record.state() == ExecutionState.TIMEOUT) {
            return responseMapper.timeoutResponse(record);
        }
        return null;
    }
}
