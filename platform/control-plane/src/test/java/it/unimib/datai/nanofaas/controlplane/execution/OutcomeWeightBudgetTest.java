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
        // The default is calibrated so that nothing changes for compact outcomes:
        // maxOutcomes outcomes of 116 bytes must all still fit.
        int count = 500;
        ExecutionStore store = store(new ExecutionStoreProperties(TTL, TTL, TTL, count, 100_000, 0));

        for (int i = 0; i < count; i++) {
            settle(store, "exec-" + i, "ok");
        }

        assertThat(store.size()).isEqualTo(count);
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

        assertThat(store.size())
                .as("the byte budget must bite well before the count cap")
                .isLessThan(1000);
        assertThat(store.size())
                .as("but it must still retain the outcomes that do fit")
                .isPositive();
    }

    @Test
    void theRetainedCountFollowsTheByteBudgetNotACount() {
        // The property that matters is not "exactly how many", which depends on the
        // estimated weight of the individual outcome, but that the retained count follows
        // the BYTES granted: doubling the budget doubles the retention, payload unchanged.
        long budget = 20 * ExecutionStoreProperties.COMPACT_OUTCOME_BYTES;
        int small = fillAndCount(budget);
        int doubled = fillAndCount(budget * 2);

        assertThat(small).isPositive();
        assertThat(doubled).isCloseTo(small * 2, org.assertj.core.data.Offset.offset(3));
    }

    private static int fillAndCount(long budget) {
        ExecutionStore store = store(new ExecutionStoreProperties(TTL, TTL, TTL, 100_000, 100_000, budget));
        for (int i = 0; i < 500; i++) {
            settle(store, "exec-" + i, "ok");
        }
        return store.size();
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

    private static ExecutionStore store(ExecutionStoreProperties props) {
        ExecutionStore store = new ExecutionStore(props);
        // The owner is mandatory for settle(); the store-level tests attach a minimal one.
        new ExecutionLifecycle(store, new IdempotencyStore());
        return store;
    }

    private static void settle(ExecutionStore store, String id, Object output) {
        FunctionSpec spec = new FunctionSpec("fn", "img", List.of(), Map.of(), null,
                1000, 1, 10, 0, null, ExecutionMode.LOCAL, null, null, null);
        InvocationTask task = new InvocationTask(id, "fn", spec,
                new InvocationRequest("p", Map.of()), null, null, Instant.now(), 1,
                InvocationKind.ASYNC);
        ExecutionRecord record = new ExecutionRecord(id, task);
        store.put(record);
        record.markSuccess(output);
        store.settle(record);
    }
}
