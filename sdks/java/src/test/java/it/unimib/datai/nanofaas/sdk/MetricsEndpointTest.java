package it.unimib.datai.nanofaas.sdk;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import it.unimib.datai.nanofaas.sdk.runtime.HandlerRegistry;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.when;

import org.springframework.http.MediaType;

import java.util.Map;

@SpringBootTest(
        classes = MetricsEndpointTest.TestApp.class,
        properties = {
                "management.metrics.export.prometheus.enabled=true",
                "management.endpoint.prometheus.enabled=true",
                "management.endpoints.web.exposure.include=health,prometheus"
        }
)
@AutoConfigureMockMvc
class MetricsEndpointTest {

    @SpringBootApplication
    static class TestApp {
    }

    @MockitoBean
    HandlerRegistry handlerRegistry;

    @Autowired
    private MockMvc mvc;

    @Test
    void health_isExposed() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ok")));
    }

    @Test
    void metrics_isExposed() throws Exception {
        when(handlerRegistry.resolve()).thenReturn(request -> Map.of("ok", true));
        mvc.perform(post("/invoke")
                        .header("X-Execution-Id", "exec-metrics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":\"ok\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/metrics"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("runtime_invocations_total")))
                .andExpect(content().string(containsString("runtime_invocation_duration_seconds")))
                .andExpect(content().string(containsString("runtime_in_flight")))
                .andExpect(content().string(containsString("runtime_cold_start")))
                .andExpect(content().string(containsString("runtime_callback_failures")));
    }
}
