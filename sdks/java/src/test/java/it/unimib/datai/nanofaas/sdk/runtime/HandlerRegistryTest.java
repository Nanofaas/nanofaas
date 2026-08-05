package it.unimib.datai.nanofaas.sdk.runtime;

import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HandlerRegistryTest {

    @Test
    void resolve_singleHandler_returnsIt() {
        FunctionHandler handler = mock(FunctionHandler.class);
        RuntimeSettings settings = new RuntimeSettings(null, null, null, null);
        HandlerRegistry registry = new HandlerRegistry(Map.of("myHandler", handler), settings);

        assertSame(handler, registry.resolve());
    }

    @Test
    void resolve_singleHandler_resolve_twice_returnsSameInstance() {
        FunctionHandler handler = mock(FunctionHandler.class);
        RuntimeSettings settings = new RuntimeSettings(null, null, null, null);
        HandlerRegistry registry = new HandlerRegistry(Map.of("myHandler", handler), settings);

        assertSame(registry.resolve(), registry.resolve());
    }

    @Test
    void resolve_noHandlers_throwsIllegalState() {
        RuntimeSettings settings = new RuntimeSettings(null, null, null, null);
        Map<String, FunctionHandler> handlers = Map.of();
        assertThrows(IllegalStateException.class,
                () -> new HandlerRegistry(handlers, settings));
    }

    @Test
    void resolve_multipleHandlers_withFunctionHandlerSet_returnsNamedBean() {
        FunctionHandler h1 = mock(FunctionHandler.class);
        FunctionHandler h2 = mock(FunctionHandler.class);
        RuntimeSettings settings = new RuntimeSettings(null, null, null, "betaHandler");
        HandlerRegistry registry = new HandlerRegistry(Map.of("alphaHandler", h1, "betaHandler", h2), settings);

        assertSame(h2, registry.resolve());
    }

    @Test
    void resolve_multipleHandlers_withoutFunctionHandlerSet_throwsIllegalState() {
        FunctionHandler h1 = mock(FunctionHandler.class);
        FunctionHandler h2 = mock(FunctionHandler.class);
        RuntimeSettings settings = new RuntimeSettings(null, null, null, null);

        Map<String, FunctionHandler> handlers = Map.of("alphaHandler", h1, "betaHandler", h2);
        assertThrows(IllegalStateException.class,
                () -> new HandlerRegistry(handlers, settings));
    }

    @Test
    void resolve_multipleHandlers_errorMessageListsAvailableBeans() {
        FunctionHandler h1 = mock(FunctionHandler.class);
        FunctionHandler h2 = mock(FunctionHandler.class);
        RuntimeSettings settings = new RuntimeSettings(null, null, null, null);

        Map<String, FunctionHandler> handlers = Map.of("alphaHandler", h1, "betaHandler", h2);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new HandlerRegistry(handlers, settings));

        String msg = ex.getMessage();
        assertTrue(msg.contains("alphaHandler") || msg.contains("betaHandler"),
                "Error message should list available bean names but was: " + msg);
        assertTrue(msg.contains("FUNCTION_HANDLER"),
                "Error message should mention FUNCTION_HANDLER env var");
    }

    @Test
    void resolve_multipleHandlers_unknownFunctionHandlerName_throwsIllegalState() {
        FunctionHandler h1 = mock(FunctionHandler.class);
        RuntimeSettings settings = new RuntimeSettings(null, null, null, "nonExistentHandler");

        Map<String, FunctionHandler> handlers = Map.of("myHandler", h1);
        assertThrows(IllegalStateException.class,
                () -> new HandlerRegistry(handlers, settings));
    }
}
