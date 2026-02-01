package com.mcfaas.controlplane.sync;

import com.mcfaas.controlplane.scheduler.InvocationTask;

import java.time.Instant;

public record SyncQueueItem(
        InvocationTask task,
        Instant enqueuedAt
) {
}
