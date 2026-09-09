package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.InvocationCapacity;
import it.unimib.datai.nanofaas.controlplane.capacity.WaiterCapacity;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.input.RetainedInputEstimator;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionService;
import it.unimib.datai.nanofaas.controlplane.sync.SyncQueueGateway;

final class TestWaiterCapacity {
    private TestWaiterCapacity() {
    }

    static Runtime runtime(ExecutionStore store, IdempotencyStore keys, Metrics metrics,
                           String... functionNames) {
        FunctionCapacityRegistry generations = new FunctionCapacityRegistry();
        for (String functionName : functionNames) {
            generations.register(functionName, 1);
        }
        WaiterCapacity waiters = new WaiterCapacity(generations, 10_000, 1_000);
        InvocationCapacity capacity = new InvocationCapacity(
                generations, 10_000, 1_000, 64L << 20, 16L << 20, 64);
        InvocationExecutionFactory factory = new InvocationExecutionFactory(
                store, keys, metrics, capacity,
                new RetainedInputEstimator.Limits(32, 16_384, 65_536, 64L << 20));
        return new Runtime(factory, waiters);
    }

    static InvocationService service(
            FunctionService functions,
            InvocationEnqueuer enqueuer,
            ExecutionStore store,
            IdempotencyStore keys,
            Metrics metrics,
            SyncQueueGateway syncQueue,
            ExecutionCompletionHandler completion,
            String... functionNames) {
        Runtime runtime = runtime(store, keys, metrics, functionNames);
        InvocationResponseMapper responses = new InvocationResponseMapper();
        ReactiveInvocationCoordinator coordinator = new ReactiveInvocationCoordinator(
                enqueuer, metrics, syncQueue, null, completion, responses, runtime.waiters());
        return new InvocationService(
                functions, enqueuer, store, metrics, completion, runtime.factory(), responses, coordinator);
    }

    record Runtime(InvocationExecutionFactory factory, WaiterCapacity waiters) { }
}
