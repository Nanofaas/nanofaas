package it.unimib.datai.nanofaas.controlplane.dispatch;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationKind;
import it.unimib.datai.nanofaas.controlplane.scheduler.InvocationTask;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalDispatcherTest {
    private static final Instant RETRY_NOW = Instant.parse("2026-09-24T12:00:00Z");

    @Test
    void retryAfterDatesAndDuplicatesAreUnambiguous() {
        assertEquals(RETRY_NOW.plusSeconds(1), ExternalDispatcher.parseRetryAfter(List.of("1"), RETRY_NOW));
        assertEquals(RETRY_NOW.plusSeconds(1), ExternalDispatcher.parseRetryAfter(
                List.of("Thu, 24 Sep 2026 12:00:01 GMT"), RETRY_NOW));
        assertEquals(RETRY_NOW.plusSeconds(1), ExternalDispatcher.parseRetryAfter(List.of(" 1 ", "1"), RETRY_NOW));
        assertNull(ExternalDispatcher.parseRetryAfter(List.of("1", "2"), RETRY_NOW));
        assertNull(ExternalDispatcher.parseRetryAfter(List.of("1, 2"), RETRY_NOW));
        assertNull(ExternalDispatcher.parseRetryAfter(List.of("-1"), RETRY_NOW));
        assertNull(ExternalDispatcher.parseRetryAfter(List.of(""), RETRY_NOW));
        assertNull(ExternalDispatcher.parseRetryAfter(List.of("  "), RETRY_NOW));
        assertNull(ExternalDispatcher.parseRetryAfter(List.of("+1"), RETRY_NOW));
        assertNull(ExternalDispatcher.parseRetryAfter(List.of("1.5"), RETRY_NOW));
        assertNull(ExternalDispatcher.parseRetryAfter(List.of("not-a-date"), RETRY_NOW));
        assertEquals(RETRY_NOW, ExternalDispatcher.parseRetryAfter(List.of("0"), RETRY_NOW));
        assertEquals(RETRY_NOW.minusSeconds(1), ExternalDispatcher.parseRetryAfter(
                List.of("Thu, 24 Sep 2026 11:59:59 GMT"), RETRY_NOW));
        assertEquals(Instant.MAX, ExternalDispatcher.parseRetryAfter(
                List.of("999999999999999999999999999999"), RETRY_NOW));
        assertEquals(Instant.MAX, ExternalDispatcher.parseRetryAfter(List.of("1"), Instant.MAX));
        assertNull(ExternalDispatcher.parseRetryAfter(null, RETRY_NOW));
    }

    @Test
    void retryAfterAcceptsLegacyHttpDates() {
        assertEquals(RETRY_NOW.plusSeconds(1), ExternalDispatcher.parseRetryAfter(
                List.of("Thursday, 24-Sep-26 12:00:01 GMT"), RETRY_NOW));
        assertEquals(RETRY_NOW.plusSeconds(1), ExternalDispatcher.parseRetryAfter(
                List.of("Thu Sep 24 12:00:01 2026"), RETRY_NOW));
    }

    @Test
    void retryHintOnlyBelongsToUnmarkedRateLimitAndUnavailableResponses() throws Exception {
        assertHint(429, false, "1", RETRY_NOW.plusSeconds(1));
        assertHint(503, false, "Thu, 24 Sep 2026 12:00:01 GMT", RETRY_NOW.plusSeconds(1));
        assertHint(500, false, "1", null);
        assertHint(429, true, "1", null);
        assertHint(503, true, "1", null);
    }

    @Test
    void retryHintSurvivesErrorBodyDecodeFailure() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setResponseCode(429).addHeader("Retry-After", "1")
                .addHeader("X-Cold-Start", "true").addHeader("X-Init-Duration-Ms", "27")
                .setBody("x".repeat(300_000)));
        server.start();
        try {
            WebClient limitedClient = WebClient.builder().codecs(codecs ->
                    codecs.defaultCodecs().maxInMemorySize(1024)).build();
            DispatchResult result = new ExternalDispatcher(limitedClient,
                    Clock.fixed(RETRY_NOW, ZoneOffset.UTC)).dispatch(taskFor(server)).get(5, TimeUnit.SECONDS);
            assertFalse(result.result().success());
            assertEquals("EXTERNAL_ERROR", result.result().error().code());
            assertEquals(RETRY_NOW.plusSeconds(1), result.retryNotBefore());
            assertTrue(result.coldStart());
            assertEquals(27L, result.initDurationMs());
        } finally {
            server.shutdown();
        }
    }

    @Test
    void connectionRefusalHasNoRetryHint() throws Exception {
        MockWebServer server = new MockWebServer();
        server.start();
        InvocationTask task = taskFor(server);
        server.shutdown();

        DispatchResult result = new ExternalDispatcher(WebClient.create(),
                Clock.fixed(RETRY_NOW, ZoneOffset.UTC)).dispatch(task).get(5, TimeUnit.SECONDS);
        assertFalse(result.result().success());
        assertEquals("EXTERNAL_ERROR", result.result().error().code());
        assertNull(result.retryNotBefore());
    }

    private static void assertHint(int status, boolean marked, String retryAfter, Instant expected) throws Exception {
        MockWebServer server = new MockWebServer();
        MockResponse response = new MockResponse().setResponseCode(status)
                .addHeader("Retry-After", retryAfter).setBody(marked ? "{\"ok\":true}" : "busy");
        if (marked) response.addHeader("X-NanoFaaS-Function-Status", "true");
        server.enqueue(response);
        server.start();
        try {
            DispatchResult result = new ExternalDispatcher(WebClient.create(),
                    Clock.fixed(RETRY_NOW, ZoneOffset.UTC)).dispatch(taskFor(server)).get(5, TimeUnit.SECONDS);
            assertEquals(marked, result.result().success());
            assertEquals(expected, result.retryNotBefore());
            if (marked) assertEquals(status, result.result().statusCode());
            else assertEquals("EXTERNAL_ERROR", result.result().error().code());
        } finally {
            server.shutdown();
        }
    }

    private static InvocationTask taskFor(MockWebServer server) {
        FunctionSpec spec = new FunctionSpec("retry-fn", "image", null, Map.of(), null, 1000,
                1, 10, 3, server.url("/invoke").toString(), ExecutionMode.EXTERNAL, null, null, null);
        return new InvocationTask("retry-exec", "retry-fn", spec,
                new InvocationRequest("payload", Map.of()), null, null, RETRY_NOW, 1, InvocationKind.SYNC);
    }

    @Test
    void poolDispatchCallsEndpoint() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setBody("{\"message\":\"ok\"}")
                .addHeader("Content-Type", "application/json"));
        server.start();

        String endpoint = server.url("/invoke").toString();

        FunctionSpec spec = new FunctionSpec(
                "pool-fn",
                "image",
                null,
                Map.of(),
                null,
                1000,
                1,
                10,
                3,
                endpoint,
                ExecutionMode.EXTERNAL,
                null,
                null,
                null
        );

        InvocationTask task = new InvocationTask(
                "exec-pool",
                "pool-fn",
                spec,
                new InvocationRequest("payload", Map.of()),
                null,
                null,
                Instant.now(),
                1
        ,
        InvocationKind.SYNC
    );

        ExternalDispatcher dispatcher = new ExternalDispatcher(WebClient.builder().build());
        DispatchResult dr = dispatcher.dispatch(task).get();

        assertTrue(dr.result().success());
        assertNotNull(dr.result().output());
        assertFalse(dr.coldStart());
        assertEquals(1, server.getRequestCount());
        server.shutdown();
    }

    @Test
    void poolDispatchSendsDispatchAttemptHeader() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setBody("{\"message\":\"ok\"}")
                .addHeader("Content-Type", "application/json"));
        server.start();

        String endpoint = server.url("/invoke").toString();
        FunctionSpec spec = new FunctionSpec(
                "pool-fn",
                "image",
                null,
                Map.of(),
                null,
                1000,
                1,
                10,
                3,
                endpoint,
                ExecutionMode.EXTERNAL,
                null,
                null,
                null
        );
        InvocationTask task = new InvocationTask(
                "exec-pool",
                "pool-fn",
                spec,
                new InvocationRequest("payload", Map.of()),
                null,
                null,
                Instant.now(),
                4
        ,
        InvocationKind.SYNC
    );

        ExternalDispatcher dispatcher = new ExternalDispatcher(WebClient.builder().build());
        dispatcher.dispatch(task).get();

        RecordedRequest request = server.takeRequest();
        assertEquals("4", request.getHeader("X-Dispatch-Attempt"));
        server.shutdown();
    }

    @Test
    void poolDispatchHandlesTextPlain() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setBody("plain-output")
                .addHeader("Content-Type", "text/plain"));
        server.start();

        String endpoint = server.url("/invoke").toString();

        FunctionSpec spec = new FunctionSpec(
                "pool-fn",
                "image",
                null,
                Map.of(),
                null,
                1000,
                1,
                10,
                3,
                endpoint,
                ExecutionMode.EXTERNAL,
                null,
                null,
                null
        );

        InvocationTask task = new InvocationTask(
                "exec-pool",
                "pool-fn",
                spec,
                new InvocationRequest("payload", Map.of()),
                null,
                null,
                Instant.now(),
                1
        ,
        InvocationKind.SYNC
    );

        ExternalDispatcher dispatcher = new ExternalDispatcher(WebClient.builder().build());
        DispatchResult dr = dispatcher.dispatch(task).get();

        assertTrue(dr.result().success());
        assertEquals("plain-output", dr.result().output());
        server.shutdown();
    }

    @Test
    void poolDispatchHandlesEmptySuccessfulResponse() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse().setResponseCode(204));
        server.start();

        try {
            FunctionSpec spec = new FunctionSpec(
                    "pool-fn",
                    "image",
                    null,
                    Map.of(),
                    null,
                    1000,
                    1,
                    10,
                    3,
                    server.url("/invoke").toString(),
                    ExecutionMode.EXTERNAL,
                    null,
                    null,
                    null
            );
            InvocationTask task = new InvocationTask(
                    "exec-pool",
                    "pool-fn",
                    spec,
                    new InvocationRequest("payload", Map.of()),
                    null,
                    null,
                    Instant.now(),
                    1
            ,
        InvocationKind.SYNC
    );

            DispatchResult result = new ExternalDispatcher(WebClient.builder().build()).dispatch(task).get();

            assertNotNull(result);
            assertTrue(result.result().success());
            assertNull(result.result().output());
        } finally {
            server.shutdown();
        }
    }

    @Test
    void dispatch_functionStatusMarkerPresent_treatsNon2xxAsSuccess() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setResponseCode(404)
                .setBody("{\"error\":\"not found\"}")
                .addHeader("Content-Type", "application/json")
                .addHeader("X-NanoFaaS-Function-Status", "true"));
        server.start();

        String endpoint = server.url("/invoke").toString();
        FunctionSpec spec = new FunctionSpec(
                "pool-fn", "image", null, Map.of(), null, 1000, 1, 10, 3,
                endpoint, ExecutionMode.EXTERNAL, null, null, null
        );
        InvocationTask task = new InvocationTask(
                "exec-pool", "pool-fn", spec,
                new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1
        ,
        InvocationKind.SYNC
    );

        ExternalDispatcher dispatcher = new ExternalDispatcher(WebClient.builder().build());
        DispatchResult dr = dispatcher.dispatch(task).get();

        assertTrue(dr.result().success());
        assertEquals(404, dr.result().statusCode());
        server.shutdown();
    }

    @Test
    void dispatch_functionStatusMarkerPresent_propagatesAllowedHeaders() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/pdf")
                .addHeader("X-NanoFaaS-Function-Status", "true"));
        server.start();

        String endpoint = server.url("/invoke").toString();
        FunctionSpec spec = new FunctionSpec(
                "pool-fn", "image", null, Map.of(), null, 1000, 1, 10, 3,
                endpoint, ExecutionMode.EXTERNAL, null, null, null
        );
        InvocationTask task = new InvocationTask(
                "exec-pool", "pool-fn", spec,
                new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1
        ,
        InvocationKind.SYNC
    );

        ExternalDispatcher dispatcher = new ExternalDispatcher(WebClient.builder().build());
        DispatchResult dr = dispatcher.dispatch(task).get();

        assertTrue(dr.result().success());
        assertEquals("application/pdf", dr.result().headers().get("Content-Type"));
        server.shutdown();
    }

    @Test
    void dispatch_functionStatusMarkerPresent_unrecognizedContentTypeWithJsonBody_survivesIntact() throws Exception {
        // Content-Type is a caller-chosen label; the wire body from InvokeController is always
        // JSON regardless of it. A JSON-quoted base64 string is exactly the shape issue #176's
        // binary-payload feature produces.
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("\"aGVsbG8gd29ybGQ=\"")
                .addHeader("Content-Type", "application/pdf")
                .addHeader("X-NanoFaaS-Function-Status", "true"));
        server.start();

        String endpoint = server.url("/invoke").toString();
        FunctionSpec spec = new FunctionSpec(
                "pool-fn", "image", null, Map.of(), null, 1000, 1, 10, 3,
                endpoint, ExecutionMode.EXTERNAL, null, null, null
        );
        InvocationTask task = new InvocationTask(
                "exec-pool", "pool-fn", spec,
                new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1
        ,
        InvocationKind.SYNC
    );

        ExternalDispatcher dispatcher = new ExternalDispatcher(WebClient.builder().build());
        DispatchResult dr = dispatcher.dispatch(task).get();

        assertTrue(dr.result().success());
        assertEquals("aGVsbG8gd29ybGQ=", dr.result().output());
        assertEquals("application/pdf", dr.result().headers().get("Content-Type"));
        server.shutdown();
    }

    @Test
    void dispatch_functionStatusMarkerPresent_unrecognizedContentTypeWithEmptyBody_yieldsNullOutput() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/pdf")
                .addHeader("X-NanoFaaS-Function-Status", "true"));
        server.start();

        String endpoint = server.url("/invoke").toString();
        FunctionSpec spec = new FunctionSpec(
                "pool-fn", "image", null, Map.of(), null, 1000, 1, 10, 3,
                endpoint, ExecutionMode.EXTERNAL, null, null, null
        );
        InvocationTask task = new InvocationTask(
                "exec-pool", "pool-fn", spec,
                new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1
        ,
        InvocationKind.SYNC
    );

        ExternalDispatcher dispatcher = new ExternalDispatcher(WebClient.builder().build());
        DispatchResult dr = dispatcher.dispatch(task).get();

        assertTrue(dr.result().success());
        assertNull(dr.result().output());
        server.shutdown();
    }

    @Test
    void dispatch_noFunctionStatusMarker_unrecognizedContentTypeIsStillExternalError() throws Exception {
        // Regression guard: the no-marker path must keep today's failure mode for a content
        // type Spring can't decode as Object.class — unlike the marker path, it must NOT fall
        // back to a lenient JSON re-parse.
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("\"aGVsbG8gd29ybGQ=\"")
                .addHeader("Content-Type", "application/pdf"));
        server.start();

        String endpoint = server.url("/invoke").toString();
        FunctionSpec spec = new FunctionSpec(
                "pool-fn", "image", null, Map.of(), null, 1000, 1, 10, 3,
                endpoint, ExecutionMode.EXTERNAL, null, null, null
        );
        InvocationTask task = new InvocationTask(
                "exec-pool", "pool-fn", spec,
                new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1
        ,
        InvocationKind.SYNC
    );

        ExternalDispatcher dispatcher = new ExternalDispatcher(WebClient.builder().build());
        DispatchResult dr = dispatcher.dispatch(task).get();

        assertFalse(dr.result().success());
        assertEquals("EXTERNAL_ERROR", dr.result().error().code());
        server.shutdown();
    }

    @Test
    void dispatch_noFunctionStatusMarker_non2xxIsStillExternalError() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("boom"));
        server.start();

        String endpoint = server.url("/invoke").toString();
        FunctionSpec spec = new FunctionSpec(
                "pool-fn", "image", null, Map.of(), null, 1000, 1, 10, 3,
                endpoint, ExecutionMode.EXTERNAL, null, null, null
        );
        InvocationTask task = new InvocationTask(
                "exec-pool", "pool-fn", spec,
                new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1
        ,
        InvocationKind.SYNC
    );

        ExternalDispatcher dispatcher = new ExternalDispatcher(WebClient.builder().build());
        DispatchResult dr = dispatcher.dispatch(task).get();

        assertFalse(dr.result().success());
        assertEquals("EXTERNAL_ERROR", dr.result().error().code());
        server.shutdown();
    }

    @Test
    void dispatch_functionStatusMarkerWithTextPlain_decodesBodyAsString() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("hello")
                .addHeader("Content-Type", "text/plain")
                .addHeader("X-NanoFaaS-Function-Status", "true"));
        server.start();

        String endpoint = server.url("/invoke").toString();
        FunctionSpec spec = new FunctionSpec(
                "pool-fn", "image", null, Map.of(), null, 1000, 1, 10, 3,
                endpoint, ExecutionMode.EXTERNAL, null, null, null
        );
        InvocationTask task = new InvocationTask(
                "exec-pool", "pool-fn", spec,
                new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1
        ,
        InvocationKind.SYNC
    );

        ExternalDispatcher dispatcher = new ExternalDispatcher(WebClient.builder().build());
        DispatchResult dr = dispatcher.dispatch(task).get();

        assertTrue(dr.result().success());
        assertEquals("hello", dr.result().output());
        server.shutdown();
    }

    @Test
    void dispatch_functionStatusMarkerPresent_encodingSurvivesTheHop() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("\"aGVsbG8gd29ybGQ=\"")
                .addHeader("Content-Type", "application/json")
                .addHeader("X-NanoFaaS-Function-Status", "true")
                .addHeader("X-NanoFaaS-Encoding", "base64"));
        server.start();

        String endpoint = server.url("/invoke").toString();
        FunctionSpec spec = new FunctionSpec(
                "pool-fn", "image", null, Map.of(), null, 1000, 1, 10, 3,
                endpoint, ExecutionMode.EXTERNAL, null, null, null
        );
        InvocationTask task = new InvocationTask(
                "exec-pool", "pool-fn", spec,
                new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1
        ,
        InvocationKind.SYNC
    );

        ExternalDispatcher dispatcher = new ExternalDispatcher(WebClient.builder().build());
        DispatchResult dr = dispatcher.dispatch(task).get();

        assertTrue(dr.result().success());
        assertEquals("base64", dr.result().encoding());
        assertEquals("aGVsbG8gd29ybGQ=", dr.result().output());
        server.shutdown();
    }

    @Test
    void dispatch_functionStatusMarkerPresent_noEncodingHeaderYieldsNullEncoding() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"message\":\"ok\"}")
                .addHeader("Content-Type", "application/json")
                .addHeader("X-NanoFaaS-Function-Status", "true"));
        server.start();

        String endpoint = server.url("/invoke").toString();
        FunctionSpec spec = new FunctionSpec(
                "pool-fn", "image", null, Map.of(), null, 1000, 1, 10, 3,
                endpoint, ExecutionMode.EXTERNAL, null, null, null
        );
        InvocationTask task = new InvocationTask(
                "exec-pool", "pool-fn", spec,
                new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1
        ,
        InvocationKind.SYNC
    );

        ExternalDispatcher dispatcher = new ExternalDispatcher(WebClient.builder().build());
        DispatchResult dr = dispatcher.dispatch(task).get();

        assertTrue(dr.result().success());
        assertNull(dr.result().encoding());
        server.shutdown();
    }

    @Test
    void dispatch_functionStatusMarkerPresent_malformedBodyIsExternalErrorNotSilentNull() throws Exception {
        // A marker-bearing response whose body is present but not valid JSON (truncated by a
        // proxy, or a buggy third-party runtime) must surface as a platform error, not
        // success=true/output=null.
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{not-valid-json")
                .addHeader("Content-Type", "application/pdf")
                .addHeader("X-NanoFaaS-Function-Status", "true"));
        server.start();

        String endpoint = server.url("/invoke").toString();
        FunctionSpec spec = new FunctionSpec(
                "pool-fn", "image", null, Map.of(), null, 1000, 1, 10, 3,
                endpoint, ExecutionMode.EXTERNAL, null, null, null
        );
        InvocationTask task = new InvocationTask(
                "exec-pool", "pool-fn", spec,
                new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1
        ,
        InvocationKind.SYNC
    );

        ExternalDispatcher dispatcher = new ExternalDispatcher(WebClient.builder().build());
        DispatchResult dr = dispatcher.dispatch(task).get();

        assertFalse(dr.result().success());
        assertEquals("EXTERNAL_ERROR", dr.result().error().code());
        server.shutdown();
    }

    @Test
    void dispatch_functionStatusMarkerWithOutOfRangeStatus_isPlatformErrorNotPassthrough() throws Exception {
        MockWebServer server = new MockWebServer();
        server.enqueue(new MockResponse()
                .setResponseCode(999)
                .setBody("{\"ignored\":true}")
                .addHeader("Content-Type", "application/json")
                .addHeader("X-NanoFaaS-Function-Status", "true"));
        server.start();

        String endpoint = server.url("/invoke").toString();
        FunctionSpec spec = new FunctionSpec(
                "oor-fn", "image", null, Map.of(), null, 1000, 1, 10, 3,
                endpoint, ExecutionMode.EXTERNAL, null, null, null
        );
        InvocationTask task = new InvocationTask(
                "exec-oor", "oor-fn", spec,
                new InvocationRequest("payload", Map.of()),
                null, null, Instant.now(), 1
        ,
        InvocationKind.SYNC
    );

        ExternalDispatcher dispatcher = new ExternalDispatcher(WebClient.builder().build());
        DispatchResult dr = dispatcher.dispatch(task).get();

        assertFalse(dr.result().success(),
                "an out-of-range status must not be trusted as a function decision");
        assertEquals("EXTERNAL_ERROR", dr.result().error().code());
        assertNull(dr.result().statusCode(),
                "the illegal status must never be propagated to the caller");
        server.shutdown();
    }
}
