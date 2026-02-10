package it.unimib.datai.nanofaas.sdk.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    private CallbackClient callbackClient;
    private HandlerRegistry handlerRegistry;
    private FunctionHandler handler;
    private InvokeController controller;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        callbackClient = mock(CallbackClient.class);
        handlerRegistry = mock(HandlerRegistry.class);
        handler = mock(FunctionHandler.class);
        when(handlerRegistry.resolve()).thenReturn(handler);
        controller = new InvokeController(callbackClient, handlerRegistry, "env-exec-id");
    }

    @Test
    void invoke_success_returnsOkWithOutput() {
        when(handler.handle(any())).thenReturn(Map.of("result", "hello"));
        when(callbackClient.sendResult(anyString(), any(), anyString())).thenReturn(true);

        InvocationRequest request = new InvocationRequest("input", null);
        ResponseEntity<Object> response = controller.invoke(request, null, "trace-1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(Map.of("result", "hello"), response.getBody());
        verify(callbackClient).sendResult(eq("env-exec-id"), any(InvocationResult.class), eq("trace-1"));
    }

    @Test
    void invoke_headerExecutionIdOverridesEnv() {
        when(handler.handle(any())).thenReturn("ok");
        when(callbackClient.sendResult(anyString(), any(), any())).thenReturn(true);

        InvocationRequest request = new InvocationRequest("input", null);
        controller.invoke(request, "header-exec-id", null);

        verify(callbackClient).sendResult(eq("header-exec-id"), any(), isNull());
    }

    @Test
    void invoke_handlerThrows_returns500AndSendsErrorCallback() {
        when(handler.handle(any())).thenThrow(new RuntimeException("boom"));

        InvocationRequest request = new InvocationRequest("input", null);
        ResponseEntity<Object> response = controller.invoke(request, null, "t-1");

        assertEquals(500, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) response.getBody();
        assertEquals("boom", body.get("error"));
        verify(callbackClient).sendResult(eq("env-exec-id"), argThat(r -> !r.success()), eq("t-1"));
    }

    @Test
    void invoke_callbackFails_stillReturnsOk() {
        when(handler.handle(any())).thenReturn("data");
        when(callbackClient.sendResult(anyString(), any(), any())).thenReturn(false);

        InvocationRequest request = new InvocationRequest("input", null);
        ResponseEntity<Object> response = controller.invoke(request, null, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("data", response.getBody());
    }

    @Test
    void invoke_noExecutionId_returnsBadRequest() {
        InvokeController noExecController = new InvokeController(callbackClient, handlerRegistry, null);

        InvocationRequest request = new InvocationRequest("input", null);
        ResponseEntity<Object> response = noExecController.invoke(request, null, null);

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void invoke_blankHeaderAndBlankEnv_returnsBadRequest() {
        InvokeController blankController = new InvokeController(callbackClient, handlerRegistry, "  ");

        InvocationRequest request = new InvocationRequest("input", null);
        ResponseEntity<Object> response = blankController.invoke(request, "  ", null);

        assertEquals(400, response.getStatusCode().value());
    }
}
