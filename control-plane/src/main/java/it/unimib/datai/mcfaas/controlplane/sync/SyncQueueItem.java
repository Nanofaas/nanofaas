package it.unimib.datai.mcfaas.controlplane.sync;

import it.unimib.datai.mcfaas.controlplane.scheduler.InvocationTask;

import java.time.Instant;

public record SyncQueueItem(
        InvocationTask task,
        Instant enqueuedAt
) {
}
