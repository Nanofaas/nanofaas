package it.unimib.datai.nanofaas.modules.forecasting;

import it.unimib.datai.nanofaas.forecastingapi.ExternalArrival;
import it.unimib.datai.nanofaas.forecastingapi.ForecastQuery;
import it.unimib.datai.nanofaas.forecastingapi.ForecastSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

class ForecastConcurrencyTest {
    private static final class MutableClock extends Clock {
        volatile Instant now = Instant.EPOCH;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @ParameterizedTest
    @EnumSource(value = ForecastingProperties.Provider.class, names = {"ORACLE", "EWMA", "ARI"})
    void inFlightQueryKeepsItsSelectedProviderAndParameters(ForecastingProperties.Provider next) throws Exception {
        var clock = new MutableClock();
        var props = new ForecastingProperties(true, "edge", ForecastingProperties.Provider.ARI, null,
                Duration.ofSeconds(1), Duration.ofMinutes(1), null, null, new ForecastingProperties.Ari(1), 512);
        var config = new ForecastingConfiguration();
        var selection = config.forecastSelection(props);
        var trend = new TrendForecastSource("edge", props.window(), props.maxAge(), 10, 512,
                () -> selection.get().trendModel(), clock);
        populateRamp(trend, clock);
        var source = config.selectedForecastSource(props, selection, config.ewmaForecastSource(props),
                trend, config.oracleForecastStore(props));
        var namespace = config.forecastingRuntimeConfigExtension(props, selection);
        var query = query(4, "f");
        var forecast = new FutureTask<>(() -> source.forecast(query));
        var worker = new Thread(forecast, "forecast-switch-regression");
        try {
            synchronized (trend) {
                worker.start();
                awaitBlocked(worker);
                namespace.apply(Map.of("provider", next.id(), "ariOrder", 64));
            }
            var snapshot = forecast.get(5, TimeUnit.SECONDS);
            assertThat(snapshot.provider()).isEqualTo("ari");
            // ARI(1) extends the four-window ramp; ARI(64) would hold its last rate at 4.
            assertThat(snapshot.rate()).isCloseTo(5, offset(1e-6));
            assertThat(source.forecast(query).provider()).isEqualTo(next.id());
            if (next == ForecastingProperties.Provider.ARI) {
                assertThat(source.forecast(query).rate()).isEqualTo(4);
            }
        } finally {
            forecast.cancel(true);
            worker.join(5_000);
        }
    }

    @Test
    void ariCalculationDoesNotBlockArrivalsOrChangeItsSnapshot() throws Exception {
        var clock = new MutableClock();
        var model = new AtomicReference<TrendForecastSource.Model>(new TrendForecastSource.Ari(1));
        var source = new TrendForecastSource("edge", Duration.ofSeconds(1), Duration.ofMinutes(1),
                10, 512, model::get, clock);
        populateRamp(source, clock);
        source.observe("g", 1, Instant.EPOCH);
        var fitting = new CountDownLatch(1);
        var resumeFit = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            try {
                var pending = workers.submit(() -> {
                    // Pause at the real calculation boundary, then execute the actual ARI fit.
                    // This controls interleaving without relying on a large/slow input or sleeps.
                    try (var arithmetic = mockStatic(TrendForecastSource.class, CALLS_REAL_METHODS)) {
                        arithmetic.when(() -> TrendForecastSource.ari(any(), any(), anyInt())).thenAnswer(call -> {
                            fitting.countDown();
                            assertThat(resumeFit.await(10, TimeUnit.SECONDS)).isTrue();
                            return call.callRealMethod();
                        });
                        return source.forecast(query(4, "f"));
                    }
                });
                assertThat(fitting.await(5, TimeUnit.SECONDS)).isTrue();
                clock.now = Instant.ofEpochSecond(4, 500_000_000);
                var recorded = workers.submit(() -> {
                    source.record(new ExternalArrival("f", 1, clock.instant(), "during-fit-f"));
                    source.record(new ExternalArrival("g", 1, clock.instant(), "during-fit-g"));
                });
                recorded.get(5, TimeUnit.SECONDS);

                clock.now = Instant.ofEpochSecond(5);
                model.set(new TrendForecastSource.Holt(1, 1, 1));
                var newer = source.forecast(query(5, "f"));
                assertThat(newer.provider()).isEqualTo("holt");
                assertThat(newer.revision()).isEqualTo(5);
                assertThat(newer.rate()).isZero();
                assertThat(source.forecast(query(5, "g")).rate()).isEqualTo(2);

                resumeFit.countDown();
                var original = pending.get(5, TimeUnit.SECONDS);
                assertThat(original.provider()).isEqualTo("ari");
                assertThat(original.status()).isEqualTo(ForecastSnapshot.Status.AVAILABLE);
                assertThat(original.rate()).isCloseTo(5, offset(1e-6));
                assertThat(original.revision()).isEqualTo(4);
                assertThat(original.producedAt()).isEqualTo(Instant.ofEpochSecond(4));

                // With insufficient history ARI holds the last completed window: neither arrival was lost.
                model.set(new TrendForecastSource.Ari(64));
                assertThat(source.forecast(query(5, "f")).rate()).isEqualTo(1);
                assertThat(source.forecast(query(5, "g")).rate()).isEqualTo(1);
            } finally {
                resumeFit.countDown();
            }
        }
    }

    private static void populateRamp(TrendForecastSource source, MutableClock clock) {
        source.observe("f", 1, Instant.EPOCH);
        for (int window = 0; window < 4; window++) {
            clock.now = Instant.ofEpochSecond(window);
            for (int arrival = 0; arrival <= window; arrival++) {
                source.record(new ExternalArrival("f", 1, clock.instant(), "r" + window + "-" + arrival));
            }
        }
        clock.now = Instant.ofEpochSecond(4);
    }

    private static ForecastQuery query(long second, String function) {
        return new ForecastQuery("edge", function, 1, Instant.ofEpochSecond(second), Instant.ofEpochSecond(second + 1));
    }

    private static void awaitBlocked(Thread worker) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (worker.getState() != Thread.State.BLOCKED && worker.isAlive() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(worker.getState()).isEqualTo(Thread.State.BLOCKED);
    }
}
