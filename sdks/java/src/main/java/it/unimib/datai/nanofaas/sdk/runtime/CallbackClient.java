package it.unimib.datai.nanofaas.sdk.runtime;

import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Delivers invoke results back to the control plane.
 *
 * <p>Callbacks are part of the request contract, not background best effort work. The Java SDK
 * keeps the retry policy and URL construction explicit here so the runtime can finish the request
 * lifecycle only after the control plane has a chance to receive the outcome.</p>
 *
 * <p>Parallel implementation exists in {@code function-sdk-java-lite} with the same retry logic
 * but a different HTTP stack. Keep retry constants and URL-building logic in sync when modifying.</p>
 */
@Component
public class CallbackClient {
    private static final Logger log = LoggerFactory.getLogger(CallbackClient.class);
    private static final int MAX_RETRIES = 3;
    private static final int[] RETRY_DELAYS_MS = {100, 500, 2000};
    private static final int DEFAULT_MAX_PAYLOAD_BYTES = 2 * 1024 * 1024;

    private final RestClient restClient;
    private final RuntimeSettings runtimeSettings;
    private final ObjectMapper objectMapper;
    private final BoundedJson boundedJson;
    private final int maxPayloadBytes;
    private final int maxAttempts;

    public CallbackClient(RestClient restClient, RuntimeSettings runtimeSettings, ObjectMapper objectMapper) {
        this(restClient, runtimeSettings, objectMapper, DEFAULT_MAX_PAYLOAD_BYTES, MAX_RETRIES);
    }

    CallbackClient(RestClient restClient, RuntimeSettings runtimeSettings, ObjectMapper objectMapper,
                   int maxPayloadBytes) {
        this(restClient, runtimeSettings, objectMapper, maxPayloadBytes, MAX_RETRIES);
    }

    @Autowired
    public CallbackClient(RestClient restClient, RuntimeSettings runtimeSettings, ObjectMapper objectMapper,
                          @Value("${nanofaas.callback.max-payload-bytes:${NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES:2097152}}") int maxPayloadBytes,
                          @Value("${nanofaas.callback.max-attempts:${NANOFAAS_CALLBACK_MAX_ATTEMPTS:3}}") int maxAttempts) {
        if (maxPayloadBytes <= 0 || maxAttempts <= 0) {
            throw new IllegalArgumentException("callback payload and attempt limits must be positive");
        }
        this.restClient = restClient;
        this.runtimeSettings = runtimeSettings;
        this.objectMapper = objectMapper;
        this.boundedJson = new BoundedJson(objectMapper);
        this.maxPayloadBytes = maxPayloadBytes;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Pauses before the next retry attempt. Protected for override in tests.
     * @param attemptIndex zero-based index of the attempt just completed (0 = first retry delay)
     */
    protected void sleepBeforeRetry(int attemptIndex) throws InterruptedException {
        Thread.sleep(RETRY_DELAYS_MS[attemptIndex]);
    }

    private boolean pauseBeforeRetry(int attempt, String executionId) {
        try {
            sleepBeforeRetry(attempt);
            return true;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            log.warn("Callback retry interrupted for execution {}", executionId);
            return false;
        }
    }

    private boolean isPermanentClientFailure(RestClientException ex) {
        if (!(ex instanceof RestClientResponseException responseException)) {
            return false;
        }
        HttpStatusCode statusCode = responseException.getStatusCode();
        return statusCode.is4xxClientError()
                && statusCode.value() != 408
                && statusCode.value() != 429;
    }

    public boolean sendResult(String executionId, CallbackPayload payload, String traceId) {
        return sendResult(executionId, payload, traceId, null);
    }

    public boolean sendResult(String executionId, CallbackPayload payload, String traceId, String dispatchAttempt) {
        String baseUrl = runtimeSettings.callbackUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("CALLBACK_URL not configured, skipping callback for execution {}", executionId);
            return false;
        }
        if (executionId == null || executionId.isBlank()) {
            log.warn("executionId is null or blank, skipping callback");
            return false;
        }

        final byte[] body;
        try {
            body = serializeBounded(payload, maxPayloadBytes);
        } catch (BoundedJson.PayloadTooLargeException | BoundedJson.SerializationException ex) {
            log.error("Failed to serialize callback payload for execution {}", executionId, ex);
            return false;
        }
        return sendSerializedResult(executionId, body, traceId, dispatchAttempt);
    }

    public boolean sendSerializedResult(String executionId, byte[] body, String traceId, String dispatchAttempt) {
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                doSendPayload(executionId, body, traceId, dispatchAttempt);
                log.debug("Callback sent successfully for execution {} (attempt {})", executionId, attempt + 1);
                return true;
            } catch (RestClientException ex) {
                log.warn("Callback failed for execution {} (attempt {}): {}",
                        executionId, attempt + 1, ex.getMessage());
                if (isPermanentClientFailure(ex)) {
                    log.error("Permanent callback failure for execution {} with status {}",
                            executionId, ((RestClientResponseException) ex).getStatusCode());
                    return false;
                }
                if (attempt < maxAttempts - 1 && !pauseBeforeRetry(attempt, executionId)) {
                    return false;
                }
            }
        }

        log.error("All {} callback attempts failed for execution {}", maxAttempts, executionId);
        return false;
    }

    private void doSendPayload(String executionId, byte[] payload, String traceId, String dispatchAttempt) {
        String effectiveTraceId = (traceId != null && !traceId.isBlank())
                ? traceId
                : runtimeSettings.traceId();
        String url = callbackUrl(executionId);

        RestClient.RequestBodySpec request = restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON);

        if (effectiveTraceId != null && !effectiveTraceId.isBlank()) {
            request.header("X-Trace-Id", effectiveTraceId);
        }
        if (dispatchAttempt != null && !dispatchAttempt.isBlank()) {
            request.header("X-Dispatch-Attempt", dispatchAttempt);
        }

        request.body(payload)
                .retrieve()
                .toBodilessEntity();
    }

    byte[] serializeBounded(CallbackPayload payload, int maxBytes) {
        return boundedJson.serialize(payload, maxBytes);
    }

    private String callbackUrl(String executionId) {
        String base = runtimeSettings.callbackUrl().strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        // Remove any existing /<segment>:complete suffix so executionId is always authoritative.
        // Assumption: the base URL path does not contain ':complete' in intermediate segments.
        int completeSuffixIdx = base.lastIndexOf(":complete");
        if (completeSuffixIdx >= 0) {
            int slashIdx = base.lastIndexOf('/', completeSuffixIdx);
            if (slashIdx >= 0) {
                base = base.substring(0, slashIdx);
            }
        }
        return base + "/" + executionId + ":complete";
    }
}
