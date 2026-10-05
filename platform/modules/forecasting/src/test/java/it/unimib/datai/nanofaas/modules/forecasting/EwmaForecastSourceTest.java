package it.unimib.datai.nanofaas.modules.forecasting;
import it.unimib.datai.nanofaas.forecastingapi.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
class EwmaForecastSourceTest {
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-04T12:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    final MutableClock clock = new MutableClock();
    final Instant start = clock.instant();
    final EwmaForecastSource source = new EwmaForecastSource("edge", 0.5, Duration.ofSeconds(10), Duration.ofSeconds(30), 100, clock);
    ForecastQuery query(long generation) { return new ForecastQuery("edge", "f", generation, clock.instant(), clock.instant().plusSeconds(300)); }
    void arrivals(int count, long offset) {
        for (int i = 0; i < count; i++) source.record(new ExternalArrival("f", 1, start.plusSeconds(offset), "r" + offset + "-" + i));
    }
    @Test void smoothsCompletedObservedWindowsAndKeepsGenerationsSeparate() {
        assertThat(source.forecast(query(1)).status()).isEqualTo(ForecastSnapshot.Status.MISSING);
        source.observe("f", 1, start);
        arrivals(100, 1); clock.now = start.plusSeconds(10);
        assertThat(source.forecast(query(1)).rate()).isEqualTo(10);
        arrivals(200, 11); clock.now = start.plusSeconds(20);
        assertThat(source.forecast(query(1)).rate()).isEqualTo(15);
        assertThat(source.forecast(query(2)).status()).isEqualTo(ForecastSnapshot.Status.MISSING);
    }
    @Test void zeroRequiresACompleteObservedWindowAndBoundaryArrivalGoesToNext() {
        source.observe("f", 1, start); arrivals(1, 10);
        assertThat(source.forecast(query(1)).status()).isEqualTo(ForecastSnapshot.Status.MISSING);
        clock.now = start.plusSeconds(10);
        assertThat(source.forecast(query(1)).rate()).isEqualTo(0);
        clock.now = start.plusSeconds(20);
        assertThat(source.forecast(query(1)).rate()).isEqualTo(0.05);
    }
    @Test void longGapPreservesEarlierSamplesWithoutUnboundedWindowIteration() {
        var slowDecay = new EwmaForecastSource("edge", 0.001, Duration.ofSeconds(10), Duration.ofHours(2), 100, clock);
        slowDecay.observe("f", 1, start);
        for (int i = 0; i < 100; i++) slowDecay.record(new ExternalArrival("f", 1, start.plusSeconds(1), "r" + i));
        clock.now = start.plusSeconds(6010);
        assertThat(slowDecay.forecast(query(1)).rate()).isCloseTo(10 * Math.pow(0.999, 600), org.assertj.core.data.Offset.offset(1e-9));
    }
}
