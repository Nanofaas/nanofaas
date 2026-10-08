package it.unimib.datai.nanofaas.controlplane.execution;

import com.github.benmanes.caffeine.cache.Ticker;
import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The key's phases are derived from the executions' lifetimes, never configured on
 * their own.
 *
 * <p>A published key lives as long as the execution it points at can live -
 * {@code maxLifetime}. A terminal key lives as long as the outcome it points at can
 * be read back - {@code ttl}, and that clock starts at the completion (the
 * {@code markTerminal} transition), not at publication.
 *
 * <p>The failure this prevents: a key that expired while the answer it points at
 * still existed reads as "never seen", so a retry builds a second execution while
 * the first is still held, and the function runs twice. Silently - no error, no
 * log, one duplicate side effect, which is the exact failure the key exists to
 * prevent. {@code IdempotencyOutlivesExecutionTest} keeps the pre-fix failure
 * executable.
 */
class IdempotencyKeyLifetimeTest {

    private final AtomicLong clock = new AtomicLong();
    private final Ticker ticker = clock::get;

    private IdempotencyStore store(Duration ttl, Duration maxLifetime) {
        return new IdempotencyStore(ExecutionStoreProperties.of(ttl, maxLifetime, null), ticker);
    }

    private void advance(Duration duration) {
        clock.addAndGet(duration.toNanos());
    }

    @Test
    void aPublishedKeyLivesAsLongAsTheExecutionCanNotAsLongAsTheOutcome() {
        // ttl 5 minutes, maxLifetime 30: the live phase is maxLifetime, not ttl.
        IdempotencyStore store = store(Duration.ofMinutes(5), Duration.ofMinutes(30));
        store.put("fn", "k", "exec-1");

        // It survives well past the ttl: the execution may live up to maxLifetime.
        advance(Duration.ofMinutes(6));
        assertThat(store.getExecutionId("fn", "k")).hasValue("exec-1");

        // And it disappears only with the execution, maxLifetime after publication.
        advance(Duration.ofMinutes(25));
        assertThat(store.getExecutionId("fn", "k")).isEmpty();
    }

    @Test
    void aTerminalKeyLivesTtlFromCompletionNotFromPublication() {
        // A key published at t=0 and archived at t=29m must last until t=34m, not until
        // the old derivation's max(ttl, maxLifetime)=30m.
        IdempotencyStore store = store(Duration.ofMinutes(5), Duration.ofMinutes(30));
        store.put("fn", "k", "exec-1");

        advance(Duration.ofMinutes(29));
        store.markTerminal("fn", "k", "exec-1");

        // t=31m: past the old horizon, but inside the terminal retention.
        advance(Duration.ofMinutes(2));
        assertThat(store.getExecutionId("fn", "k")).hasValue("exec-1");

        // t=34m: the terminal retention has expired.
        advance(Duration.ofMinutes(3).plusSeconds(1));
        assertThat(store.getExecutionId("fn", "k")).isEmpty();
    }

    @Test
    void markTerminalIsIdempotentAndDoesNotTouchUnkeyedOrPendingKeys() {
        IdempotencyStore store = store(Duration.ofMinutes(5), Duration.ofMinutes(30));
        store.put("fn", "k", "exec-1");

        store.markTerminal("fn", "k", "exec-1");
        store.markTerminal("fn", "k", "exec-1");

        assertThat(store.getExecutionId("fn", "k")).hasValue("exec-1");

        // Pending: the transition must not step over a claim in progress.
        IdempotencyStore.AcquireResult claim = store.acquireOrGet("fn", "k2");
        store.markTerminal("fn", "k2", "exec-2");
        assertThat(store.acquireOrGet("fn", "k2").state())
                .isEqualTo(IdempotencyStore.AcquireResult.State.PENDING);
        assertThat(claim.state()).isEqualTo(IdempotencyStore.AcquireResult.State.CLAIMED);
    }

    @Test
    void markTerminalIgnoresAKeyThatHasSinceBeenReboundToAnotherExecution() {
        IdempotencyStore store = store(Duration.ofMinutes(5), Duration.ofMinutes(30));
        store.put("fn", "k", "exec-2");

        // The replaced execution archives late: the current binding is not its own.
        store.markTerminal("fn", "k", "exec-1");

        advance(Duration.ofMinutes(20));
        assertThat(store.getExecutionId("fn", "k"))
                .as("the binding lives on its own execution's retention, not the replaced one's")
                .hasValue("exec-2");
    }

    @Test
    void theDefaultsAgreeWithoutBeingToldTo() {
        ExecutionStoreProperties defaults = ExecutionStoreProperties.of(null, null, null);
        IdempotencyStore store = new IdempotencyStore(defaults, ticker);
        store.put("fn", "k", "exec-1");

        // The live phase covers at least maxLifetime, and after markTerminal at least ttl.
        advance(defaults.maxLifetime().dividedBy(2));
        assertThat(store.getExecutionId("fn", "k")).hasValue("exec-1");
        store.markTerminal("fn", "k", "exec-1");
        advance(defaults.ttl().dividedBy(2));
        assertThat(store.getExecutionId("fn", "k")).hasValue("exec-1");
    }
}
