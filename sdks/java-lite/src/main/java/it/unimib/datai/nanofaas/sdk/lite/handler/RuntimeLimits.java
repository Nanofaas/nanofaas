package it.unimib.datai.nanofaas.sdk.lite.handler;

import java.util.concurrent.atomic.AtomicBoolean;

final class RuntimeLimits {
    static final int DEFAULT_HANDLERS = 32;
    static final int DEFAULT_CALLBACKS = 128;
    static final int DEFAULT_INPUT_BYTES = 1024 * 1024;
    static final int DEFAULT_OUTPUT_BYTES = 1024 * 1024;
    static final int DEFAULT_CALLBACK_BYTES = 2 * 1024 * 1024;
    static final long DEFAULT_PENDING_CALLBACK_BYTES = 16L * 1024 * 1024;
    static final int DEFAULT_BODY_READ_TIMEOUT_MS = 10_000;
    static final int DEFAULT_CALLBACK_ATTEMPT_TIMEOUT_MS = 10_000;
    static final int DEFAULT_CALLBACK_MAX_ATTEMPTS = 3;
    static final int DEFAULT_SHUTDOWN_TIMEOUT_MS = 5_000;

    final int maxInputBytes;
    final int maxOutputBytes;
    final int maxCallbackBytes;
    final int bodyReadTimeoutMs;
    final int callbackAttemptTimeoutMs;
    final int callbackMaxAttempts;
    final int shutdownTimeoutMs;
    private final int maxHandlers;
    private final int maxCallbacks;
    private final long maxPendingCallbackBytes;
    private int activeHandlers;
    private int pendingCallbacks;
    private long pendingCallbackBytes;
    private boolean accepting = true;

    RuntimeLimits(int maxHandlers, int maxCallbacks, long maxPendingCallbackBytes,
                  int maxInputBytes, int maxOutputBytes, int maxCallbackBytes) {
        this(maxHandlers, maxCallbacks, maxPendingCallbackBytes, maxInputBytes, maxOutputBytes,
                maxCallbackBytes, DEFAULT_BODY_READ_TIMEOUT_MS, DEFAULT_CALLBACK_ATTEMPT_TIMEOUT_MS,
                DEFAULT_CALLBACK_MAX_ATTEMPTS, DEFAULT_SHUTDOWN_TIMEOUT_MS);
    }

    RuntimeLimits(int maxHandlers, int maxCallbacks, long maxPendingCallbackBytes, // NOSONAR (java:S107): composition constructor; each argument is an injected collaborator or limit
                  int maxInputBytes, int maxOutputBytes, int maxCallbackBytes,
                  int bodyReadTimeoutMs, int callbackAttemptTimeoutMs, int callbackMaxAttempts,
                  int shutdownTimeoutMs) {
        if (maxHandlers <= 0 || maxCallbacks <= 0 || maxPendingCallbackBytes <= 0
                || maxInputBytes <= 0 || maxOutputBytes <= 0 || maxCallbackBytes <= 0
                || maxCallbackBytes > maxPendingCallbackBytes || bodyReadTimeoutMs <= 0
                || callbackAttemptTimeoutMs <= 0 || callbackMaxAttempts <= 0 || shutdownTimeoutMs <= 0) {
            throw new IllegalArgumentException("runtime limits must be finite, positive and internally consistent");
        }
        this.maxHandlers = maxHandlers;
        this.maxCallbacks = maxCallbacks;
        this.maxPendingCallbackBytes = maxPendingCallbackBytes;
        this.maxInputBytes = maxInputBytes;
        this.maxOutputBytes = maxOutputBytes;
        this.maxCallbackBytes = maxCallbackBytes;
        this.bodyReadTimeoutMs = bodyReadTimeoutMs;
        this.callbackAttemptTimeoutMs = callbackAttemptTimeoutMs;
        this.callbackMaxAttempts = callbackMaxAttempts;
        this.shutdownTimeoutMs = shutdownTimeoutMs;
    }

    static RuntimeLimits fromSettings() {
        return new RuntimeLimits(
                setting("nanofaas.handler.max.concurrent", "NANOFAAS_MAX_CONCURRENT_HANDLERS", DEFAULT_HANDLERS),
                setting("nanofaas.callback.max.pending", "NANOFAAS_MAX_PENDING_CALLBACKS", DEFAULT_CALLBACKS),
                settingLong("nanofaas.callback.max.pending.bytes", "NANOFAAS_MAX_PENDING_CALLBACK_BYTES", DEFAULT_PENDING_CALLBACK_BYTES),
                setting("nanofaas.input.max.bytes", "NANOFAAS_MAX_INPUT_BYTES", DEFAULT_INPUT_BYTES),
                setting("nanofaas.output.max.bytes", "NANOFAAS_MAX_OUTPUT_BYTES", DEFAULT_OUTPUT_BYTES),
                setting("nanofaas.callback.max.payload.bytes", "NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES", DEFAULT_CALLBACK_BYTES),
                setting("nanofaas.body.read.timeout.ms", "NANOFAAS_BODY_READ_TIMEOUT_MS", DEFAULT_BODY_READ_TIMEOUT_MS),
                setting("nanofaas.callback.attempt.timeout.ms", "NANOFAAS_CALLBACK_ATTEMPT_TIMEOUT_MS", DEFAULT_CALLBACK_ATTEMPT_TIMEOUT_MS),
                setting("nanofaas.callback.max.attempts", "NANOFAAS_CALLBACK_MAX_ATTEMPTS", DEFAULT_CALLBACK_MAX_ATTEMPTS),
                setting("nanofaas.shutdown.timeout.ms", "NANOFAAS_SHUTDOWN_TIMEOUT_MS", DEFAULT_SHUTDOWN_TIMEOUT_MS));
    }

    synchronized Reservation tryReserveHandler() {
        if (!accepting || activeHandlers >= maxHandlers) return null;
        activeHandlers++;
        return new Reservation(this::releaseHandler);
    }

    synchronized Reservation tryReserveCallback() {
        if (!accepting || pendingCallbacks >= maxCallbacks
                || maxCallbackBytes > maxPendingCallbackBytes - pendingCallbackBytes) return null;
        pendingCallbacks++;
        pendingCallbackBytes += maxCallbackBytes;
        return new Reservation(this::releaseCallback);
    }

    synchronized void stopAdmission() { accepting = false; }
    synchronized boolean handlerCapacityExhausted() { return activeHandlers >= maxHandlers; }
    synchronized int activeHandlers() { return activeHandlers; }
    synchronized int pendingCallbacks() { return pendingCallbacks; }
    synchronized long pendingCallbackBytes() { return pendingCallbackBytes; }
    private synchronized void releaseHandler() { activeHandlers--; }
    private synchronized void releaseCallback() { pendingCallbacks--; pendingCallbackBytes -= maxCallbackBytes; }

    private static int setting(String property, String environment, int fallback) {
        long value = settingLong(property, environment, fallback);
        if (value > Integer.MAX_VALUE) throw new IllegalArgumentException(property + " is too large");
        return (int) value;
    }

    private static long settingLong(String property, String environment, long fallback) {
        String raw = System.getProperty(property, System.getenv(environment));
        if (raw == null || raw.isBlank()) return fallback;
        try {
            long value = Long.parseLong(raw);
            if (value <= 0) throw new IllegalArgumentException(property + " must be positive");
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(property + " must be a finite positive integer", ex);
        }
    }

    static final class Reservation implements AutoCloseable {
        private final Runnable release;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Reservation(Runnable release) { this.release = release; }
        @Override public void close() { if (closed.compareAndSet(false, true)) release.run(); }
    }
}
