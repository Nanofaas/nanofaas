package it.unimib.datai.nanofaas.forecastingapi;

import java.time.Instant;
import java.util.Objects;
/** Immutable future external-arrival rate in requests/s; missing is distinct from observed zero. */
public record ForecastSnapshot(ForecastQuery query, Status status, Double rate, long revision,
                               String provider, Instant producedAt) {
    public enum Status { AVAILABLE, MISSING, STALE }
    public ForecastSnapshot {
        Objects.requireNonNull(query); Objects.requireNonNull(status);
        if (provider == null || provider.isBlank() || revision < 0)
            throw new IllegalArgumentException("provider and nonnegative revision required");
        if (rate != null && (!Double.isFinite(rate) || rate < 0))
            throw new IllegalArgumentException("finite nonnegative requests/s required");
        if (status == Status.AVAILABLE && (rate == null || producedAt == null || revision < 1))
            throw new IllegalArgumentException("available forecasts require rate, time and revision");
        if (status == Status.MISSING && rate != null)
            throw new IllegalArgumentException("missing forecasts have no observed rate");
    }
}
