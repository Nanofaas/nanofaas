package com.mcfaas.controlplane.dispatch;

import com.mcfaas.common.model.InvocationResult;
import com.mcfaas.controlplane.scheduler.InvocationTask;

import java.util.concurrent.CompletableFuture;

public interface Dispatcher {
    CompletableFuture<InvocationResult> dispatch(InvocationTask task);
}
