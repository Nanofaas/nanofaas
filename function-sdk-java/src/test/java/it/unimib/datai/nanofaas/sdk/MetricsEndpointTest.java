package it.unimib.datai.nanofaas.sdk;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
        mvc.perform(get("/metrics"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("# HELP")));
    }
}
