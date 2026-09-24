package it.unimib.datai.nanofaas.execution;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryBackoffTest {
    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

    @Test
    void doublesThenCapsWithoutShorteningAnUpstreamHint() {
        RetryBackoff backoff = new RetryBackoff(Duration.ofMillis(100), Duration.ofSeconds(2), () -> 0.0);

        assertThat(backoff.notBefore(1, NOW, null)).isEqualTo(NOW.plusMillis(50));
        assertThat(backoff.notBefore(2, NOW, null)).isEqualTo(NOW.plusMillis(100));
        assertThat(backoff.notBefore(3, NOW, null)).isEqualTo(NOW.plusMillis(200));
        assertThat(backoff.notBefore(Integer.MAX_VALUE, NOW, null)).isEqualTo(NOW.plusSeconds(1));
        assertThat(backoff.notBefore(1, NOW, NOW.plusSeconds(30))).isEqualTo(NOW.plusSeconds(30));
        assertThat(backoff.notBefore(1, NOW, NOW.minusSeconds(30))).isEqualTo(NOW.plusMillis(50));
        assertThat(backoff.notBefore(1, Instant.MAX, null)).isEqualTo(Instant.MAX);
    }

    @Test
    void rejectsInvalidDurationsAndAttemptOrdinal() {
        assertThatThrownBy(() -> new RetryBackoff(Duration.ZERO, Duration.ofSeconds(2), () -> 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryBackoff(Duration.ofNanos(-1), Duration.ofSeconds(2), () -> 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryBackoff(Duration.ofSeconds(2), Duration.ofSeconds(1), () -> 0))
                .isInstanceOf(IllegalArgumentException.class);
        RetryBackoff backoff = new RetryBackoff(Duration.ofSeconds(1), Duration.ofSeconds(2), () -> 0);
        assertThatThrownBy(() -> backoff.notBefore(0, NOW, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> backoff.notBefore(1, null, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void tinyAndHugeDurationsStayPositiveAndBounded() {
        RetryBackoff tiny = new RetryBackoff(Duration.ofNanos(1), Duration.ofNanos(1), () -> 0.0);
        assertThat(tiny.notBefore(1, NOW, null)).isEqualTo(NOW.plusNanos(1));

        RetryBackoff large = new RetryBackoff(Duration.ofSeconds(Long.MAX_VALUE),
                Duration.ofSeconds(Long.MAX_VALUE), () -> Math.nextDown(1.0));
        assertThat(large.notBefore(1, NOW, null)).isEqualTo(Instant.MAX);

        RetryBackoff nearUpper = new RetryBackoff(Duration.ofSeconds(2), Duration.ofSeconds(2),
                () -> Math.nextDown(1.0));
        assertThat(nearUpper.notBefore(1, NOW, null)).isBefore(NOW.plusSeconds(2));
    }

    @Test
    void rejectsRandomOutsideUnitInterval() {
        RetryBackoff backoff = new RetryBackoff(Duration.ofSeconds(1), Duration.ofSeconds(2), () -> 1.0);
        assertThatThrownBy(() -> backoff.notBefore(1, NOW, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
