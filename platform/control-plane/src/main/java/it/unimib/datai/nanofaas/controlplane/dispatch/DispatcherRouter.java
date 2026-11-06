package it.unimib.datai.nanofaas.controlplane.dispatch;

import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
public class DispatcherRouter {
    private final LocalDispatcher localDispatcher;
    private final ExternalDispatcher poolDispatcher;
    private final it.unimib.datai.nanofaas.controlplane.registry.ManagedDeploymentCoordinator deployments;

    public DispatcherRouter(LocalDispatcher localDispatcher,
                            ExternalDispatcher poolDispatcher) {
        this(localDispatcher, poolDispatcher, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DispatcherRouter(LocalDispatcher localDispatcher, ExternalDispatcher poolDispatcher,
                            it.unimib.datai.nanofaas.controlplane.registry.ManagedDeploymentCoordinator deployments) {
        this.localDispatcher = localDispatcher;
        this.poolDispatcher = poolDispatcher;
        this.deployments = deployments;
    }

    public CompletableFuture<DispatchResult> dispatchLocal(InvocationTask task) {
        return localDispatcher.dispatch(task);
    }

    public CompletableFuture<DispatchResult> dispatchExternal(InvocationTask task) {
        if (deployments == null || task.functionSpec().executionMode() != it.unimib.datai.nanofaas.common.model.ExecutionMode.DEPLOYMENT) {
            return poolDispatcher.dispatch(task);
        }
        var resolution = deployments.acquireInvocationTarget(task);
        if (!resolution.supported()) return poolDispatcher.dispatch(task);
        if (resolution.target() == null) return CompletableFuture.completedFuture(DispatchResult.warm(
                new it.unimib.datai.nanofaas.common.model.InvocationResult(false, null,
                        new it.unimib.datai.nanofaas.common.model.ErrorInfo("RUNTIME_HANDLER_SATURATED", "No admissible managed replica"),
                        503, null, null)));
        return poolDispatcher.dispatch(task, resolution.target());
    }
}
