package it.unimib.datai.nanofaas.sdk.runtime;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HandlerExecutorTest {

    private HandlerExecutor executor;

    @Test
    void commonTimeoutEnvironmentValueUsesMilliseconds() {
        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                    context, "NANOFAAS_HANDLER_TIMEOUT=25");
            context.registerBean(HandlerExecutor.class);
            context.refresh();
            HandlerExecutor configured = context.getBean(HandlerExecutor.class);

            assertThrows(TimeoutException.class, () -> configured.execute(
                    request -> {
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
                        return "late";
                    }, new InvocationRequest(null, null)));
        }
    }

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

    @SuppressWarnings("java:S2925") // the handler must outlive the 100ms executor timeout: the 5s sleep guarantees the timeout fires while the handler is still running
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
