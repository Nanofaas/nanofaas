package it.unimib.datai.nanofaas.modules.offload.oneshot.coordination;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
class EpochCoordinatorTest {
    @Test void staleOrLargeClockOffsetPreventsConfirmation() {
        var now=new AtomicReference<>(Instant.parse("2026-10-04T10:00:00Z"));
        var health=new ClockHealth(Duration.ofMillis(100),Duration.ofSeconds(10),now::get);
        assertThat(health.healthy()).isFalse(); health.sample(Duration.ofMillis(-50),now.get()); assertThat(health.healthy()).isTrue();
        health.sample(Duration.ofMillis(101),now.get()); assertThat(health.healthy()).isFalse();
        health.sample(Duration.ZERO,now.get()); now.set(now.get().plusSeconds(11)); assertThat(health.healthy()).isFalse();
    }
}
