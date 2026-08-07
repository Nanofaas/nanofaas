package it.unimib.datai.nanofaas.modules.syncqueue.sync;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;

import java.time.Instant;

public record SyncQueueItem(
        InvocationTask task,
        Instant enqueuedAt
) {
}
