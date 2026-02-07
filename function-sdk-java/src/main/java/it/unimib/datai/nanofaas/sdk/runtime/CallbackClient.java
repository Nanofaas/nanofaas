package it.unimib.datai.nanofaas.sdk.runtime;

import it.unimib.datai.nanofaas.common.model.InvocationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class CallbackClient {
    private static final Logger log = LoggerFactory.getLogger(CallbackClient.class);
    private static final int MAX_RETRIES = 3;
    private static final int[] RETRY_DELAYS_MS = {100, 500, 2000};

    private final RestClient restClient;
    private final String baseUrl;

    @Autowired
    public CallbackClient(RestClient restClient) {
        this.restClient = restClient;
        this.baseUrl = System.getenv("CALLBACK_URL");
    }

    // Constructor for testing with custom baseUrl
    CallbackClient(RestClient restClient, String baseUrl) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
    }

    public boolean sendResult(String executionId, InvocationResult result) {
        return sendResult(executionId, result, null);
    }

    public boolean sendResult(String executionId, InvocationResult result, String traceId) {
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
        // Use provided traceId, fall back to environment variable
        String effectiveTraceId = (traceId != null && !traceId.isBlank())
                ? traceId
                : System.getenv("TRACE_ID");

        String url = baseUrl.endsWith(":complete")
                ? baseUrl
                : baseUrl + "/" + executionId + ":complete";

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
}
