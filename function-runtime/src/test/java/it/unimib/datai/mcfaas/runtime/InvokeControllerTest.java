package com.mcfaas.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class InvokeControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void issue014_invokeContractReturnsInput() throws Exception {
        mockMvc.perform(post("/invoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{\"message\":\"hi\"}}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"message\":\"hi\"}"));
    }
}
