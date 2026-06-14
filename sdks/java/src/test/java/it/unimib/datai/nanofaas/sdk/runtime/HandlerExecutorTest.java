package it.unimib.datai.nanofaas.sdk.runtime;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HandlerExecutorTest {

    private HandlerExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) executor.shutdown();
    }

    @Test
    void execute_handlerCompletesBeforeTimeout_returnsResult() throws Exception {
        executor = new HandlerExecutor(5000);
        FunctionHandler handler = mock(FunctionHandler.class);
        InvocationRequest request = new InvocationRequest("input", null);
        when(handler.handle(request)).thenReturn("output");

        Object result = executor.execute(handler, request);

        assertEquals("output", result);
    }

    @Test
    void execute_handlerExceedsTimeout_throwsTimeoutException() {
        executor = new HandlerExecutor(100);
        FunctionHandler handler = mock(FunctionHandler.class);
        InvocationRequest request = new InvocationRequest("input", null);
        when(handler.handle(any())).thenAnswer(inv -> {
            Thread.sleep(5000);
            return "never";
        });

        assertThrows(TimeoutException.class, () -> executor.execute(handler, request));
    }

    @Test
    void execute_handlerThrowsRuntimeException_propagatesException() {
        executor = new HandlerExecutor(5000);
        FunctionHandler handler = mock(FunctionHandler.class);
        InvocationRequest request = new InvocationRequest("input", null);
        when(handler.handle(any())).thenThrow(new IllegalStateException("boom"));

        assertThrows(IllegalStateException.class, () -> executor.execute(handler, request));
    }
}
