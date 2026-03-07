package it.unimib.datai.nanofaas.sdk.runtime;

import it.unimib.datai.nanofaas.common.model.InvocationResult;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class CallbackClientTest {

    private MockWebServer server;
    private CallbackClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        RestClient restClient = RestClient.builder()
                .baseUrl(server.url("/").toString())
                .build();
        client = new CallbackClient(
                restClient,
                new RuntimeSettings("env-exec-id", "env-trace-id", server.url("/v1/executions").toString(), "handler"));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void sendResult_success_buildsUrlAndSendsBody() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));

        boolean ok = client.sendResult("exec-1", InvocationResult.success("hello"), "trace-42");
        assertTrue(ok);

        RecordedRequest req = server.takeRequest();
        assertEquals("POST", req.getMethod());
        assertTrue(req.getPath().contains("exec-1:complete"));
        assertEquals("trace-42", req.getHeader("X-Trace-Id"));
        assertTrue(req.getBody().readUtf8().contains("\"success\":true"));
    }

    @Test
    void sendResult_successWithoutTraceId_usesInjectedDefaultTraceHeader() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));

        boolean ok = client.sendResult("exec-2", InvocationResult.success("data"));
        assertTrue(ok);

        RecordedRequest req = server.takeRequest();
        assertEquals("env-trace-id", req.getHeader("X-Trace-Id"));
    }

    @Test
    void sendResult_urlAlreadyEndsWithComplete_usesUrlAsIs() throws Exception {
        RestClient restClient = RestClient.builder()
                .baseUrl(server.url("/").toString())
                .build();
        CallbackClient completeClient = new CallbackClient(
                restClient,
                new RuntimeSettings(
                        "env-exec-id",
                        "env-trace-id",
                        server.url("/v1/executions/exec-3:complete").toString(),
                        "handler"));

        server.enqueue(new MockResponse().setResponseCode(200));

        boolean ok = completeClient.sendResult("exec-3", InvocationResult.success("ok"));
        assertTrue(ok);

        RecordedRequest req = server.takeRequest();
        assertTrue(req.getPath().endsWith(":complete"));
        // Should NOT append /exec-3:complete again
        assertFalse(req.getPath().contains("exec-3:complete/exec-3:complete"));
    }

    @Test
    void sendResult_nullBaseUrl_returnsFalse() {
        RestClient restClient = RestClient.create();
        CallbackClient nullUrlClient = new CallbackClient(
                restClient,
                new RuntimeSettings("env-exec-id", "env-trace-id", null, "handler"));

        boolean ok = nullUrlClient.sendResult("exec-4", InvocationResult.success("data"));
        assertFalse(ok);
    }

    @Test
    void sendResult_blankBaseUrl_returnsFalse() {
        RestClient restClient = RestClient.create();
        CallbackClient blankUrlClient = new CallbackClient(
                restClient,
                new RuntimeSettings("env-exec-id", "env-trace-id", "  ", "handler"));

        boolean ok = blankUrlClient.sendResult("exec-5", InvocationResult.success("data"));
        assertFalse(ok);
    }

    @Test
    void sendResult_nullExecutionId_returnsFalse() {
        boolean ok = client.sendResult(null, InvocationResult.success("data"));
        assertFalse(ok);
    }

    @Test
    void sendResult_blankExecutionId_returnsFalse() {
        boolean ok = client.sendResult("  ", InvocationResult.success("data"));
        assertFalse(ok);
    }

    @Test
    void sendResult_retriesOnFailureThenSucceeds() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(200));

        boolean ok = client.sendResult("exec-6", InvocationResult.success("retry-ok"));
        assertTrue(ok);
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void sendResult_allRetriesFail_returnsFalse() {
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));

        boolean ok = client.sendResult("exec-7", InvocationResult.error("ERR", "fail"));
        assertFalse(ok);
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void sendResult_errorResult_sendsErrorPayload() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));

        boolean ok = client.sendResult("exec-8", InvocationResult.error("TIMEOUT", "timed out"), "t-1");
        assertTrue(ok);

        RecordedRequest req = server.takeRequest();
        String body = req.getBody().readUtf8();
        assertTrue(body.contains("\"success\":false"));
        assertTrue(body.contains("TIMEOUT"));
    }

    @Test
    void sendResult_permanent4xxFailure_isNotRetried() {
        server.enqueue(new MockResponse().setResponseCode(400));

        boolean ok = client.sendResult("exec-9", InvocationResult.error("ERR", "fail"));

        assertFalse(ok);
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void sendResult_retryable429Failure_isRetried() {
        server.enqueue(new MockResponse().setResponseCode(429));
        server.enqueue(new MockResponse().setResponseCode(200));

        boolean ok = client.sendResult("exec-9b", InvocationResult.success("retry-ok"));

        assertTrue(ok);
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void sendResult_retryable408Failure_isRetried() {
        server.enqueue(new MockResponse().setResponseCode(408));
        server.enqueue(new MockResponse().setResponseCode(200));

        boolean ok = client.sendResult("exec-9c", InvocationResult.success("retry-ok"));

        assertTrue(ok);
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void sendResult_usesInjectedCallbackSettings() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        RestClient restClient = RestClient.builder()
                .baseUrl(server.url("/").toString())
                .build();
        CallbackClient configuredClient = new CallbackClient(
                restClient,
                new RuntimeSettings(
                        "exec-env",
                        "trace-from-settings",
                        server.url("/v1/callbacks").toString(),
                        "handler"));

        boolean ok = configuredClient.sendResult("exec-9", InvocationResult.success("data"));
        assertTrue(ok);

        RecordedRequest req = server.takeRequest();
        assertTrue(req.getPath().contains("/v1/callbacks/exec-9:complete"));
        assertEquals("trace-from-settings", req.getHeader("X-Trace-Id"));
    }

    @Test
    void sendResult_urlEndsWithComplete_stillUsesCorrectExecutionId() throws Exception {
        RestClient restClient = RestClient.builder()
                .baseUrl(server.url("/").toString())
                .build();
        CallbackClient placeholderClient = new CallbackClient(
                restClient,
                new RuntimeSettings(
                        "env-exec-id",
                        "env-trace-id",
                        server.url("/v1/executions/placeholder:complete").toString(),
                        "handler"));

        server.enqueue(new MockResponse().setResponseCode(200));

        placeholderClient.sendResult("real-exec-id", InvocationResult.success("ok"));

        RecordedRequest req = server.takeRequest();
        assertTrue(req.getPath().contains("real-exec-id:complete"),
                "Expected real-exec-id in path but got: " + req.getPath());
        assertFalse(req.getPath().contains("placeholder"),
                "Should not use placeholder ID from base URL");
    }

    @Test
    void sendResult_normalizesTrailingSlashAndCompleteSuffix() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        server.enqueue(new MockResponse().setResponseCode(200));
        RestClient restClient = RestClient.builder()
                .baseUrl(server.url("/").toString())
                .build();
        CallbackClient trailingSlashClient = new CallbackClient(
                restClient,
                new RuntimeSettings(
                        "exec-env",
                        "trace-from-settings",
                        server.url("/v1/executions/").toString(),
                        "handler"));
        CallbackClient completeSuffixClient = new CallbackClient(
                restClient,
                new RuntimeSettings(
                        "exec-env",
                        "trace-from-settings",
                        server.url("/v1/executions/exec-10:complete/").toString(),
                        "handler"));

        assertTrue(trailingSlashClient.sendResult("exec-10", InvocationResult.success("data")));
        assertTrue(completeSuffixClient.sendResult("exec-10", InvocationResult.success("data")));

        RecordedRequest trailingSlashRequest = server.takeRequest();
        assertFalse(trailingSlashRequest.getPath().contains("//exec-10:complete"));
        assertTrue(trailingSlashRequest.getPath().endsWith("/exec-10:complete"));

        RecordedRequest completeSuffixRequest = server.takeRequest();
        assertFalse(completeSuffixRequest.getPath().contains(":complete/exec-10:complete"));
        assertFalse(completeSuffixRequest.getPath().endsWith(":complete/"));
        assertTrue(completeSuffixRequest.getPath().endsWith("/exec-10:complete"));
    }

    @Test
    void sendResult_urlWithTrailingWhitespaceAndSlash_isNormalized() throws Exception {
        RestClient restClient = RestClient.builder()
                .baseUrl(server.url("/").toString())
                .build();
        String dirtyUrl = server.url("/v1/executions").toString() + "/ ";
        CallbackClient dirtyClient = new CallbackClient(
                restClient,
                new RuntimeSettings("env", "trace", dirtyUrl, "handler"));

        server.enqueue(new MockResponse().setResponseCode(200));

        dirtyClient.sendResult("exec-clean", InvocationResult.success("data"));

        RecordedRequest req = server.takeRequest();
        assertFalse(req.getPath().contains("//"), "Double slash in path: " + req.getPath());
        assertTrue(req.getPath().endsWith("/exec-clean:complete"));
    }
}
