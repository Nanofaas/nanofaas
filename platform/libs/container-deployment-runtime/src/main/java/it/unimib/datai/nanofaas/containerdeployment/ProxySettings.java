package it.unimib.datai.nanofaas.containerdeployment;


import java.time.Duration;

public record ProxySettings(
        int maxRequestBytes,
        int maxResponseBytes,
        long maxBufferedBytes,
        Duration inboundReadTimeout,
        Duration responseWriteTimeout
) {
    static final int DEFAULT_MAX_REQUEST_BYTES = 2 * 1024 * 1024;
    static final int DEFAULT_MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    static final long DEFAULT_MAX_BUFFERED_BYTES = 32L * 1024 * 1024;
    static final Duration DEFAULT_INBOUND_READ_TIMEOUT = Duration.ofSeconds(5);
    static final Duration DEFAULT_RESPONSE_WRITE_TIMEOUT = Duration.ofSeconds(5);

    public ProxySettings {
        maxRequestBytes = positiveOrDefault(maxRequestBytes, DEFAULT_MAX_REQUEST_BYTES, "maxRequestBytes");
        maxResponseBytes = positiveOrDefault(maxResponseBytes, DEFAULT_MAX_RESPONSE_BYTES, "maxResponseBytes");
        if (maxBufferedBytes == 0) {
            maxBufferedBytes = DEFAULT_MAX_BUFFERED_BYTES;
        } else if (maxBufferedBytes < 0) {
            throw new IllegalArgumentException("maxBufferedBytes must be positive");
        }
        inboundReadTimeout = positiveOrDefault(
                inboundReadTimeout, DEFAULT_INBOUND_READ_TIMEOUT, "inboundReadTimeout");
        responseWriteTimeout = positiveOrDefault(
                responseWriteTimeout, DEFAULT_RESPONSE_WRITE_TIMEOUT, "responseWriteTimeout");
    }

    public Duration executionProbeTimeout() { return Duration.ofSeconds(2); }
    public Duration executionProbeRetention() { return Duration.ofMinutes(10); }
    public static ProxySettings defaults() {
        return new ProxySettings(0, 0, 0, null, null);
    }

    private static int positiveOrDefault(int value, int defaultValue, String name) {
        if (value == 0) {
            return defaultValue;
        }
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Duration positiveOrDefault(Duration value, Duration defaultValue, String name) {
        if (value == null) {
            return defaultValue;
        }
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be a positive duration");
        }
        return value;
    }
}
