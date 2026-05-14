package it.unimib.datai.nanofaas.sdk.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

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
        client = new FastCallbackClient(
                restClient,
                new RuntimeSettings("env-exec-id", "env-trace-id", server.url("/v1/executions").toString(), "handler"),
                new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private CallbackClient newCallbackClient(RestClient restClient, RuntimeSettings settings) {
        return new FastCallbackClient(restClient, settings, new ObjectMapper());
    }

    private static CallbackPayload successPayload(Object value) {
        return CallbackPayload.success(new ObjectMapper().valueToTree(value));
    }

    private static CallbackPayload errorPayload(String code, String message) {
        return CallbackPayload.error(code, message);
    }

    @Test
    void sendResult_success_buildsUrlAndSendsBody() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));

        boolean ok = client.sendResult("exec-1", successPayload("hello"), "trace-42");
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

        boolean ok = client.sendResult("exec-2", successPayload("data"), null);
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
                        "handler"),
                new ObjectMapper());

        server.enqueue(new MockResponse().setResponseCode(200));

        boolean ok = completeClient.sendResult("exec-3", successPayload("ok"), null);
        assertTrue(ok);

        RecordedRequest req = server.takeRequest();
        assertTrue(req.getPath().endsWith(":complete"));
        assertFalse(req.getPath().contains("exec-3:complete/exec-3:complete"));
    }

    @Test
    void sendResult_nullBaseUrl_returnsFalse() {
        RestClient restClient = RestClient.create();
        CallbackClient nullUrlClient = new CallbackClient(
                restClient,
                new RuntimeSettings("env-exec-id", "env-trace-id", null, "handler"),
                new ObjectMapper());

        boolean ok = nullUrlClient.sendResult("exec-4", successPayload("data"), null);
        assertFalse(ok);
    }

    @Test
    void sendResult_blankBaseUrl_returnsFalse() {
        RestClient restClient = RestClient.create();
        CallbackClient blankUrlClient = new CallbackClient(
                restClient,
                new RuntimeSettings("env-exec-id", "env-trace-id", "  ", "handler"),
                new ObjectMapper());

        boolean ok = blankUrlClient.sendResult("exec-5", successPayload("data"), null);
        assertFalse(ok);
    }

    @Test
    void sendResult_nullExecutionId_returnsFalse() {
        boolean ok = client.sendResult(null, successPayload("data"), null);
        assertFalse(ok);
    }

    @Test
    void sendResult_blankExecutionId_returnsFalse() {
        boolean ok = client.sendResult("  ", successPayload("data"), null);
        assertFalse(ok);
    }

    @Test
    void sendResult_retriesOnFailureThenSucceeds() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(200));

        boolean ok = client.sendResult("exec-6", successPayload("retry-ok"), null);
        assertTrue(ok);
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void sendResult_allRetriesFail_returnsFalse() {
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));

        boolean ok = client.sendResult("exec-7", errorPayload("ERR", "fail"), null);
        assertFalse(ok);
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void sendResult_errorResult_sendsErrorPayload() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));

        boolean ok = client.sendResult("exec-8", errorPayload("TIMEOUT", "timed out"), "t-1");
        assertTrue(ok);

        RecordedRequest req = server.takeRequest();
        String body = req.getBody().readUtf8();
        assertTrue(body.contains("\"success\":false"));
        assertTrue(body.contains("TIMEOUT"));
    }

    @Test
    void sendResult_permanent4xxFailure_isNotRetried() {
        server.enqueue(new MockResponse().setResponseCode(400));

        boolean ok = client.sendResult("exec-9", errorPayload("ERR", "fail"), null);

        assertFalse(ok);
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void sendResult_retryable429Failure_isRetried() {
        server.enqueue(new MockResponse().setResponseCode(429));
        server.enqueue(new MockResponse().setResponseCode(200));

        boolean ok = client.sendResult("exec-9b", successPayload("retry-ok"), null);

        assertTrue(ok);
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void sendResult_retryable408Failure_isRetried() {
        server.enqueue(new MockResponse().setResponseCode(408));
        server.enqueue(new MockResponse().setResponseCode(200));

        boolean ok = client.sendResult("exec-9c", successPayload("retry-ok"), null);

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
                        "handler"),
                new ObjectMapper());

        boolean ok = configuredClient.sendResult("exec-9", successPayload("data"), null);
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
                        "handler"),
                new ObjectMapper());

        server.enqueue(new MockResponse().setResponseCode(200));

        placeholderClient.sendResult("real-exec-id", successPayload("ok"), null);

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
                        "handler"),
                new ObjectMapper());
        CallbackClient completeSuffixClient = new CallbackClient(
                restClient,
                new RuntimeSettings(
                        "exec-env",
                        "trace-from-settings",
                        server.url("/v1/executions/exec-10:complete/").toString(),
                        "handler"),
                new ObjectMapper());

        assertTrue(trailingSlashClient.sendResult("exec-10", successPayload("data"), null));
        assertTrue(completeSuffixClient.sendResult("exec-10", successPayload("data"), null));

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
                new RuntimeSettings("env", "trace", dirtyUrl, "handler"),
                new ObjectMapper());

        server.enqueue(new MockResponse().setResponseCode(200));

        dirtyClient.sendResult("exec-clean", successPayload("data"), null);

        RecordedRequest req = server.takeRequest();
        assertFalse(req.getPath().contains("//"), "Double slash in path: " + req.getPath());
        assertTrue(req.getPath().endsWith("/exec-clean:complete"));
    }

    @Test
    void sendResult_interruptedDuringRetry_returnsFalseAndRestoresInterruptFlag() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(500));

        AtomicBoolean interruptedAfterCall = new AtomicBoolean(false);
        Thread testThread = new Thread(() -> {
            Thread.currentThread().interrupt();
            client.sendResult("exec-interrupt", successPayload("x"), null);
            interruptedAfterCall.set(Thread.currentThread().isInterrupted());
        });
        testThread.start();
        testThread.join(3000);

        assertTrue(interruptedAfterCall.get(),
                "Interrupt flag should be restored after InterruptedException in retry sleep");
    }

    @Test
    void sendResult_doesNotStringifySuccessfulOutputs() throws Exception {
        RestClient restClient = RestClient.builder()
                .baseUrl(server.url("/").toString())
                .build();
        ObjectMapper mapper = new ObjectMapper();
        CallbackClient strictClient = new CallbackClient(
                restClient,
                new RuntimeSettings("env-exec-id", "env-trace-id", server.url("/v1/executions").toString(), "handler"),
                mapper);
        CallbackPayload payload = CallbackPayload.success(mapper.readTree("""
                {"nested":{"value":"kept"}}
                """));
        server.enqueue(new MockResponse().setResponseCode(200));

        assertTrue(strictClient.sendResult("exec-strict", payload, "trace-42"));

        String body = server.takeRequest().getBody().readUtf8();
        assertTrue(body.contains("\"nested\":{\"value\":\"kept\"}"));
        assertFalse(body.contains("NativeLikeOutput"));
    }

    @Test
    void sendResult_sendsStructuredJsonNodeOutputWithoutMessageConverter() throws Exception {
        RestClient restClient = RestClient.builder()
                .baseUrl(server.url("/").toString())
                .messageConverters(converters -> converters.removeIf(converter ->
                        converter.getClass().getName().contains("MappingJackson2HttpMessageConverter")))
                .build();
        CallbackClient clientWithoutJacksonConverter = newCallbackClient(
                restClient,
                new RuntimeSettings("env-exec-id", "env-trace-id", server.url("/v1/executions").toString(), "handler"));
        ObjectMapper mapper = new ObjectMapper();
        CallbackPayload payload = CallbackPayload.success(mapper.readTree("""
                {"wordCount":4,"topWords":[{"word":"the","count":1}]}
                """));
        server.enqueue(new MockResponse().setResponseCode(200));

        boolean ok = clientWithoutJacksonConverter.sendResult("exec-json", payload, "trace-42");

        assertTrue(ok);
        RecordedRequest req = server.takeRequest();
        String body = req.getBody().readUtf8();
        assertTrue(body.contains("\"success\":true"));
        assertTrue(body.contains("\"wordCount\":4"));
        assertTrue(body.contains("\"topWords\""));
        assertFalse(body.contains("NativeLikeOutput"));
    }

    /** Removes retry delays for fast test execution. */
    private static class FastCallbackClient extends CallbackClient {
        FastCallbackClient(RestClient restClient, RuntimeSettings settings, ObjectMapper mapper) {
            super(restClient, settings, mapper);
        }

        @Override
        protected void sleepBeforeRetry(int attemptIndex) {
            // no-op: eliminate retry delays in tests
        }
    }
}
