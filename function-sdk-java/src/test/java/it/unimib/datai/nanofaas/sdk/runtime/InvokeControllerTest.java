package it.unimib.datai.nanofaas.sdk.runtime;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

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
        when(callbackDispatcher.submit(anyString(), any(), any())).thenReturn(true);
        when(runtimeContextResolver.resolve(any(), any()))
                .thenReturn(new InvocationRuntimeContext("env-exec-id", null));
        when(coldStartTracker.firstInvocation()).thenReturn(false);
        controller = new InvokeController(callbackDispatcher, handlerRegistry, runtimeContextResolver, coldStartTracker);
    }

    @Test
    void invoke_success_returnsOkWithOutput() {
        when(handler.handle(any())).thenReturn(Map.of("result", "hello"));
        when(runtimeContextResolver.resolve(any(), any()))
                .thenReturn(new InvocationRuntimeContext("env-exec-id", "trace-1"));

        InvocationRequest request = new InvocationRequest("input", null);
        ResponseEntity<Object> response = controller.invoke(request, null, "trace-1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(Map.of("result", "hello"), response.getBody());
        verify(callbackDispatcher).submit(eq("env-exec-id"), any(InvocationResult.class), eq("trace-1"));
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
        verify(callbackDispatcher).submit(eq("resolved-exec-id"), any(), eq("resolved-trace"));
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
                argThat((InvocationResult r) -> !r.success()),
                eq("t-1"));
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
                argThat((InvocationResult r) -> !r.success() && r.error() != null
                        && "HANDLER_ERROR".equals(r.error().code())
                        && "Handler execution failed".equals(r.error().message())),
                eq("t-2"));
    }

    @Test
    void invoke_callbackFails_stillReturnsOk() {
        when(handler.handle(any())).thenReturn("data");
        when(callbackDispatcher.submit(anyString(), any(), any())).thenReturn(false);
        when(runtimeContextResolver.resolve(any(), any()))
                .thenReturn(new InvocationRuntimeContext("env-exec-id", null));

        InvocationRequest request = new InvocationRequest("input", null);
        ResponseEntity<Object> response = controller.invoke(request, null, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("data", response.getBody());
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
}
