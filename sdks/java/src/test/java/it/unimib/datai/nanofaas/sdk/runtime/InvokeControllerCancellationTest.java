package it.unimib.datai.nanofaas.sdk.runtime;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InvokeControllerCancellationTest {
    @Test
    void interruptedInvocationEmitsCanonicalTerminalCallbackAndNoWireSuccess() throws Exception {
        CallbackDispatcher callbacks = mock(CallbackDispatcher.class);
        CallbackDispatcher.CallbackReservation reservation = mock(CallbackDispatcher.CallbackReservation.class);
        when(callbacks.reserveInvocation()).thenReturn(reservation);
        when(callbacks.submit(eq(reservation), anyString(), any(), any(), any()))
                .thenReturn(CallbackDispatcher.SubmitResult.ACCEPTED);
        HandlerRegistry handlers = mock(HandlerRegistry.class);
        when(handlers.resolve()).thenReturn(_ -> "unused");
        InvocationRuntimeContextResolver contexts = mock(InvocationRuntimeContextResolver.class);
        when(contexts.resolve(any(), any())).thenReturn(new InvocationRuntimeContext("execution", "trace"));
        HandlerExecutor executor = mock(HandlerExecutor.class);
        when(executor.execute(any(), any())).thenThrow(new InterruptedException("cancelled"));
        InvokeController controller = new InvokeController(callbacks, handlers, contexts,
                mock(ColdStartTracker.class), executor, new JsonOutputNormalizer(new tools.jackson.databind.ObjectMapper()));

        var request = new InvocationRequest(null, null);
        assertThrows(InvocationCancelledException.class, () ->
                controller.invoke(request, "execution", "trace", "1"));
        verify(callbacks).submit(eq(reservation), eq("execution"),
                argThat(payload -> !payload.success()
                        && "INVOCATION_CANCELLED".equals(payload.error().code())
                        && "Invocation cancelled".equals(payload.error().message())),
                eq("trace"), eq("1"));
        Thread.interrupted();
    }
}
