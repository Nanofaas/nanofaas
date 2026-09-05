package it.unimib.datai.nanofaas.controlplane.execution;

import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The active side of expiry: a record that {@code maxLifetime} evicts on its own
 * must tell someone, and only when the eviction really was that - not an ordinary
 * {@link ExecutionStore#settle} or {@link ExecutionStore#remove}.
 */
class ExecutionStoreAdministrativeExpiryTest {

    private static final Duration TTL = Duration.ofMinutes(5);
    private static final Duration MAX_LIFETIME = Duration.ofMinutes(30);
    private static final Duration SYNC_TTL = Duration.ofSeconds(30);

    private final AtomicLong clock = new AtomicLong();
    private final Ticker ticker = clock::get;

    private ExecutionStore store() {
        return new ExecutionStore(ExecutionStoreProperties.of(TTL, MAX_LIFETIME, SYNC_TTL), ticker);
    }

    private void advance(Duration duration) {
        clock.addAndGet(duration.toNanos());
    }

    @Test
    void aRecordThatOutlivesMaxLifetimeNotifiesTheRegisteredListenerExactlyOnce() {
        ExecutionStore store = store();
        ConcurrentLinkedQueue<ExecutionRecord> notified = new ConcurrentLinkedQueue<>();
        store.onAdministrativeExpiry(notified::add);
        ExecutionRecord execution = executionRecord("stuck");
        store.put(execution);

        advance(MAX_LIFETIME.plusSeconds(1));
        store.cleanUp();

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(notified).hasSize(1));
        assertThat(notified.peek().executionId()).isEqualTo("stuck");
        assertThat(store.getOrNull("stuck")).isNull();
    }

    @Test
    void aNormalSettleNeverNotifiesTheExpiryListener() {
        ExecutionStore store = store();
        ConcurrentLinkedQueue<ExecutionRecord> notified = new ConcurrentLinkedQueue<>();
        store.onAdministrativeExpiry(notified::add);
        ExecutionRecord execution = executionRecord("settled");
        store.put(execution);
        execution.markSuccess("ok");

        store.settle(execution);
        advance(MAX_LIFETIME.multipliedBy(2));
        store.cleanUp();

        // Give the (async) listener executor a fair chance to run, then assert it never did:
        // an EXPLICIT removal (settle's own invalidate) must never be mistaken for an expiry.
        await().pollDelay(Duration.ofMillis(200)).atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(notified).isEmpty());
    }

    @Test
    void anExplicitRemoveNeverNotifiesTheExpiryListener() {
        ExecutionStore store = store();
        ConcurrentLinkedQueue<ExecutionRecord> notified = new ConcurrentLinkedQueue<>();
        store.onAdministrativeExpiry(notified::add);
        ExecutionRecord execution = executionRecord("removed");
        store.put(execution);

        store.remove("removed");
        advance(MAX_LIFETIME.multipliedBy(2));
        store.cleanUp();

        await().pollDelay(Duration.ofMillis(200)).atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(notified).isEmpty());
    }

    /**
     * The point of the whole change: nobody touches the cache after {@code put}.
     * If expiry only ran on the next read/write (the old behaviour), this would
     * hang forever. Real ticker, real clock, no {@code cleanUp()}, no {@code get}.
     */
    @Test
    void expiryFiresOnItsOwnWithoutAnyFurtherCacheActivity() {
        ExecutionStore store = new ExecutionStore(
                ExecutionStoreProperties.of(TTL, Duration.ofMillis(80), SYNC_TTL, 100_000),
                new SimpleMeterRegistry());
        ConcurrentLinkedQueue<ExecutionRecord> notified = new ConcurrentLinkedQueue<>();
        store.onAdministrativeExpiry(notified::add);
        store.put(executionRecord("background"));

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(notified).hasSize(1));
    }

    private static ExecutionRecord executionRecord(String id) {
        FunctionSpec spec = new FunctionSpec("fn", "img", List.of(), Map.of(), null,
                1000, 1, 10, 0, null, ExecutionMode.LOCAL, RuntimeMode.HTTP, null, null, null);
        InvocationTask task = new InvocationTask(id, "fn", spec,
                new InvocationRequest("payload", Map.of()), null, null, Instant.now(), 1,
                InvocationKind.SYNC);
        return new ExecutionRecord(id, task);
    }
}
