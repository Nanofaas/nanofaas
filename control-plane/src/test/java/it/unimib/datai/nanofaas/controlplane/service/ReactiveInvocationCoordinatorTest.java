package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResponse;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ReactiveInvocationCoordinatorTest {

    private final ExecutionStore executionStore = new ExecutionStore();
    private final IdempotencyStore idempotencyStore = new IdempotencyStore();
    private final InvocationExecutionFactory factory =
            new InvocationExecutionFactory(executionStore, idempotencyStore);
    private final Metrics metrics = new Metrics(new SimpleMeterRegistry());
    private final ExecutionCompletionHandler completionHandler = mock(ExecutionCompletionHandler.class);
    private final ReactiveInvocationCoordinator coordinator =
            new ReactiveInvocationCoordinator(null, metrics, null, completionHandler, new InvocationResponseMapper());

    @Test
    void clientTimeoutDoesNotCancelSharedCompletionFuture() {
        FunctionSpec spec = spec("fn-cancel");
        InvocationExecutionFactory.ExecutionLookup lookup =
                factory.createOrReuseExecution("fn-cancel", spec, new InvocationRequest("payload", Map.of()), null, null);

        InvocationResponse response = coordinator.invoke(lookup, spec, 50).block();

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo("timeout");
        // The shared future must survive a single client's timeout: other idempotent
        // waiters and the completion callback still depend on it.
        assertThat(lookup.record().completion().isCancelled()).isFalse();
    }

    private static FunctionSpec spec(String name) {
        return new FunctionSpec(name, "img", List.of(), Map.of(), null,
                1000, 1, 10, 0, null, ExecutionMode.LOCAL, RuntimeMode.HTTP, null, null, null);
    }
}
