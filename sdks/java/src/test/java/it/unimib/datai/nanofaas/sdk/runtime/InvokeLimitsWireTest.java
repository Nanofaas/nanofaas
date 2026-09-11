package it.unimib.datai.nanofaas.sdk.runtime;

import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InvokeLimitsWireTest {
    private static final String REQUEST = "{\"input\":{}}";

    @Test
    void inputAndOutputCapsHaveCanonicalWireResponses() throws Exception {
        MockMvc input = mvc(_ -> Map.of("ok", true), mock(CallbackDispatcher.class),
                mock(HandlerExecutor.class), 16, 1_024);
        input.perform(post("/invoke").header("X-Execution-Id", "exec")
                        .contentType("application/json")
                        .content("{\"input\":\"01234567890123456789\"}"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(content().json("""
                        {"error":{"code":"RUNTIME_INPUT_TOO_LARGE",
                        "message":"Runtime input exceeds configured byte limit"}}
                        """));

        MockMvc output = mvc(_ -> Map.of("value", "01234567890123456789"),
                mock(CallbackDispatcher.class), new HandlerExecutor(1_000), 1_024, 16);
        output.perform(post("/invoke").header("X-Execution-Id", "exec")
                        .contentType("application/json").content(REQUEST))
                .andExpect(status().isInternalServerError())
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(content().json("""
                        {"error":{"code":"RUNTIME_OUTPUT_TOO_LARGE",
                        "message":"Runtime output exceeds configured byte limit"}}
                        """));
    }

    @Test
    void callbackAndHandlerSaturationHaveDistinctCanonicalWireResponses() throws Exception {
        CallbackDispatcher callbacks = mock(CallbackDispatcher.class);
        when(callbacks.reserveInvocation()).thenThrow(new CallbackSaturatedException());
        mvc(_ -> Map.of("ok", true), callbacks, new HandlerExecutor(1_000), 1_024, 1_024)
                .perform(post("/invoke").header("X-Execution-Id", "exec")
                        .contentType("application/json").content(REQUEST))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(content().json("""
                        {"error":{"code":"RUNTIME_CALLBACK_SATURATED",
                        "message":"Runtime callback capacity exhausted"}}
                        """));

        HandlerExecutor handlers = mock(HandlerExecutor.class);
        when(handlers.execute(any(), any())).thenThrow(new HandlerSaturatedException());
        mvc(_ -> Map.of("ok", true), mock(CallbackDispatcher.class), handlers, 1_024, 1_024)
                .perform(post("/invoke").header("X-Execution-Id", "exec")
                        .contentType("application/json").content(REQUEST))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(content().json("""
                        {"error":{"code":"RUNTIME_HANDLER_SATURATED",
                        "message":"Runtime handler capacity exhausted"}}
                        """));
    }

    @Test
    void stoppingHasCanonicalWireResponse() throws Exception {
        HandlerExecutor handlers = mock(HandlerExecutor.class);
        when(handlers.execute(any(), any())).thenThrow(new RuntimeStoppingException());
        mvc(_ -> Map.of("ok", true), mock(CallbackDispatcher.class), handlers, 1_024, 1_024)
                .perform(post("/invoke").header("X-Execution-Id", "exec")
                        .contentType("application/json").content(REQUEST))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(content().json("""
                        {"error":{"code":"RUNTIME_STOPPING","message":"Runtime is stopping"}}
                        """));
    }

    private static MockMvc mvc(FunctionHandler function, CallbackDispatcher callbacks,
                               HandlerExecutor handlers, int maxInputBytes, int maxOutputBytes) {
        HandlerRegistry registry = mock(HandlerRegistry.class);
        when(registry.resolve()).thenReturn(function);
        InvocationRuntimeContextResolver resolver = mock(InvocationRuntimeContextResolver.class);
        when(resolver.resolve(any(), any())).thenReturn(new InvocationRuntimeContext("exec", "trace"));
        JsonMapper mapper = JsonMapper.builder().build();
        InvokeController controller = new InvokeController(callbacks, registry, resolver,
                new ColdStartTracker(), handlers, new JsonOutputNormalizer(mapper),
                new RuntimePayloadLimits(mapper, maxOutputBytes));
        return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new RuntimePayloadLimitFilter(maxInputBytes))
                .build();
    }
}
