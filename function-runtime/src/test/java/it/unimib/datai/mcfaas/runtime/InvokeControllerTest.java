package it.unimib.datai.mcfaas.runtime;

import it.unimib.datai.mcfaas.common.model.InvocationResult;
import it.unimib.datai.mcfaas.runtime.core.CallbackClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class InvokeControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CallbackClient callbackClient;

    @Test
    void issue014_invokeContractReturnsInput() throws Exception {
        when(callbackClient.sendResult(any(), any(), any())).thenReturn(true);

        mockMvc.perform(post("/invoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{\"message\":\"hi\"}}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"message\":\"hi\"}"));
    }

    @Test
    void invokeUsesExecutionIdFromHeader() throws Exception {
        when(callbackClient.sendResult(any(), any(), any())).thenReturn(true);

        mockMvc.perform(post("/invoke")
                        .header("X-Execution-Id", "header-exec-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\": \"test\", \"metadata\": {}}"))
                .andExpect(status().isOk());

        // Verify callback was called with header execution ID
        verify(callbackClient).sendResult(eq("header-exec-123"), any(InvocationResult.class), any());
    }

    @Test
    void invokeUsesEnvExecutionIdWhenHeaderNotProvided() throws Exception {
        when(callbackClient.sendResult(any(), any(), any())).thenReturn(true);

        mockMvc.perform(post("/invoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\": \"test\", \"metadata\": {}}"))
                .andExpect(status().isOk());

        // Verify callback was called with default/env execution ID (test-execution is the default)
        verify(callbackClient).sendResult(eq("test-execution"), any(InvocationResult.class), any());
    }

    @Test
    void invokeHeaderTakesPrecedenceOverEnv() throws Exception {
        // This test verifies that when both header and env are set, header wins
        when(callbackClient.sendResult(any(), any(), any())).thenReturn(true);

        mockMvc.perform(post("/invoke")
                        .header("X-Execution-Id", "header-takes-precedence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\": \"test\", \"metadata\": {}}"))
                .andExpect(status().isOk());

        // Verify header execution ID was used, not the env one
        verify(callbackClient).sendResult(eq("header-takes-precedence"), any(InvocationResult.class), any());
    }

    @Test
    void invokePropagatesTraceIdToCallback() throws Exception {
        when(callbackClient.sendResult(any(), any(), any())).thenReturn(true);

        mockMvc.perform(post("/invoke")
                        .header("X-Execution-Id", "exec-123")
                        .header("X-Trace-Id", "trace-456")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\": \"test\", \"metadata\": {}}"))
                .andExpect(status().isOk());

        verify(callbackClient).sendResult(eq("exec-123"), any(InvocationResult.class), eq("trace-456"));
    }

    @Test
    void invokePropagatesNullTraceIdWhenHeaderNotProvided() throws Exception {
        when(callbackClient.sendResult(any(), any(), any())).thenReturn(true);

        mockMvc.perform(post("/invoke")
                        .header("X-Execution-Id", "exec-789")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\": \"test\", \"metadata\": {}}"))
                .andExpect(status().isOk());

        verify(callbackClient).sendResult(eq("exec-789"), any(InvocationResult.class), (String) isNull());
    }
}
