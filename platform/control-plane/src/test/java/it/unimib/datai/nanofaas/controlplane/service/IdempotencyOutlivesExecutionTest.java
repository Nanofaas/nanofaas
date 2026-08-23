package it.unimib.datai.nanofaas.controlplane.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;
import it.unimib.datai.nanofaas.controlplane.execution.ExecutionStore;
import it.unimib.datai.nanofaas.controlplane.execution.IdempotencyStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The failure that deriving the key's lifetime prevents, kept executable.
 *
 * An idempotency key gates the replay: createOrReuseExecution asks the key store
 * first, and an expired key reads as "never seen". So a retry that arrives after
 * the key has gone - but while the answer is still held - builds a second
 * execution and the function runs twice. No error, no log, one extra side effect,
 * which is the exact thing the key exists to prevent.
 *
 * The lifetime arithmetic lives beside the class that computes it, in
 * IdempotencyKeyLifetimeTest. This one shows why it matters.
 */
class IdempotencyOutlivesExecutionTest {

    private static FunctionSpec spec() {
        return new FunctionSpec("charge-card", "image", null, Map.of(), null,
                1000, 1, 10, 0, null, ExecutionMode.LOCAL, null, null, null);
    }

    @Test
    void aKeyShorterThanTheRecordDuplicatesTheExecution() throws InterruptedException {
        // Records held for thirty minutes; the key, built the way production no
        // longer can, for a tenth of a second.
        ExecutionStore executions = new ExecutionStore(new ExecutionStoreProperties(
                Duration.ofMinutes(30), Duration.ofMinutes(20), Duration.ofHours(1)));
        IdempotencyStore keys = new IdempotencyStore(Duration.ofMillis(100));
        InvocationExecutionFactory factory = new InvocationExecutionFactory(executions, keys, new Metrics(new SimpleMeterRegistry()));
        InvocationRequest request = new InvocationRequest("payload", Map.of());

        var first = factory.createOrReuseExecution("charge-card", spec(), request, "order-42", null, InvocationKind.SYNC);
        String firstId = first.executionRecord().executionId();
        first.publishAdmission();
        first.executionRecord().markSuccess("charged once");

        Thread.sleep(250);
        assertThat(executions.getOrNull(firstId))
                .describedAs("the answer is still held, which is the premise")
                .isNotNull();

        var second = factory.createOrReuseExecution("charge-card", spec(), request, "order-42", null, InvocationKind.SYNC);
        assertThat(second.executionRecord().executionId())
                .describedAs("a key shorter than the record produces a second charge")
                .isNotEqualTo(firstId);
    }
}
