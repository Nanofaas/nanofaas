package it.unimib.datai.nanofaas.forecastingapi;

import java.time.Instant;
import java.util.Objects;
/** One valid external HTTP arrival, including explicit client repeats, never an internal attempt. */
public record ExternalArrival(String function, long generation, Instant at, String requestId) {
    public ExternalArrival {
        if (function == null || function.isBlank() || generation < 1 || requestId == null || requestId.isBlank())
            throw new IllegalArgumentException("function, positive generation and request id required");
        Objects.requireNonNull(at);
    }
}
