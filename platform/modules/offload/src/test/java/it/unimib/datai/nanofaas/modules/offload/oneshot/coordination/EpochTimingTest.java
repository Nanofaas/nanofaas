package it.unimib.datai.nanofaas.modules.offload.oneshot.coordination;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.assertThat;

class EpochTimingTest {
    private static final Instant LABEL = Instant.parse("2020-01-01T00:00:00Z");

    @Test
    void canonicalUtcLabelsDoNotAuthorizeOrExpireMonotonicBudgets() {
        var nano = new AtomicLong(100);
        var timing = EpochTiming.capture("session", LABEL, LABEL.plusSeconds(360), Duration.ofSeconds(5), nano::get);
        assertThat(timing.startsAt()).isEqualTo(LABEL);
        assertThat(timing.endsAt()).isEqualTo(LABEL.plusSeconds(360));
        long auction = timing.auctionDeadline(Duration.ofSeconds(2));
        assertThat(timing.expired(auction)).isFalse();
        nano.addAndGet(Duration.ofSeconds(2).toNanos());
        assertThat(timing.expired(auction)).isTrue();
        assertThat(timing.remaining(auction)).isZero();
        assertThat(timing.expired(timing.activationDeadline())).isFalse();
        nano.addAndGet(Duration.ofSeconds(3).toNanos());
        assertThat(timing.expired(timing.activationDeadline())).isTrue();
        assertThat(timing.expired(timing.expiryDeadline())).isFalse();
        nano.addAndGet(Duration.ofSeconds(360).toNanos());
        assertThat(timing.expired(timing.expiryDeadline())).isTrue();
    }

    @Test
    void deadlineArithmeticSurvivesSignedNanoCounterWrap() {
        var nano = new AtomicLong(Long.MAX_VALUE - 10);
        var timing = EpochTiming.capture("session", LABEL, LABEL.plusSeconds(60), Duration.ofNanos(100), nano::get);
        long deadline = timing.auctionDeadline(Duration.ofNanos(50));
        assertThat(timing.expired(deadline)).isFalse();
        nano.addAndGet(49);
        assertThat(timing.remaining(deadline)).isEqualTo(Duration.ofNanos(1));
        nano.incrementAndGet();
        assertThat(timing.expired(deadline)).isTrue();
    }
}
