package it.unimib.datai.mcfaas.controlplane.dispatch;

import it.unimib.datai.mcfaas.common.model.InvocationResult;
import it.unimib.datai.mcfaas.controlplane.scheduler.InvocationTask;

import java.util.concurrent.CompletableFuture;

public interface Dispatcher {
    CompletableFuture<InvocationResult> dispatch(InvocationTask task);
}
