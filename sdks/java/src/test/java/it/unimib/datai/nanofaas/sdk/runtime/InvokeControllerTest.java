package it.unimib.datai.nanofaas.sdk.runtime;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import it.unimib.datai.nanofaas.common.runtime.HandlerResponse;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InvokeControllerTest {

    private CallbackDispatcher callbackDispatcher;
    private HandlerRegistry handlerRegistry;
    private InvocationRuntimeContextResolver runtimeContextResolver;
    private ColdStartTracker coldStartTracker;
    private FunctionHandler handler;
    private InvokeController controller;

    @BeforeEach
    void setUp() {
        callbackDispatcher = mock(CallbackDispatcher.class);
        handlerRegistry = mock(HandlerRegistry.class);
        runtimeContextResolver = mock(InvocationRuntimeContextResolver.class);
        coldStartTracker = mock(ColdStartTracker.class);
        handler = mock(FunctionHandler.class);
        when(handlerRegistry.resolve()).thenReturn(handler);
        when(callbackDispatcher.submit(anyString(), any(CallbackPayload.class), any())).thenReturn(true);
        when(callbackDispatcher.submit(anyString(), any(CallbackPayload.class), any(), any())).thenReturn(true);
        when(runtimeContextResolver.resolve(any(), any()))
                .thenReturn(new InvocationRuntimeContext("env-exec-id", null));
        when(coldStartTracker.firstInvocation()).thenReturn(false);
        controller = new InvokeController(callbackDispatcher, handlerRegistry, runtimeContextResolver, coldStartTracker,
                new HandlerExecutor(5000), new JsonOutputNormalizer(new ObjectMapper()));
    }

    @Test
    void invoke_success_returnsOkWithOutput() {
        when(handler.handle(any())).thenReturn(Map.of("result", "hello"));
        when(runtimeContextResolver.resolve(any(), any()))
                .thenReturn(new InvocationRuntimeContext("env-exec-id", "trace-1"));

        InvocationRequest request = new InvocationRequest("input", null);
        ResponseEntity<Object> response = controller.invoke(request, null, "trace-1");

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody() instanceof JsonNode);
        assertEquals("hello", ((JsonNode) response.getBody()).get("result").asText());
        verify(callbackDispatcher).submit(eq("env-exec-id"), any(CallbackPayload.class), eq("trace-1"), isNull());
    }

    @Test
    void invoke_withDispatchAttemptHeader_sendsAttemptOnCallback() throws Exception {
        MockWebServer callbackServer = new MockWebServer();
        callbackServer.enqueue(new MockResponse().setResponseCode(204));
        callbackServer.start();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
        try {
            CallbackClient callbackClient = new CallbackClient(
                    RestClient.builder().baseUrl(callbackServer.url("/").toString()).build(),
                    new RuntimeSettings(
                            "env-exec-id",
                            null,
                            callbackServer.url("/v1/internal/executions").toString(),
                            "handler"),
                    new ObjectMapper());
            InvokeController callbackController = new InvokeController(
                    new CallbackDispatcher(callbackClient, executor),
                    handlerRegistry,
                    runtimeContextResolver,
                    coldStartTracker,
                    new HandlerExecutor(5000),
                    new JsonOutputNormalizer(new ObjectMapper()));
            when(handler.handle(any())).thenReturn(Map.of("result", "hello"));
            when(runtimeContextResolver.resolve(any(), any()))
                    .thenReturn(new InvocationRuntimeContext("exec-dispatch-attempt", "trace-1"));

            Method invoke = InvokeController.class.getMethod(
                    "invoke",
                    InvocationRequest.class,
                    String.class,
                    String.class,
                    String.class
            );
            ResponseEntity<?> response = (ResponseEntity<?>) invoke.invoke(
                    callbackController,
                    new InvocationRequest("input", null),
                    "exec-dispatch-attempt",
                    "trace-1",
                    "3"
            );

            assertEquals(200, response.getStatusCode().value());
            RecordedRequest callback = callbackServer.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(callback);
            assertEquals("3", callback.getHeader("X-Dispatch-Attempt"));
        } finally {
            executor.shutdownNow();
            callbackServer.shutdown();
        }
    }

    @Test
    void invokeController_delegatesExecutionIdAndTraceResolution() {
        when(handler.handle(any())).thenReturn("ok");
        when(runtimeContextResolver.resolve("header-exec-id", "trace-9"))
                .thenReturn(new InvocationRuntimeContext("resolved-exec-id", "resolved-trace"));

        InvocationRequest request = new InvocationRequest("input", null);
        ResponseEntity<Object> response = controller.invoke(request, "header-exec-id", "trace-9");

        assertEquals(200, response.getStatusCode().value());
        verify(runtimeContextResolver).resolve("header-exec-id", "trace-9");
        verify(callbackDispatcher).submit(eq("resolved-exec-id"), any(CallbackPayload.class), eq("resolved-trace"), isNull());
    }

    @Test
    void invoke_handlerThrows_returns500AndSendsErrorCallback() {
        when(handler.handle(any())).thenThrow(new RuntimeException("boom"));
        when(runtimeContextResolver.resolve(any(), any()))
                .thenReturn(new InvocationRuntimeContext("env-exec-id", "t-1"));

        InvocationRequest request = new InvocationRequest("input", null);
        ResponseEntity<Object> response = controller.invoke(request, null, "t-1");

        assertEquals(500, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) response.getBody();
        assertEquals("boom", body.get("error"));
        verify(callbackDispatcher).submit(
                eq("env-exec-id"),
                argThat((CallbackPayload p) -> !p.success()),
                eq("t-1"),
                isNull());
    }

    @Test
    void invoke_handlerThrowsWithoutMessage_returnsStable500AndCallbackPayload() {
        when(handler.handle(any())).thenThrow(new RuntimeException());
        when(runtimeContextResolver.resolve(any(), any()))
                .thenReturn(new InvocationRuntimeContext("env-exec-id", "t-2"));

        InvocationRequest request = new InvocationRequest("input", null);
        ResponseEntity<Object> response = controller.invoke(request, null, "t-2");

        assertEquals(500, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) response.getBody();
        assertEquals("Handler execution failed", body.get("error"));
        verify(callbackDispatcher).submit(
                eq("env-exec-id"),
                argThat((CallbackPayload p) -> !p.success() && p.error() != null
                        && "HANDLER_ERROR".equals(p.error().code())
                        && "Handler execution failed".equals(p.error().message())),
                eq("t-2"),
                isNull());
    }

    @Test
    void invoke_callbackFails_stillReturnsOk() {
        when(handler.handle(any())).thenReturn("data");
        when(callbackDispatcher.submit(anyString(), any(CallbackPayload.class), any())).thenReturn(false);
        when(callbackDispatcher.submit(anyString(), any(CallbackPayload.class), any(), any())).thenReturn(false);
        when(runtimeContextResolver.resolve(any(), any()))
                .thenReturn(new InvocationRuntimeContext("env-exec-id", null));

        InvocationRequest request = new InvocationRequest("input", null);
        ResponseEntity<Object> response = controller.invoke(request, null, null);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody() instanceof JsonNode);
        assertEquals("data", ((JsonNode) response.getBody()).asText());
    }

    @Test
    void invoke_noExecutionId_returnsBadRequest() {
        when(runtimeContextResolver.resolve(any(), any()))
                .thenReturn(new InvocationRuntimeContext("  ", null));

        InvocationRequest request = new InvocationRequest("input", null);
        ResponseEntity<Object> response = controller.invoke(request, null, null);

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void invoke_blankHeaderAndBlankEnv_returnsBadRequest() {
        when(runtimeContextResolver.resolve(any(), any()))
                .thenReturn(new InvocationRuntimeContext(null, null));

        InvocationRequest request = new InvocationRequest("input", null);
        ResponseEntity<Object> response = controller.invoke(request, "  ", null);

        assertEquals(400, response.getStatusCode().value());
    }

    @SuppressWarnings("java:S2925") // the mocked handler must outlive the 50ms executor timeout: the 10s sleep guarantees the timeout fires while the handler is still running
    @Test
    void invoke_handlerTimesOut_returns504AndSendsErrorCallback() {
        when(handler.handle(any())).thenAnswer(inv -> { Thread.sleep(10_000); return null; });
        controller = new InvokeController(
            callbackDispatcher, handlerRegistry, runtimeContextResolver, coldStartTracker,
            new HandlerExecutor(50), new JsonOutputNormalizer(new ObjectMapper())); // 50ms timeout

        ResponseEntity<Object> response = controller.invoke(new InvocationRequest("in", null), "exec-id", null);

        assertEquals(504, response.getStatusCode().value());
        verify(callbackDispatcher).submit(
            eq("env-exec-id"),
            argThat((CallbackPayload p) -> !p.success() && "HANDLER_TIMEOUT".equals(p.error().code())),
            any(),
            isNull());
    }

    @Test
    void invoke_normalizesOutputOnceForResponseAndCallback() {
        when(handler.handle(any())).thenReturn(Map.of("wordCount", 4, "topWords", List.of()));
        when(runtimeContextResolver.resolve(any(), any()))
                .thenReturn(new InvocationRuntimeContext("exec-normalized", "trace-normalized"));

        ResponseEntity<Object> response = controller.invoke(
                new InvocationRequest(Map.of("text", "the quick brown fox"), Map.of()),
                "exec-normalized",
                "trace-normalized");

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody() instanceof JsonNode);
        JsonNode body = (JsonNode) response.getBody();
        assertEquals(4, body.get("wordCount").asInt());

        ArgumentCaptor<CallbackPayload> payloadCaptor = ArgumentCaptor.forClass(CallbackPayload.class);
        verify(callbackDispatcher).submit(eq("exec-normalized"), payloadCaptor.capture(), eq("trace-normalized"), isNull());
        assertTrue(payloadCaptor.getValue().success());
        assertEquals(4, payloadCaptor.getValue().output().get("wordCount").asInt());
    }

    @Test
    void coldStartHeader_isReportedOnlyForFirstResolvedInvocation() {
        when(handler.handle(any())).thenReturn("ok");
        when(runtimeContextResolver.resolve(any(), any()))
                .thenReturn(new InvocationRuntimeContext("env-exec-id", "trace-1"));
        when(coldStartTracker.firstInvocation()).thenReturn(true, false);
        when(coldStartTracker.initDurationMs()).thenReturn(123L);

        InvocationRequest request = new InvocationRequest("input", null);
        ResponseEntity<Object> first = controller.invoke(request, null, "trace-1");
        ResponseEntity<Object> second = controller.invoke(request, null, "trace-1");

        assertEquals("true", first.getHeaders().getFirst("X-Cold-Start"));
        assertEquals("123", first.getHeaders().getFirst("X-Init-Duration-Ms"));
        assertNull(second.getHeaders().getFirst("X-Cold-Start"));
        assertNull(second.getHeaders().getFirst("X-Init-Duration-Ms"));
        verify(coldStartTracker, times(2)).firstInvocation();
        verify(coldStartTracker).initDurationMs();
    }

    @Test
    void invoke_handlerReturnsHandlerResponse_usesItsStatusAndHeaders() {
        when(handler.handle(any())).thenReturn(
                HandlerResponse.of(Map.of("error", "not found"), 404, Map.of("Content-Type", "application/json")));

        InvocationRequest request = new InvocationRequest("input", null);
        ResponseEntity<Object> response = controller.invoke(request, null, null);

        assertEquals(404, response.getStatusCode().value());
        assertEquals("application/json", response.getHeaders().getFirst("Content-Type"));
        assertEquals("true", response.getHeaders().getFirst("X-NanoFaaS-Function-Status"));
    }

    @Test
    void invoke_handlerReturnsHandlerResponse_dropsDisallowedHeaders() {
        when(handler.handle(any())).thenReturn(
                HandlerResponse.of("body", 200, Map.of("X-Execution-Id", "spoof", "X-Custom", "nope")));

        InvocationRequest request = new InvocationRequest("input", null);
        ResponseEntity<Object> response = controller.invoke(request, "env-exec-id", null);

        assertEquals(200, response.getStatusCode().value());
        assertNull(response.getHeaders().getFirst("X-Execution-Id"));
        assertNull(response.getHeaders().getFirst("X-Custom"));
    }

    @Test
    void invoke_handlerReturnsHandlerResponse_disallowedHeaderLogsWarnWithHeaderNameAndExecutionId() {
        Logger controllerLogger = (Logger) LoggerFactory.getLogger(InvokeController.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        controllerLogger.addAppender(appender);
        try {
            when(handler.handle(any())).thenReturn(
                    HandlerResponse.of("body", 200, Map.of("X-Custom", "nope")));

            controller.invoke(new InvocationRequest("input", null), "env-exec-id", null);

            boolean warned = appender.list.stream().anyMatch(event ->
                    event.getLevel() == Level.WARN
                            && event.getFormattedMessage().contains("X-Custom")
                            && event.getFormattedMessage().contains("env-exec-id"));
            assertTrue(warned, "expected a WARN log naming the dropped header and execution id, got: " + appender.list);
        } finally {
            controllerLogger.detachAppender(appender);
        }
    }

    @Test
    void invoke_handlerReturnsHandlerResponse_invalidStatusCodeFallsBackToPlatformError() {
        when(handler.handle(any())).thenReturn(HandlerResponse.of("body", 999));

        InvocationRequest request = new InvocationRequest("input", null);
        ResponseEntity<Object> response = controller.invoke(request, null, null);

        assertEquals(500, response.getStatusCode().value());
        assertNull(response.getHeaders().getFirst("X-NanoFaaS-Function-Status"));
    }

    @Test
    void invoke_handlerReturnsPlainValue_behavesExactlyAsToday() {
        when(handler.handle(any())).thenReturn(Map.of("roman", "XLII"));

        InvocationRequest request = new InvocationRequest("input", null);
        ResponseEntity<Object> response = controller.invoke(request, null, null);

        assertEquals(200, response.getStatusCode().value());
        assertNull(response.getHeaders().getFirst("X-NanoFaaS-Function-Status"));
    }

    @Test
    void invoke_handlerReturnsHandlerResponseWithEncoding_emitsEncodingHeader() {
        when(handler.handle(any())).thenReturn(
                new HandlerResponse("aGVsbG8=", 200, Map.of("Content-Type", "application/octet-stream"), "base64"));

        InvocationRequest request = new InvocationRequest("input", null);
        ResponseEntity<Object> response = controller.invoke(request, null, null);

        assertEquals("base64", response.getHeaders().getFirst("X-NanoFaaS-Encoding"));
    }

    @Test
    void invoke_handlerReturnsHandlerResponseWithoutEncoding_omitsEncodingHeader() {
        when(handler.handle(any())).thenReturn(
                HandlerResponse.of(Map.of("error", "not found"), 404, Map.of("Content-Type", "application/json")));

        InvocationRequest request = new InvocationRequest("input", null);
        ResponseEntity<Object> response = controller.invoke(request, null, null);

        assertNull(response.getHeaders().getFirst("X-NanoFaaS-Encoding"));
    }

    @Test
    void invoke_handlerReturnsPlainValue_neverEmitsEncodingHeader() {
        when(handler.handle(any())).thenReturn("ok");

        InvocationRequest request = new InvocationRequest("input", null);
        ResponseEntity<Object> response = controller.invoke(request, null, null);

        assertNull(response.getHeaders().getFirst("X-NanoFaaS-Encoding"));
    }

    @Test
    void invoke_handlerCannotSpoofEncodingHeaderThroughItsOwnHeadersMap() {
        // X-NanoFaaS-Encoding is not in ResponseHeaderPolicy.ALLOWED_RESPONSE_HEADERS, so a
        // handler stuffing it into its own headers map must never leak it through — only the
        // envelope's dedicated `encoding` field can produce this header.
        when(handler.handle(any())).thenReturn(
                new HandlerResponse("body", 200, Map.of("X-NanoFaaS-Encoding", "spoofed"), null));

        InvocationRequest request = new InvocationRequest("input", null);
        ResponseEntity<Object> response = controller.invoke(request, null, null);

        assertNull(response.getHeaders().getFirst("X-NanoFaaS-Encoding"));
    }

    @Test
    void invoke_passesCallerHeadersFromBodyToHandler() {
        ArgumentCaptor<InvocationRequest> requestCaptor = ArgumentCaptor.forClass(InvocationRequest.class);
        when(handler.handle(requestCaptor.capture())).thenReturn("ok");

        InvocationRequest request = new InvocationRequest("input", null, Map.of("authorization", "Bearer x"));
        controller.invoke(request, null, null);

        assertEquals("Bearer x", requestCaptor.getValue().headers().get("authorization"));
    }
}
