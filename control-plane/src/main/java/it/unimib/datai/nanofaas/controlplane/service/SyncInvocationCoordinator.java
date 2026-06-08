package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
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
        InvocationResponse replay = responseMapper.terminalResponse(record);
        if (replay != null) {
            return replay;
        }

        if (lookup.isNew()) {
            try {
                if (syncQueueGateway.enabled()) {
                    syncQueueGateway.enqueueOrThrow(record.task());
                } else if (enqueuer.enabled()) {
                    InvocationEnqueueSupport.enqueueOrThrow(enqueuer, metrics, record);
                } else {
                    completionHandler.dispatch(record.task());
                }
                lookup.publishAdmission();
            } catch (RuntimeException ex) {
                lookup.abandonAdmission();
                throw ex;
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
        } catch (java.util.concurrent.TimeoutException ex) {
            record.markTimeout();
            metrics.timeout(record.task().functionName());
            return responseMapper.timeoutResponse(record);
        } catch (InterruptedException ex) {
            record.markTimeout();
            metrics.timeout(record.task().functionName());
            Thread.currentThread().interrupt();
            throw ex;
        } catch (java.util.concurrent.ExecutionException ex) {
            record.markTimeout();
            metrics.timeout(record.task().functionName());
            return responseMapper.timeoutResponse(record);
        }
    }
}
