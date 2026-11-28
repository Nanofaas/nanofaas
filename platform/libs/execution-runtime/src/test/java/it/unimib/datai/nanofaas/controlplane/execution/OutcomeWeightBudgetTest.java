package it.unimib.datai.nanofaas.controlplane.execution;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T3: outcomes are bounded in bytes, not only in count. The count cap assumes they
 * all weigh the same, and a readable outcome retains the caller's payload: 20,000
 * outcomes at 64 KB measured 1.28 GB.
 */
class OutcomeWeightBudgetTest {

    private static final Duration TTL = Duration.ofMinutes(30);

    @Test
    void compactOutcomesStillFitTheirCountBudget() {
        // An explicit byte budget must cover payload, key and structural overhead.
        // The legacy 116-byte multiplier is a default budget, not a heap-size guarantee.
        int count = 500;
        ExecutionStore store = store(new ExecutionStoreProperties(TTL, TTL, TTL, count, 100_000, count * 256L));

        for (int i = 0; i < count; i++) {
            settle(store, "exec-" + i, "ok");
        }

        assertThat(ExecutionFixtures.outcomesHeld(store)).isEqualTo(count);
    }

    @Test
    void largePayloadsAreEvictedByWeightLongBeforeTheCountLimit() {
        // Default budget (100,000 compact outcomes = 11.6 MB) and a 64 KB payload: the
        // count cap would let 100,000 in, i.e. ~6 GB. The byte budget must stop at a few
        // hundred.
        ExecutionStore store = store(new ExecutionStoreProperties(TTL, TTL, TTL, 100_000, 100_000, 0));
        String large = "x".repeat(64 * 1024);

        for (int i = 0; i < 1000; i++) {
            settle(store, "exec-" + i, large);
        }

        assertThat(ExecutionFixtures.outcomesHeld(store))
                .as("the byte budget must bite well before the count cap")
                .isLessThan(1000);
        assertThat(ExecutionFixtures.outcomesHeld(store))
                .as("but it must still retain the outcomes that do fit")
                .isPositive();
    }

    @Test
    void theRetainedCountFollowsTheByteBudgetNotACount() {
        // The property that matters is not "exactly how many", which depends on the
        // estimated weight of the individual outcome, but that the retained count follows
        // the BYTES granted: doubling the budget doubles the retention, payload unchanged.
        long budget = 20 * ExecutionStoreProperties.COMPACT_OUTCOME_BYTES;
        long small = fillAndCount(budget);
        long doubled = fillAndCount(budget * 2);

        assertThat(small).isPositive();
        assertThat(doubled).isCloseTo(small * 2, org.assertj.core.data.Offset.offset(3L));
    }

    private static long fillAndCount(long budget) {
        ExecutionStore store = store(new ExecutionStoreProperties(TTL, TTL, TTL, 100_000, 100_000, budget));
        for (int i = 0; i < 500; i++) {
            settle(store, "exec-" + i, "ok");
        }
        return ExecutionFixtures.outcomesHeld(store);
    }

    @Test
    void aPayloadBeyondTheTraversalBoundIsDeclinedNotPricedAtZero() {
        // A payload nested deeper than the walk limit used to be priced at zero below the
        // limit and retained under a tiny weight (finding R1). Now it is declined: the
        // skipped remainder must not be silently under-priced.
        Map<String, Object> nested = Map.of("k", Map.of("k", Map.of("k", Map.of("k",
                Map.of("k", Map.of("k", "x".repeat(4096)))))));
        ExecutionStore store = store(new ExecutionStoreProperties(TTL, TTL, TTL, 100_000, 100_000, 0));

        settle(store, "deep", nested);

        assertThat(store.outcomeOf("deep")).isNull();
    }

    @Test
    void aPayloadWithinTheTraversalBoundIsRetained() {
        // Four levels of nesting walk fine: the string at depth 4 is inside the limit, so
        // its weight is known and the outcome is retained.
        Map<String, Object> nested = Map.of("k", Map.of("k", Map.of("k", Map.of("k",
                "x".repeat(4096)))));
        ExecutionStore store = store(new ExecutionStoreProperties(TTL, TTL, TTL, 100_000, 100_000, 0));

        settle(store, "shallow", nested);

        assertThat(store.outcomeOf("shallow")).isNotNull();
    }

    /**
     * On a stopped fake clock: nothing expires, and the store evicts for weight on the calling
     * thread, so a count read after the writes sees every eviction they caused.
     */
    private static ExecutionStore store(ExecutionStoreProperties props) {
        return ExecutionFixtures.store(props, () -> 0L);
    }

    private static void settle(ExecutionStore store, String id, Object output) {
        FunctionSpec spec = new FunctionSpec("fn", "img", List.of(), Map.of(), null,
                1000, 1, 10, 0, null, ExecutionMode.LOCAL, null, null, null);
        InvocationTask task = new InvocationTask(id, "fn", spec,
                new InvocationRequest("p", Map.of()), null, null, Instant.now(), 1,
                InvocationKind.ASYNC);
        ExecutionRecord executionRecord = ExecutionFixtures.record(id, task);
        store.put(executionRecord);
        executionRecord.markSuccess(output);
        store.settle(executionRecord);
    }
}
