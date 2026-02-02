package it.unimib.datai.nanofaas.controlplane.dispatch;

import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;

import java.util.concurrent.CompletableFuture;

public interface Dispatcher {
    CompletableFuture<InvocationResult> dispatch(InvocationTask task);
}
