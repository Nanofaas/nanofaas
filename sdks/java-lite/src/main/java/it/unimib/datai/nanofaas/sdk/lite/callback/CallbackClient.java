// Parallel implementation exists in function-sdk-java (same retry logic, different HTTP stack:
// Spring RestClient instead of java.net.http.HttpClient). Keep retry constants and URL-building
// logic in sync when modifying.
package it.unimib.datai.nanofaas.sdk.lite.callback;

import it.unimib.datai.nanofaas.common.model.InvocationResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class CallbackClient {
    private static final Logger log = LoggerFactory.getLogger(CallbackClient.class);
    private static final int MAX_RETRIES = 3;
    private static final int[] RETRY_DELAYS_MS = {100, 500, 2000};

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public CallbackClient(ObjectMapper objectMapper, String baseUrl) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.httpClient = (baseUrl != null && !baseUrl.isBlank())
                ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                : null;
    }

    // Visible for testing
    CallbackClient(HttpClient httpClient, ObjectMapper objectMapper, String baseUrl) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
    }

    public boolean sendResult(String executionId, InvocationResult result, String traceId) {
        return sendResult(executionId, result, traceId, null);
    }

    public boolean sendResult(String executionId, InvocationResult result, String traceId, String dispatchAttempt) {
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
            body = objectMapper.writeValueAsBytes(result);
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize callback payload for execution {}", executionId, ex);
            return false;
        }

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                int status = doSend(executionId, body, traceId, dispatchAttempt);
                if (status >= 200 && status < 300) {
                    log.debug("Callback sent successfully for execution {} (attempt {})", executionId, attempt + 1);
                    return true;
                }
                if (status >= 400 && status < 500 && status != 408 && status != 429) {
                    log.error("Permanent callback failure for execution {} with status {}", executionId, status);
                    return false;
                }
                log.warn("Callback failed for execution {} (attempt {}) with status {}",
                        executionId, attempt + 1, status);
            } catch (Exception ex) {
                log.warn("Callback failed for execution {} (attempt {}): {}",
                        executionId, attempt + 1, ex.getMessage());
                if (attempt < MAX_RETRIES - 1) {
                    try {
                        Thread.sleep(RETRY_DELAYS_MS[attempt]);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("Callback retry interrupted for execution {}", executionId);
                        return false;
                    }
                }
            }
        }

        log.error("All {} callback attempts failed for execution {}", MAX_RETRIES, executionId);
        return false;
    }

    private int doSend(String executionId, byte[] body, String traceId, String dispatchAttempt) throws Exception {
        String effectiveTraceId = (traceId != null && !traceId.isBlank())
                ? traceId
                : System.getenv("TRACE_ID");

        String url = callbackUrl(executionId);

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
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
}
