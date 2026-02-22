package it.unimib.datai.nanofaas.controlplane.dispatch;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;

import java.util.concurrent.CompletableFuture;

public interface Dispatcher {
    CompletableFuture<DispatchResult> dispatch(InvocationTask task);
}
