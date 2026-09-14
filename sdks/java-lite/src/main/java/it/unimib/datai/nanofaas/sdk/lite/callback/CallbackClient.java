// Parallel implementation exists in function-sdk-java (same retry logic, different HTTP stack:
// Spring RestClient instead of java.net.http.HttpClient). Keep retry constants and URL-building
// logic in sync when modifying.
package it.unimib.datai.nanofaas.sdk.lite.callback;

import it.unimib.datai.nanofaas.common.model.InvocationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unimib.datai.nanofaas.sdk.lite.handler.BoundedJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CallbackClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CallbackClient.class);
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final int DEFAULT_MAX_PAYLOAD_BYTES = 2 * 1024 * 1024;
    private static final int[] DEFAULT_RETRY_DELAYS_MS = {100, 500};

    private final HttpClient httpClient;
    private final String baseUrl;
    private final boolean ownsHttpClient;
    private final Duration attemptTimeout;
    private final int maxAttempts;
    private final int[] retryDelaysMs;
    private final BoundedJson boundedJson;
    private final int maxPayloadBytes;
    private final AtomicBoolean closed = new AtomicBoolean();

    public CallbackClient(ObjectMapper objectMapper, String baseUrl) {
        this((baseUrl != null && !baseUrl.isBlank())
                ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                : null, objectMapper, baseUrl,
                Duration.ofMillis(setting("nanofaas.callback.attempt.timeout.ms",
                        "NANOFAAS_CALLBACK_ATTEMPT_TIMEOUT_MS", 10_000)),
                setting("nanofaas.callback.max.attempts", "NANOFAAS_CALLBACK_MAX_ATTEMPTS",
                        DEFAULT_MAX_ATTEMPTS), DEFAULT_RETRY_DELAYS_MS,
                setting("nanofaas.callback.max.payload.bytes", "NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES",
                        DEFAULT_MAX_PAYLOAD_BYTES), true);
    }

    CallbackClient(ObjectMapper objectMapper, String baseUrl, int maxPayloadBytes) {
        this((baseUrl != null && !baseUrl.isBlank()) ? HttpClient.newHttpClient() : null,
                objectMapper, baseUrl, Duration.ofSeconds(10), DEFAULT_MAX_ATTEMPTS,
                DEFAULT_RETRY_DELAYS_MS, maxPayloadBytes, true);
    }

    // Visible for testing
    CallbackClient(HttpClient httpClient, ObjectMapper objectMapper, String baseUrl) {
        this(httpClient, objectMapper, baseUrl, Duration.ofSeconds(10), DEFAULT_MAX_ATTEMPTS,
                DEFAULT_RETRY_DELAYS_MS, DEFAULT_MAX_PAYLOAD_BYTES, false);
    }

    CallbackClient(HttpClient httpClient, ObjectMapper objectMapper, String baseUrl,
                   Duration attemptTimeout, int maxAttempts, int[] retryDelaysMs) {
        this(httpClient, objectMapper, baseUrl, attemptTimeout, maxAttempts, retryDelaysMs,
                DEFAULT_MAX_PAYLOAD_BYTES, false);
    }

    private CallbackClient(HttpClient httpClient, ObjectMapper objectMapper, String baseUrl,
                           Duration attemptTimeout, int maxAttempts, int[] retryDelaysMs,
                           int maxPayloadBytes, boolean ownsHttpClient) {
        if (maxPayloadBytes <= 0) throw new IllegalArgumentException("max payload bytes must be positive");
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
        this.ownsHttpClient = ownsHttpClient && httpClient != null;
        this.attemptTimeout = attemptTimeout;
        this.maxAttempts = maxAttempts;
        this.retryDelaysMs = retryDelaysMs.clone();
        this.boundedJson = new BoundedJson(objectMapper);
        this.maxPayloadBytes = maxPayloadBytes;
    }

    @Override
    public void close() {
        close(Duration.ofSeconds(5));
    }

    public void close(Duration timeout) {
        if (!ownsHttpClient || httpClient == null || !closed.compareAndSet(false, true)) {
            return;
        }
        httpClient.shutdown();
        if (timeout.isZero() || timeout.isNegative()) {
            httpClient.shutdownNow();
            return;
        }
        try {
            if (!httpClient.awaitTermination(timeout)) {
                httpClient.shutdownNow();
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            httpClient.shutdownNow();
        }
    }

    private static boolean isPermanentFailure(int status) {
        return status >= 400 && status < 500 && status != 408 && status != 429;
    }

    private boolean pauseBeforeRetry(int attempt, String executionId) {
        try {
            if (attempt < retryDelaysMs.length) Thread.sleep(retryDelaysMs[attempt]);
            return true;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            log.warn("Callback retry interrupted for execution {}", executionId);
            return false;
        }
    }

    public boolean sendResult(String executionId, InvocationResult result, String traceId) {
        return sendResult(executionId, result, traceId, null);
    }

    public boolean sendResult(String executionId, InvocationResult result, String traceId, String dispatchAttempt) {
        final byte[] body;
        try {
            body = boundedJson.serialize(CallbackPayload.from(result), maxPayloadBytes);
        } catch (BoundedJson.PayloadTooLargeException | BoundedJson.SerializationException ex) {
            log.error("Failed to serialize callback payload for execution {}", executionId, ex);
            return false;
        }
        return sendSerializedResult(executionId, body, traceId, dispatchAttempt);
    }

    public boolean sendSerializedResult(String executionId, byte[] body, String traceId, String dispatchAttempt) {
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("CALLBACK_URL not configured, skipping callback for execution {}", executionId);
            return false;
        }
        if (executionId == null || executionId.isBlank()) {
            log.warn("executionId is null or blank, skipping callback");
            return false;
        }

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            SendStatus outcome = attemptSend(executionId, body, traceId, dispatchAttempt, attempt);
            if (outcome == SendStatus.SUCCESS) {
                return true;
            }
            if (outcome != SendStatus.RETRYABLE) {
                return false;
            }
            if (attempt < maxAttempts - 1 && !pauseBeforeRetry(attempt, executionId)) return false;
        }
        log.error("All {} callback attempts failed for execution {}", maxAttempts, executionId);
        return false;
    }

    private SendStatus attemptSend(String executionId, byte[] body, String traceId, String dispatchAttempt, int attempt) {
        try {
            int status = doSend(executionId, body, traceId, dispatchAttempt);
            if (status >= 200 && status < 300) {
                log.debug("Callback sent successfully for execution {} (attempt {})", executionId, attempt + 1);
                return SendStatus.SUCCESS;
            }
            if (isPermanentFailure(status)) {
                log.error("Permanent callback failure for execution {} with status {}", executionId, status);
                return SendStatus.PERMANENT;
            }
            log.warn("Callback failed for execution {} (attempt {}) with status {}",
                    executionId, attempt + 1, status);
            return SendStatus.RETRYABLE;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Callback interrupted for execution {} (attempt {})", executionId, attempt + 1);
            return SendStatus.INTERRUPTED;
        } catch (Exception ex) {
            log.warn("Callback failed for execution {} (attempt {}): {}",
                    executionId, attempt + 1, ex.getMessage());
            return SendStatus.RETRYABLE;
        }
    }

    private int doSend(String executionId, byte[] body, String traceId, String dispatchAttempt)
            throws IOException, InterruptedException {
        String effectiveTraceId = (traceId != null && !traceId.isBlank())
                ? traceId
                : System.getenv("TRACE_ID");

        String url = callbackUrl(executionId);

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(attemptTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));

        if (effectiveTraceId != null && !effectiveTraceId.isBlank()) {
            reqBuilder.header("X-Trace-Id", effectiveTraceId);
        }
        if (dispatchAttempt != null && !dispatchAttempt.isBlank()) {
            reqBuilder.header("X-Dispatch-Attempt", dispatchAttempt);
        }

        return httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private String callbackUrl(String executionId) {
        String base = baseUrl.strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        int completeSuffix = base.lastIndexOf(":complete");
        if (completeSuffix >= 0) {
            int slash = base.lastIndexOf('/', completeSuffix);
            if (slash >= 0) base = base.substring(0, slash);
        }
        return base + "/" + executionId + ":complete";
    }

    private static int setting(String property, String environment, int fallback) {
        String raw = System.getProperty(property, System.getenv(environment));
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int value = Integer.parseInt(raw);
            return value > 0 ? value : fallback;
        } catch (NumberFormatException _) {
            return fallback;
        }
    }

    private enum SendStatus { SUCCESS, PERMANENT, INTERRUPTED, RETRYABLE }
}
