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
        // ttl 5 minuti, maxLifetime 30: la fase viva e' maxLifetime, non ttl.
        IdempotencyStore store = store(Duration.ofMinutes(5), Duration.ofMinutes(30));
        store.put("fn", "k", "exec-1");

        // Sopravvive ben oltre il ttl: l'esecuzione puo' vivere fino a maxLifetime.
        advance(Duration.ofMinutes(6));
        assertThat(store.getExecutionId("fn", "k")).hasValue("exec-1");

        // E sparisce solo con l'esecuzione, a maxLifetime dalla pubblicazione.
        advance(Duration.ofMinutes(25));
        assertThat(store.getExecutionId("fn", "k")).isEmpty();
    }

    @Test
    void aTerminalKeyLivesTtlFromCompletionNotFromPublication() {
        // Una chiave pubblicata a t=0 e archiviata a t=29m deve durare fino a t=34m,
        // non fino al max(ttl, maxLifetime)=30m della vecchia derivazione.
        IdempotencyStore store = store(Duration.ofMinutes(5), Duration.ofMinutes(30));
        store.put("fn", "k", "exec-1");

        advance(Duration.ofMinutes(29));
        store.markTerminal("fn", "k");

        // t=31m: oltre il vecchio orizzonte, ma dentro la ritenzione terminale.
        advance(Duration.ofMinutes(2));
        assertThat(store.getExecutionId("fn", "k")).hasValue("exec-1");

        // t=34m: scaduta la ritenzione terminale.
        advance(Duration.ofMinutes(3).plusSeconds(1));
        assertThat(store.getExecutionId("fn", "k")).isEmpty();
    }

    @Test
    void markTerminalIsIdempotentAndDoesNotTouchUnkeyedOrPendingKeys() {
        IdempotencyStore store = store(Duration.ofMinutes(5), Duration.ofMinutes(30));
        store.put("fn", "k", "exec-1");

        store.markTerminal("fn", "k");
        store.markTerminal("fn", "k");

        assertThat(store.getExecutionId("fn", "k")).hasValue("exec-1");

        // Pending: la transizione non puo' scavalcare una rivendicazione in corso.
        IdempotencyStore.AcquireResult claim = store.acquireOrGet("fn", "k2");
        store.markTerminal("fn", "k2");
        assertThat(store.acquireOrGet("fn", "k2").state())
                .isEqualTo(IdempotencyStore.AcquireResult.State.PENDING);
        assertThat(claim.state()).isEqualTo(IdempotencyStore.AcquireResult.State.CLAIMED);
    }

    @Test
    void theDefaultsAgreeWithoutBeingToldTo() {
        ExecutionStoreProperties defaults = ExecutionStoreProperties.of(null, null, null);
        IdempotencyStore store = new IdempotencyStore(defaults, ticker);
        store.put("fn", "k", "exec-1");

        // La fase viva copre almeno maxLifetime, e dopo markTerminal almeno ttl.
        advance(defaults.maxLifetime().dividedBy(2));
        assertThat(store.getExecutionId("fn", "k")).hasValue("exec-1");
        store.markTerminal("fn", "k");
        advance(defaults.ttl().dividedBy(2));
        assertThat(store.getExecutionId("fn", "k")).hasValue("exec-1");
    }
}
