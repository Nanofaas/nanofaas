package it.unimib.datai.nanofaas.controlplane.service;

import it.unimib.datai.nanofaas.execution.AttemptFixtures;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionFixtures;
import com.github.benmanes.caffeine.cache.Ticker;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;
import it.unimib.datai.nanofaas.controlplane.dispatch.DispatchResult;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionRecord;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import it.unimib.datai.nanofaas.controlplane.execution.Outcome;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import it.unimib.datai.nanofaas.execution.AttemptCoordinator;
import it.unimib.datai.nanofaas.execution.AttemptObserver;
import it.unimib.datai.nanofaas.execution.AttemptTransport;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The runtime callback ({@code POST /v1/internal/executions/{id}:complete}) deserializes its
 * result from the runtime's JSON, and nothing obliges a failure to carry an error: a runtime that
 * posts {@code {"success": false}} is not one of the official SDKs, but the endpoint accepts any
 * image speaking the protocol. Every reader of that execution must then tell the same story: the
 * waiter on the shared future, a replay of its key (live or archived) and the status endpoint.
 */
class FailureWithoutErrorDetailsTest {

    private final InvocationResponseMapper mapper = new InvocationResponseMapper();

    @Test
    void aFailureReportedWithoutErrorDetailsIsAnErrorForEveryReader() {
        var properties = ExecutionFixtures.properties(null, null, null);
        var store = ExecutionFixtures.store(properties, Ticker.systemTicker());
        var coordinator = AttemptFixtures.coordinator(store, new FunctionCapacityRegistry(),
                RetryScheduler.unavailable(), mock(AttemptTransport.class), mock(AttemptObserver.class));
        var spec = new FunctionSpec("fn", "image", null, null, null,
                30_000, 1, 10, 0, "http://function", ExecutionMode.EXTERNAL, null, null, null);
        var task = new InvocationTask("bare-failure", "fn", spec, new InvocationRequest("input", Map.of()),
                null, null, Instant.now(), 1, InvocationKind.ASYNC);
        var executionRecord = ExecutionFixtures.record("bare-failure", task);
        store.put(executionRecord);

        // What the callback endpoint builds from {"success": false}.
        coordinator.completeExecution("bare-failure",
                DispatchResult.warm(new InvocationResult(false, null, null)), 1);

        InvocationResult published = executionRecord.completion().join();
        assertThat(published.success()).isFalse();
        assertThat(published.error().code()).isEqualTo("UNSPECIFIED_ERROR");

        var waiter = mapper.toResponse(executionRecord, published);
        var liveReplay = mapper.terminalResponse(executionRecord);
        Outcome archived = store.outcomeOf("bare-failure");
        var archivedReplay = mapper.terminalResponse("bare-failure", archived);
        var status = mapper.toStatus("bare-failure", archived);

        assertThat(waiter.status()).isEqualTo("error");
        assertThat(liveReplay.status()).isEqualTo("error");
        assertThat(archivedReplay.status()).isEqualTo("error");
        assertThat(status.status()).isEqualTo("error");
        assertThat(liveReplay.error().code()).isEqualTo("UNSPECIFIED_ERROR");
        assertThat(archivedReplay.error().code()).isEqualTo("UNSPECIFIED_ERROR");
        assertThat(status.error().code()).isEqualTo("UNSPECIFIED_ERROR");
    }

    /**
     * Success or error is the record's state, as the shared future and the status endpoint read it;
     * an error's presence is not a second source of truth a replay may disagree with.
     */
    @Test
    void aReplayReadsSuccessOrErrorFromTheStateNotFromTheErrorsPresence() {
        var spec = new FunctionSpec("fn", "image", null, null, null,
                30_000, 1, 10, 0, "http://function", ExecutionMode.EXTERNAL, null, null, null);
        var task = new InvocationTask("bare-error", "fn", spec, new InvocationRequest("input", Map.of()),
                "key", null, Instant.now(), 1, InvocationKind.SYNC);
        var executionRecord = ExecutionFixtures.record("bare-error", task);
        executionRecord.markError(null);

        assertThat(mapper.terminalResponse(executionRecord).status()).isEqualTo("error");
        assertThat(mapper.terminalResponse("bare-error", executionRecord.toOutcome()).status()).isEqualTo("error");
    }
}
