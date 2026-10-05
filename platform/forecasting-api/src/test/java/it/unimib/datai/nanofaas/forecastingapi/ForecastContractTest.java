package it.unimib.datai.nanofaas.forecastingapi;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
class ForecastContractTest {
    final Instant start = Instant.parse("2026-10-04T10:00:00Z");
    final ForecastQuery query = new ForecastQuery("edge", "f", 1, start, start.plusSeconds(300));
    @Test void observedZeroDiffersFromMissingWindow() {
        var observed = new ForecastSnapshot(query, ForecastSnapshot.Status.AVAILABLE, 0.0, 1, "ewma", start);
        var missing = new ForecastSnapshot(query, ForecastSnapshot.Status.MISSING, null, 0, "ewma", null);
        assertEquals(0.0, observed.rate()); assertNull(missing.rate());
        assertNotEquals(observed.status(), missing.status());
    }
    @Test void invalidRatesAndIntervalsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ForecastQuery("edge", "f", 1, start, start));
        assertThrows(IllegalArgumentException.class, () -> new ForecastSnapshot(query, ForecastSnapshot.Status.AVAILABLE, Double.NaN, 1, "oracle", start));
        assertThrows(IllegalArgumentException.class, () -> new ForecastSnapshot(query, ForecastSnapshot.Status.AVAILABLE, -1.0, 1, "oracle", start));
        assertThrows(IllegalArgumentException.class, () -> new ForecastSnapshot(query, ForecastSnapshot.Status.MISSING, 0.0, 0, "oracle", null));
    }
    @Test void generationsAndEventsCarryNoPayload() {
        assertNotEquals(query, new ForecastQuery("edge", "f", 2, start, start.plusSeconds(300)));
        var event = new ExternalArrival("f", 1, start, "original");
        assertEquals("original", event.requestId());
        assertDoesNotThrow(() -> ExternalArrivalObserver.noOp().record(event));
    }
}
