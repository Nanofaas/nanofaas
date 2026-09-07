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
 * T3: gli esiti sono limitati in byte, non solo in numero. Il tetto in numero
 * presuppone che pesino tutti uguale, e un esito leggibile trattiene il payload
 * del chiamante: 20.000 esiti da 64 KB misurarono 1,28 GB.
 */
class OutcomeWeightBudgetTest {

    private static final Duration TTL = Duration.ofMinutes(30);

    @Test
    void compactOutcomesStillFitTheirCountBudget() {
        // Il default e' tarato perche' per gli esiti compatti non cambi nulla:
        // maxOutcomes esiti da 116 byte devono continuare a starci tutti.
        int count = 500;
        ExecutionStore store = store(new ExecutionStoreProperties(TTL, TTL, TTL, count, 100_000, 0));

        for (int i = 0; i < count; i++) {
            settle(store, "exec-" + i, "ok");
        }

        assertThat(store.size()).isEqualTo(count);
    }

    @Test
    void largePayloadsAreEvictedByWeightLongBeforeTheCountLimit() {
        // Budget predefinito (100.000 esiti compatti = 11,6 MB) e payload da 64 KB:
        // il tetto in numero ne lascerebbe entrare 100.000, cioe' ~6 GB. Il budget in
        // byte deve fermarsi a qualche centinaio.
        ExecutionStore store = store(new ExecutionStoreProperties(TTL, TTL, TTL, 100_000, 100_000, 0));
        String large = "x".repeat(64 * 1024);

        for (int i = 0; i < 1000; i++) {
            settle(store, "exec-" + i, large);
        }

        assertThat(store.size())
                .as("il budget in byte deve limitare molto prima del tetto in numero")
                .isLessThan(1000);
        assertThat(store.size())
                .as("ma deve comunque trattenere gli esiti che ci stanno")
                .isPositive();
    }

    @Test
    void theRetainedCountFollowsTheByteBudgetNotACount() {
        // La proprieta' che conta non e' "quanti esattamente", che dipende dal peso stimato
        // del singolo esito, ma che il numero trattenuto segua i BYTE concessi: raddoppiando
        // il budget si raddoppia la ritenzione, a payload invariato.
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
    void aDeeplyNestedPayloadCostsNoMoreThanTheTraversalBound() {
        // Un payload annidato in modo patologico non deve costare piu' degli altri:
        // la stima e' limitata in ampiezza e profondita' proprio per questo.
        Map<String, Object> nested = Map.of("k", Map.of("k", Map.of("k", Map.of("k",
                Map.of("k", Map.of("k", "x".repeat(4096)))))));
        ExecutionStore store = store(new ExecutionStoreProperties(TTL, TTL, TTL, 100_000, 100_000, 0));

        settle(store, "deep", nested);

        assertThat(store.outcomeOf("deep")).isNotNull();
    }

    private static ExecutionStore store(ExecutionStoreProperties props) {
        return new ExecutionStore(props);
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
