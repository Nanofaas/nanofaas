package it.unimib.datai.nanofaas.modules.autoscaler;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.controlplane.deployment.DeploymentWakeUpCoordinator;

import java.util.concurrent.ScheduledThreadPoolExecutor;

final class WakeUpTestResources implements AutoCloseable {
    private final FunctionCapacityRegistry generations = new FunctionCapacityRegistry();
    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    private final DeploymentWakeUpCoordinator coordinator;

    WakeUpTestResources() {
        scheduler.setRemoveOnCancelPolicy(true);
        coordinator = new DeploymentWakeUpCoordinator(generations, scheduler);
    }

    DeploymentWakeUpCoordinator coordinator() {
        return coordinator;
    }

    FunctionCapacityRegistry generations() {
        return generations;
    }

    ScheduledThreadPoolExecutor scheduler() {
        return scheduler;
    }

    FunctionGeneration generation(String functionName) {
        FunctionGeneration current = generations.activeGeneration(functionName);
        if (current == null) {
            generations.register(functionName, 1);
            current = generations.activeGeneration(functionName);
        }
        return current;
    }

    @Override
    public void close() {
        coordinator.close();
        scheduler.shutdownNow();
    }
}
