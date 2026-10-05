package it.unimib.datai.nanofaas.modules.forecasting;
import it.unimib.datai.nanofaas.forecastingapi.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
class OracleForecastStoreTest {
    final Instant start = Instant.parse("2026-10-04T12:00:00Z");
    final Clock clock = Clock.fixed(start, ZoneOffset.UTC);
    final OracleForecastStore store = new OracleForecastStore(clock, Duration.ofMinutes(10));
    OracleForecastStore.Trace trace(long revision, List<OracleForecastStore.Entry> rows) {
        return new OracleForecastStore.Trace(1, "edge", revision, "oracle", start, rows);
    }
    OracleForecastStore.Entry row(long generation, Instant from, Instant to, double rate) {
        return new OracleForecastStore.Entry("f", generation, from, to, rate, "requests/s");
    }
    ForecastQuery query(long generation, Instant from, Instant to) { return new ForecastQuery("edge", "f", generation, from, to); }
    @Test void replacesAtomicallyAndDeliveredSnapshotsStayImmutable() {
        store.replace(0, trace(1, List.of(row(1, start, start.plusSeconds(300), 10))));
        var old = store.forecast(query(1, start, start.plusSeconds(300)));
        store.replace(1, trace(2, List.of(row(1, start, start.plusSeconds(300), 20))));
        assertThat(old.rate()).isEqualTo(10);
        assertThat(store.forecast(query(1, start, start.plusSeconds(300))).rate()).isEqualTo(20);
        assertThatThrownBy(() -> store.replace(1, trace(3, List.of()))).isInstanceOf(OracleForecastStore.RevisionConflict.class);
        assertThat(store.revision()).isEqualTo(2);
    }
    @Test void rejectsEntireInvalidTraceAndNeverLeaksAcrossGenerations() {
        var first = row(1, start, start.plusSeconds(300), 10);
        store.replace(0, trace(1, List.of(first)));
        assertThatThrownBy(() -> store.replace(1, trace(2, List.of(first, first)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.replace(1, trace(2, List.of(first, row(1, start.plusSeconds(200), start.plusSeconds(400), 20))))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.replace(1, trace(2, List.of(row(1, start, start.plusSeconds(300), Double.NaN))))).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.revision()).isEqualTo(1);
        assertThat(store.forecast(query(2, start, start.plusSeconds(300))).status()).isEqualTo(ForecastSnapshot.Status.MISSING);
    }
    @Test void averagesContiguousHalfOpenIntervalsAndRejectsGaps() {
        store.replace(0, trace(1, List.of(row(1, start, start.plusSeconds(100), 10), row(1, start.plusSeconds(100), start.plusSeconds(200), 20))));
        assertThat(store.forecast(query(1, start, start.plusSeconds(200))).rate()).isEqualTo(15);
        assertThat(store.forecast(query(1, start.plusSeconds(100), start.plusSeconds(200))).rate()).isEqualTo(20);
        assertThat(store.forecast(query(1, start.plusSeconds(200), start.plusSeconds(300))).status()).isEqualTo(ForecastSnapshot.Status.MISSING);
    }
}
