package it.unimib.datai.nanofaas.sdk.runtime;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HandlerRegistryTest {

    @Test
    void resolve_singleHandler_returnsIt() {
        FunctionHandler handler = request -> "ok";
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(FunctionHandler.class)).thenReturn(Map.of("echo", handler));

        HandlerRegistry registry = new HandlerRegistry(ctx);
        assertSame(handler, registry.resolve());
    }

    @Test
    void resolve_cachedOnSecondCall() {
        FunctionHandler handler = request -> "ok";
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(FunctionHandler.class)).thenReturn(Map.of("echo", handler));

        HandlerRegistry registry = new HandlerRegistry(ctx);
        registry.resolve();
        registry.resolve();

        // getBeansOfType is only called once due to caching
        verify(ctx, times(1)).getBeansOfType(FunctionHandler.class);
    }

    @Test
    void resolve_noHandlers_throws() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(FunctionHandler.class)).thenReturn(Map.of());

        HandlerRegistry registry = new HandlerRegistry(ctx);
        IllegalStateException ex = assertThrows(IllegalStateException.class, registry::resolve);
        assertTrue(ex.getMessage().contains("No FunctionHandler beans"));
    }

    @Test
    void resolve_multipleHandlersNoEnv_throws() {
        FunctionHandler h1 = request -> "a";
        FunctionHandler h2 = request -> "b";
        Map<String, FunctionHandler> handlers = new LinkedHashMap<>();
        handlers.put("h1", h1);
        handlers.put("h2", h2);

        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(FunctionHandler.class)).thenReturn(handlers);

        HandlerRegistry registry = new HandlerRegistry(ctx);
        // envFunctionHandler is null (FUNCTION_HANDLER env not set in test), so should throw
        IllegalStateException ex = assertThrows(IllegalStateException.class, registry::resolve);
        assertTrue(ex.getMessage().contains("Multiple FunctionHandler beans"));
    }
}
