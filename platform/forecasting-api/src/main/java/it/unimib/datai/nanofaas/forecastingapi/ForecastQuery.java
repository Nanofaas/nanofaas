package it.unimib.datai.nanofaas.forecastingapi;

import java.time.Instant;
import java.util.Objects;
public record ForecastQuery(String nodeId, String function, long generation, Instant start, Instant end) {
    public ForecastQuery {
        if (nodeId == null || nodeId.isBlank() || function == null || function.isBlank() || generation < 1)
            throw new IllegalArgumentException("node, function and positive generation required");
        Objects.requireNonNull(start); Objects.requireNonNull(end);
        if (!start.isBefore(end)) throw new IllegalArgumentException("nonempty forecast interval required");
    }
}
