package it.unimib.datai.nanofaas.modules.forecasting;
import it.unimib.datai.nanofaas.forecastingapi.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.data.Offset.offset;
class TrendForecastSourceTest {
    final EwmaForecastSourceTest.MutableClock clock = new EwmaForecastSourceTest.MutableClock();
    final Instant start = clock.instant();
    final AtomicReference<TrendForecastSource.Model> model = new AtomicReference<>(new TrendForecastSource.Holt(1, 1, 1));
    TrendForecastSource source(int history) {
        var source = new TrendForecastSource("edge", Duration.ofSeconds(10), Duration.ofSeconds(30), 100, history, model::get, clock);
        source.observe("f", 1, start);
        return source;
    }
    /** One complete 10 s window per rate, then the clock at the end of the last one. */
    void windows(TrendForecastSource source, double... rates) {
        for (int w = 0; w < rates.length; w++) {
            // Arrivals more than one window ahead of the clock are not counted.
            clock.now = start.plusSeconds(10L * w);
            for (int i = 0; i < Math.round(rates[w] * 10); i++)
                source.record(new ExternalArrival("f", 1, clock.now, "r" + w + "-" + i));
        }
        clock.now = start.plusSeconds(10L * rates.length);
    }
    ForecastSnapshot over(TrendForecastSource source, long fromSeconds, long untilSeconds) {
        return source.forecast(new ForecastQuery("edge", "f", 1, clock.now.plusSeconds(fromSeconds), clock.now.plusSeconds(untilSeconds)));
    }
    @Test void holtExtendsALinearRampAveragedOverTheQueriedWindows() {
        var source = source(360);
        assertThat(over(source, 0, 20).status()).isEqualTo(ForecastSnapshot.Status.MISSING);
        windows(source, 1, 2, 3, 4);
        var snapshot = over(source, 0, 20);
        assertThat(snapshot.status()).isEqualTo(ForecastSnapshot.Status.AVAILABLE);
        assertThat(snapshot.provider()).isEqualTo("holt");
        assertThat(snapshot.rate()).isCloseTo(5.5, offset(1e-9));
        assertThat(over(source, 5, 15).rate()).isCloseTo(5.5, offset(1e-9));
        assertThat(over(source, 12, 20).rate()).isCloseTo(6, offset(1e-9));
    }
    @Test void holtDampingShrinksEachFurtherStep() {
        model.set(new TrendForecastSource.Holt(1, 1, 0.5));
        var source = source(360);
        windows(source, 1, 2, 3, 4);
        // Level 4, slope damped from 1: +0.5, then +0.75.
        assertThat(over(source, 0, 20).rate()).isCloseTo((4.5 + 4.75) / 2, offset(1e-9));
    }
    @Test void ariContinuesASteadyChangeAndHoldsTheLatestRateWithoutHistory() {
        model.set(new TrendForecastSource.Ari(1));
        var shortHistory = source(360);
        windows(shortHistory, 1, 2, 3);
        assertThat(over(shortHistory, 0, 20).rate()).isCloseTo(3, offset(1e-9));
        clock.now = start;
        var ramp = new TrendForecastSource("edge", Duration.ofSeconds(10), Duration.ofSeconds(30), 100, 360, model::get, clock);
        ramp.observe("f", 1, start);
        windows(ramp, 1, 2, 3, 4, 5, 6, 7, 8);
        var snapshot = over(ramp, 0, 20);
        assertThat(snapshot.provider()).isEqualTo("ari");
        assertThat(snapshot.rate()).isCloseTo(9.5, offset(1e-6));
    }
    @Test void ariStaysWithinTwiceTheRecentPeak() {
        model.set(new TrendForecastSource.Ari(1));
        var source = source(360);
        windows(source, 1, 2, 4, 8, 16);
        // Doubling changes would reach 32 then 64; the cap holds 2 x 16.
        assertThat(over(source, 0, 30).rate()).isCloseTo(32, offset(1e-6));
    }
    @Test void aSwitchedModelAnswersTheNextQueryFromTheSameWindows() {
        var source = source(360);
        windows(source, 1, 2, 3, 4);
        assertThat(over(source, 0, 10).provider()).isEqualTo("holt");
        model.set(new TrendForecastSource.Ari(1));
        assertThat(over(source, 0, 10).provider()).isEqualTo("ari");
        assertThat(over(source, 0, 10).rate()).isCloseTo(5, offset(1e-6));
    }
    @Test void aLongIdleGapCostsNoMoreThanTheHistory() {
        var source = source(8);
        windows(source, 5, 5);
        clock.now = start.plus(Duration.ofDays(3650));
        var snapshot = over(source, 0, 10);
        assertThat(snapshot.status()).isEqualTo(ForecastSnapshot.Status.AVAILABLE);
        assertThat(snapshot.rate()).isZero();
    }
    @Test void invalidParametersAreRefused() {
        assertThatThrownBy(() -> new TrendForecastSource.Holt(0, 0.5, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrendForecastSource.Holt(0.5, 0.5, 1.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TrendForecastSource.Ari(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> source(3)).isInstanceOf(IllegalArgumentException.class);
    }
}
