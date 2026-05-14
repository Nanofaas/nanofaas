package it.unimib.datai.nanofaas.runtime;

import it.unimib.datai.nanofaas.sdk.runtime.CallbackClient;
import it.unimib.datai.nanofaas.sdk.runtime.CallbackPayload;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "EXECUTION_ID=test-execution")
@AutoConfigureMockMvc
class InvokeControllerTest {
    private static final int CALLBACK_TIMEOUT_MS = 2_000;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CallbackClient callbackClient;

    @Test
    void issue014_invokeContractReturnsInput() throws Exception {
        when(callbackClient.sendResult(any(), any(CallbackPayload.class), any())).thenReturn(true);

        mockMvc.perform(post("/invoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{\"message\":\"hi\"}}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"message\":\"hi\"}"));
    }

    @Test
    void invokeUsesExecutionIdFromHeader() throws Exception {
        when(callbackClient.sendResult(any(), any(CallbackPayload.class), any())).thenReturn(true);

        mockMvc.perform(post("/invoke")
                        .header("X-Execution-Id", "header-exec-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\": \"test\", \"metadata\": {}}"))
                .andExpect(status().isOk());

        verify(callbackClient, timeout(CALLBACK_TIMEOUT_MS))
                .sendResult(eq("header-exec-123"), any(CallbackPayload.class), any());
    }

    @Test
    void invokeUsesEnvExecutionIdWhenHeaderNotProvided() throws Exception {
        when(callbackClient.sendResult(any(), any(CallbackPayload.class), any())).thenReturn(true);

        mockMvc.perform(post("/invoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\": \"test\", \"metadata\": {}}"))
                .andExpect(status().isOk());

        verify(callbackClient, timeout(CALLBACK_TIMEOUT_MS))
                .sendResult(eq("test-execution"), any(CallbackPayload.class), any());
    }

    @Test
    void invokeHeaderTakesPrecedenceOverEnv() throws Exception {
        when(callbackClient.sendResult(any(), any(CallbackPayload.class), any())).thenReturn(true);

        mockMvc.perform(post("/invoke")
                        .header("X-Execution-Id", "header-takes-precedence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\": \"test\", \"metadata\": {}}"))
                .andExpect(status().isOk());

        verify(callbackClient, timeout(CALLBACK_TIMEOUT_MS))
                .sendResult(eq("header-takes-precedence"), any(CallbackPayload.class), any());
    }

    @Test
    void invokePropagatesTraceIdToCallback() throws Exception {
        when(callbackClient.sendResult(any(), any(CallbackPayload.class), any())).thenReturn(true);

        mockMvc.perform(post("/invoke")
                        .header("X-Execution-Id", "exec-123")
                        .header("X-Trace-Id", "trace-456")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\": \"test\", \"metadata\": {}}"))
                .andExpect(status().isOk());

        verify(callbackClient, timeout(CALLBACK_TIMEOUT_MS))
                .sendResult(eq("exec-123"), any(CallbackPayload.class), eq("trace-456"));
    }

    @Test
    void invokePropagatesNullTraceIdWhenHeaderNotProvided() throws Exception {
        when(callbackClient.sendResult(any(), any(CallbackPayload.class), any())).thenReturn(true);

        mockMvc.perform(post("/invoke")
                        .header("X-Execution-Id", "exec-789")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\": \"test\", \"metadata\": {}}"))
                .andExpect(status().isOk());

        verify(callbackClient, timeout(CALLBACK_TIMEOUT_MS))
                .sendResult(eq("exec-789"), any(CallbackPayload.class), (String) isNull());
    }
}
