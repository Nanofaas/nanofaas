package it.unimib.datai.nanofaas.controlplane.execution;

import it.unimib.datai.nanofaas.controlplane.config.ExecutionStoreProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The key's lifetime is derived from the executions', never set on its own.
 *
 * A key is useful only while the answer it points at still exists: an expired key
 * reads as "never seen", so a retry builds a second execution while the first is
 * still held, and the function runs twice. Silently.
 *
 * Before this the key held five minutes hardcoded in a constructor while the
 * records held whatever nanofaas.execution-store.* said. They agreed by
 * coincidence, and raising the execution retention - a documented knob - broke
 * idempotency without touching it. IdempotencyOutlivesExecutionTest keeps that
 * failure executable.
 */
class IdempotencyKeyLifetimeTest {

    @Test
    void theKeyLifetimeCoversWhateverKeptTheExecution() {
        // A terminal record is held `ttl` past completion, a stuck one `maxLifetime`
        // past creation; the key has to outlive whichever kept the record.
        assertThat(IdempotencyStore.keyLifetime(
                ExecutionStoreProperties.of(Duration.ofMinutes(30), Duration.ofMinutes(30), null)))
                .isGreaterThanOrEqualTo(Duration.ofMinutes(30));
        assertThat(IdempotencyStore.keyLifetime(
                ExecutionStoreProperties.of(Duration.ofMinutes(5), Duration.ofHours(2), null)))
                .isGreaterThanOrEqualTo(Duration.ofHours(2));
        // And it never drops under the platform's own retry horizon: the default
        // 30s timeout across three retries plus the first attempt is two minutes.
        assertThat(IdempotencyStore.keyLifetime(
                ExecutionStoreProperties.of(Duration.ofSeconds(1), Duration.ofSeconds(1), null)))
                .isGreaterThanOrEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void theDefaultsAgreeWithoutBeingToldTo() {
        ExecutionStoreProperties defaults = ExecutionStoreProperties.of(null, null, null);
        assertThat(IdempotencyStore.keyLifetime(defaults))
                .isGreaterThanOrEqualTo(defaults.ttl())
                .isGreaterThanOrEqualTo(defaults.maxLifetime());
    }
}
