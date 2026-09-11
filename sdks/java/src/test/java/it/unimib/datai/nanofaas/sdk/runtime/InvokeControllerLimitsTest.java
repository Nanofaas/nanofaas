package it.unimib.datai.nanofaas.sdk.runtime;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InvokeControllerLimitsTest {
    @Test
    void handlerSaturationReturnsCanonicalRetryable429() throws Exception {
        CallbackDispatcher callbacks = mock(CallbackDispatcher.class);
        HandlerExecutor handlers = mock(HandlerExecutor.class);
        when(handlers.execute(any(), any())).thenThrow(new HandlerSaturatedException());
        HandlerRegistry registry = mock(HandlerRegistry.class);
        when(registry.resolve()).thenReturn(_ -> "bad");
        InvocationRuntimeContextResolver resolver = mock(InvocationRuntimeContextResolver.class);
        when(resolver.resolve(any(), any())).thenReturn(new InvocationRuntimeContext("exec", "trace"));
        var mapper = JsonMapper.builder().build();
        InvokeController controller = new InvokeController(callbacks, registry, resolver,
                new ColdStartTracker(), handlers, new JsonOutputNormalizer(mapper),
                new RuntimePayloadLimits(mapper, 1024));

        var response = controller.invoke(new InvocationRequest(Map.of(), null), "exec", "trace", "1");

        assertEquals(429, response.getStatusCode().value());
        assertEquals("1", response.getHeaders().getFirst("Retry-After"));
        assertEquals(Map.of("error", Map.of("code", "RUNTIME_HANDLER_SATURATED",
                "message", "Runtime handler capacity exhausted")), response.getBody());
    }

    @Test
    void stoppedRuntimeReturnsCanonicalRetryable503() throws Exception {
        CallbackDispatcher callbacks = mock(CallbackDispatcher.class);
        HandlerExecutor handlers = mock(HandlerExecutor.class);
        when(handlers.execute(any(), any())).thenThrow(new RuntimeStoppingException());
        HandlerRegistry registry = mock(HandlerRegistry.class);
        when(registry.resolve()).thenReturn(_ -> "bad");
        InvocationRuntimeContextResolver resolver = mock(InvocationRuntimeContextResolver.class);
        when(resolver.resolve(any(), any())).thenReturn(new InvocationRuntimeContext("exec", "trace"));
        var mapper = JsonMapper.builder().build();
        InvokeController controller = new InvokeController(callbacks, registry, resolver,
                new ColdStartTracker(), handlers, new JsonOutputNormalizer(mapper),
                new RuntimePayloadLimits(mapper, 1024));

        var response = controller.invoke(new InvocationRequest(Map.of(), null), "exec", "trace", "1");

        assertEquals(503, response.getStatusCode().value());
        assertEquals("1", response.getHeaders().getFirst("Retry-After"));
    }

    @Test
    void callbackSaturationRejectsBeforeHandlerWithCanonicalRetryable429() throws Exception {
        CallbackDispatcher callbacks = mock(CallbackDispatcher.class);
        when(callbacks.reserveInvocation()).thenThrow(new CallbackSaturatedException());
        HandlerRegistry registry = mock(HandlerRegistry.class);
        var invoked = new java.util.concurrent.atomic.AtomicBoolean();
        when(registry.resolve()).thenReturn(_ -> { invoked.set(true); return "bad"; });
        InvocationRuntimeContextResolver resolver = mock(InvocationRuntimeContextResolver.class);
        when(resolver.resolve(any(), any())).thenReturn(new InvocationRuntimeContext("exec", "trace"));
        var mapper = JsonMapper.builder().build();
        InvokeController controller = new InvokeController(callbacks, registry, resolver,
                new ColdStartTracker(), new HandlerExecutor(1_000), new JsonOutputNormalizer(mapper),
                new RuntimePayloadLimits(mapper, 1024));

        var response = controller.invoke(new InvocationRequest(Map.of(), null), "exec", "trace", "1");

        assertEquals(429, response.getStatusCode().value());
        assertEquals("1", response.getHeaders().getFirst("Retry-After"));
        assertFalse(invoked.get());
    }

    @Test
    void oversizedOutputReturnsCanonical500AndStillSubmitsBoundedErrorCallback() throws Exception {
        CallbackDispatcher callbacks = mock(CallbackDispatcher.class);
        HandlerRegistry registry = mock(HandlerRegistry.class);
        when(registry.resolve()).thenReturn(_ -> Map.of("value", "01234567890123456789"));
        InvocationRuntimeContextResolver resolver = mock(InvocationRuntimeContextResolver.class);
        when(resolver.resolve(any(), any())).thenReturn(new InvocationRuntimeContext("exec", "trace"));
        var mapper = JsonMapper.builder().build();
        InvokeController controller = new InvokeController(callbacks, registry, resolver,
                new ColdStartTracker(), new HandlerExecutor(1_000), new JsonOutputNormalizer(mapper),
                new RuntimePayloadLimits(mapper, 16));

        var response = controller.invoke(new InvocationRequest(Map.of(), null), "exec", "trace", "1");

        assertEquals(500, response.getStatusCode().value());
        assertEquals(Map.of("error", Map.of("code", "RUNTIME_OUTPUT_TOO_LARGE",
                "message", "Runtime output exceeds configured byte limit")), response.getBody());
        verify(callbacks).submit(eq("exec"), argThat(payload -> !payload.success()), eq("trace"), eq("1"));
    }
}
