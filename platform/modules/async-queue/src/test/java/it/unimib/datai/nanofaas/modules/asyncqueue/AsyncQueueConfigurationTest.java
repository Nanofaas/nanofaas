package it.unimib.datai.nanofaas.modules.asyncqueue;

import it.unimib.datai.nanofaas.controlplane.capacity.DispatchOwnership;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionGeneration;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionState;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionRegistrationListener;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingStrategy;
import it.unimib.datai.nanofaas.controlplane.scheduler.SchedulingTicket;
import it.unimib.datai.nanofaas.controlplane.scheduler.TicketId;
import it.unimib.datai.nanofaas.controlplane.service.EngineSyncQueueGateway;
import it.unimib.datai.nanofaas.controlplane.service.SchedulerConfiguration;
import it.unimib.datai.nanofaas.execution.EngineDispatch;
import it.unimib.datai.nanofaas.execution.EngineReadiness;
import it.unimib.datai.nanofaas.execution.PendingEntry;
import it.unimib.datai.nanofaas.execution.PendingWorkStore;
import it.unimib.datai.nanofaas.execution.SchedulerEngine;
import it.unimib.datai.nanofaas.execution.StrategyRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 8 fix round (issue #208), finding I4: this test used to assert an inline rebuild of the
 * retired {@code AsyncQueueConfiguration.queueLifecycleListener}, which proved nothing about
 * production — it could not have caught C2 (function removal no longer terminating queued
 * executions), because it never exercised the real listener. It now drives the actual production
 * bean, {@link SchedulerConfiguration#schedulerCapacityGenerationListener}, over a real (if
 * minimally wired) {@link SchedulerEngine}.
 */
class AsyncQueueConfigurationTest {
    private final ExecutionStore executionStore = new ExecutionStore();

    @Test
    void schedulerCapacityGenerationListener_marksDrainedQueuedExecutionAsFunctionRemoved() {
        FunctionCapacityRegistry capacityRegistry = new FunctionCapacityRegistry();
        PendingWorkStore store = new PendingWorkStore(8);
        // Never runnable: the ticket this test admits stays pending (never claimed), so the
        // removal listener's drain is what has to remove it, not an ordinary dispatch.
        EngineReadiness readiness = generation -> false;
        EngineDispatch dispatch = new EngineDispatch() {
            @Override
            public DispatchOwnership tryAcquire(SchedulingTicket ticket) {
                throw new AssertionError("never selected: readiness always refuses");
            }

            @Override
            public boolean isCurrent(SchedulingTicket ticket) {
                return true;
            }

            @Override
            public void submit(InvocationTask task) {
                throw new AssertionError("never selected: readiness always refuses");
            }

            @Override
            public void expired(InvocationTask task) {
                executionStore.expired(task);
            }

            @Override
            public void removed(InvocationTask task) {
                executionStore.removed(task);
            }

            @Override
            public void rejected(InvocationTask task, Throwable failure) {
                executionStore.rejected(task, failure);
            }
        };
        SchedulingStrategy strategy = new PerFunctionSchedulingStrategy();
        SchedulerEngine engine = new SchedulerEngine(store, new StrategyRegistry(List.of(strategy)),
                strategy.id(), dispatch, readiness, Clock.systemUTC(), System::nanoTime);

        ObjectProvider<EngineSyncQueueGateway> noSyncGateway = new ObjectProvider<>() {
            @Override
            public EngineSyncQueueGateway getObject() {
                throw new NoSuchBeanDefinitionException(EngineSyncQueueGateway.class);
            }
        };
        FunctionRegistrationListener listener = new SchedulerConfiguration()
                .schedulerCapacityGenerationListener(capacityRegistry, engine, store, noSyncGateway);

        FunctionSpec spec = spec("echo");
        InvocationTask task = task("exec-queued", spec);
        ExecutionRecord executionRecord = new ExecutionRecord(task.executionId(), task);

        listener.onRegister(spec);
        executionStore.put(executionRecord);
        FunctionGeneration generation = capacityRegistry.activeGeneration("echo");
        assertThat(generation).isNotNull();
        SchedulingTicket ticket = new SchedulingTicket(
                new TicketId(task.executionId(), task.attempt()), generation, 0,
                Instant.now(), Instant.now(), null);
        assertThat(engine.enqueue(new PendingEntry(ticket, task))).isTrue();

        listener.onRemove("echo");

        assertThat(executionRecord.state()).isEqualTo(ExecutionState.ERROR);
        assertThat(executionRecord.lastError().code()).isEqualTo("FUNCTION_REMOVED");
        assertThat(executionRecord.lastError().message()).contains("echo");
        assertThat(executionRecord.completion().isDone()).isTrue();
        InvocationResult result = executionRecord.completion().join();
        assertThat(result.success()).isFalse();
        assertThat(result.error().code()).isEqualTo("FUNCTION_REMOVED");
    }

    private static FunctionSpec spec(String name) {
        return new FunctionSpec(
                name,
                "image",
                null,
                Map.of(),
                null,
                1000,
                4,
                10,
                3,
                null,
                ExecutionMode.DEPLOYMENT,
                null,
                null,
                null
        );
    }

    private static InvocationTask task(String executionId, FunctionSpec spec) {
        return new InvocationTask(
                executionId,
                spec.name(),
                spec,
                new InvocationRequest("payload", Map.of()),
                null,
                null,
                Instant.now(),
                1
        ,
        InvocationKind.SYNC
    );
    }
}
