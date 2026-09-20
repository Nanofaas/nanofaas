package it.unimib.datai.nanofaas.controlplane.execution;

import com.github.benmanes.caffeine.cache.Ticker;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.RuntimeMode;
import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for finding R1 of the 2026-09-08 pre-soak review.
 *
 * <p>{@link OutcomeWeigher} stops walking a structure past depth 4 and past 256
 * elements, and prices everything it skipped at zero. A nested or wide payload that
 * is mostly <em>after</em> those limits is therefore retained under an outcome byte
 * budget that is orders of magnitude smaller than the payload it actually pins.
 *
 * <p>Correct behavior (plan task P02): a payload whose retained size cannot be
 * bounded within the traversal limits must not be silently cached at a tiny weight.
 * The historical reproduction — 30 distinct 1 MiB payloads nested five levels deep,
 * plus a 1 MiB string after 256 list entries — must NOT fit inside a 11,600-byte
 * outcome budget.
 *
 * <p>This test asserts the desired behavior, so it is RED on the current baseline,
 * where {@code ExecutionStore.size()} reports all of the oversized outcomes as
 * retained.
 */
class R1OutcomeByteBudgetRegressionTest {

    private static FunctionSpec spec(String name) {
        return new FunctionSpec(name, "img", List.of(), Map.of(), null,
                10000, 1, 10, 0, null, ExecutionMode.LOCAL, RuntimeMode.HTTP, null, null, null);
    }

    private static InvocationTask task(String executionId) {
        return new InvocationTask(
                executionId,
                "fn",
                spec("fn"),
                new InvocationRequest(null, Map.of()),
                null,
                null,
                Instant.now(),
                1,
                InvocationKind.ASYNC);
    }

    @Test
    void deeplyNestedAndWidePayloadsDoNotSilentlyFitTheOutcomeByteBudget() {
        int count = 30;
        int bytes = 1024 * 1024;
        ExecutionStoreProperties props = new ExecutionStoreProperties(
                Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofSeconds(30),
                100, 100, 11_600);
        ExecutionStore store = new ExecutionStore(props, Ticker.systemTicker());
        // The owner is mandatory for settle(); the store-level tests attach a minimal one.
        new ExecutionLifecycle(store, new IdempotencyStore(props, Ticker.systemTicker()));

        // Deep case: 30 distinct 1 MiB strings, each wrapped in five lists. The current
        // weigher stops at depth 4 and prices the string at zero, so each outcome weighs
        // about 176 bytes and all 30 fit the 11,600-byte budget.
        for (int i = 0; i < count; i++) {
            Object payload = new String(new char[bytes]).replace('\0', (char) ('a' + i % 26));
            for (int depth = 0; depth < 5; depth++) {
                payload = List.of(payload);
            }
            ExecutionRecord record = new ExecutionRecord("w" + i, task("w" + i));
            store.put(record);
            record.markSuccess(payload);
            store.settle(record);
        }

        // Wide case: a 1 MiB string placed after 256 null entries is skipped by the
        // 256-element walk limit, so it too is priced far below its retained size.
        List<Object> wide = new ArrayList<>(Collections.nCopies(256, null));
        wide.add("x".repeat(bytes));
        ExecutionRecord wideRecord = new ExecutionRecord("wide", task("wide"));
        store.put(wideRecord);
        wideRecord.markSuccess(wide);
        store.settle(wideRecord);

        // A conservative bound must refuse to retain these payloads under an 11,600-byte
        // budget: their retained bytes are three orders of magnitude larger. The baseline
        // retains all 31 of them and fails here.
        assertThat(store.size())
                .as("oversized nested/wide outcomes must not be silently retained within "
                        + "a %d-byte budget", props.maxOutcomeBytes())
                .isZero();
    }
}
