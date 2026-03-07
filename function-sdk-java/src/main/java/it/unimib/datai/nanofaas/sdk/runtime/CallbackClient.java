// Parallel implementation exists in function-sdk-java-lite (same retry logic, different HTTP stack:
// java.net.http.HttpClient instead of Spring RestClient). Keep retry constants and URL-building
// logic in sync when modifying.
package it.unimib.datai.nanofaas.sdk.runtime;

import it.unimib.datai.nanofaas.common.model.InvocationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class CallbackClient {
    private static final Logger log = LoggerFactory.getLogger(CallbackClient.class);
    private static final int MAX_RETRIES = 3;
    private static final int[] RETRY_DELAYS_MS = {100, 500, 2000};

    private final RestClient restClient;
    private final RuntimeSettings runtimeSettings;

    public CallbackClient(RestClient restClient, RuntimeSettings runtimeSettings) {
        this.restClient = restClient;
        this.runtimeSettings = runtimeSettings;
    }

    public boolean sendResult(String executionId, InvocationResult result) {
        return sendResult(executionId, result, null);
    }

    public boolean sendResult(String executionId, InvocationResult result, String traceId) {
        String baseUrl = runtimeSettings.callbackUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("CALLBACK_URL not configured, skipping callback for execution {}", executionId);
            return false;
        }
        if (executionId == null || executionId.isBlank()) {
            log.warn("executionId is null or blank, skipping callback");
            return false;
        }

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                doSendResult(executionId, result, traceId);
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

    private void doSendResult(String executionId, InvocationResult result, String traceId) {
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

        request.body(result)
                .retrieve()
                .toBodilessEntity();
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

    private String callbackUrl(String executionId) {
        String base = runtimeSettings.callbackUrl().strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        // Remove any existing /<segment>:complete suffix so executionId is always authoritative
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
