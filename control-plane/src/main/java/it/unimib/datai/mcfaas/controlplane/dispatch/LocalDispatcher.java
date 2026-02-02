package it.unimib.datai.mcfaas.controlplane.dispatch;

import it.unimib.datai.mcfaas.common.model.InvocationResult;
import it.unimib.datai.mcfaas.controlplane.scheduler.InvocationTask;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
public class LocalDispatcher implements Dispatcher {
    @Override
    public CompletableFuture<InvocationResult> dispatch(InvocationTask task) {
        return CompletableFuture.completedFuture(InvocationResult.success(task.request().input()));
    }
}
