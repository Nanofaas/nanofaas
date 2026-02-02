package com.mcfaas.controlplane;

import com.mcfaas.common.model.ExecutionMode;
import com.mcfaas.common.model.FunctionSpec;
import com.mcfaas.common.model.InvocationRequest;
import com.mcfaas.controlplane.registry.FunctionService;
import com.mcfaas.controlplane.service.RateLimiter;
import com.mcfaas.controlplane.scheduler.Scheduler;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mcfaas.rate.maxPerSecond=1000",
                "mcfaas.defaults.timeoutMs=2000",
                "mcfaas.defaults.concurrency=2",
                "mcfaas.defaults.queueSize=10",
                "mcfaas.defaults.maxRetries=3"
        })
@AutoConfigureWebTestClient
class ControlPlaneApiTest {
    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private FunctionService functionService;

    @Autowired
    private RateLimiter rateLimiter;

    @Autowired
    private Scheduler scheduler;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private HealthEndpoint healthEndpoint;

    @BeforeEach
    void setup() {
        functionService.register(new FunctionSpec(
                "echo",
                "local",
                null,
                Map.of(),
                null,
                1000,
                1,
                10,
                3,
                null,
                ExecutionMode.LOCAL,
                null,
                null
        ));
    }

    @Test
    void issue006_rateLimitAndRouting() {
        rateLimiter.setMaxPerSecond(0);

        webTestClient.post()
                .uri("/v1/functions/echo:invoke")
                .bodyValue(new InvocationRequest("one", Map.of()))
                .exchange()
                .expectStatus().isEqualTo(429);

        rateLimiter.setMaxPerSecond(1000);

        webTestClient.post()
                .uri("/v1/functions/echo:invoke")
                .bodyValue(new InvocationRequest("two", Map.of()))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void issue007_idempotencyReturnsSameExecutionId() {
        rateLimiter.setMaxPerSecond(1000);

        Map<String, Object> body1 = webTestClient.post()
                .uri("/v1/functions/echo:enqueue")
                .header("Idempotency-Key", "abc")
                .bodyValue(new InvocationRequest("payload", Map.of()))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();

        Map<String, Object> body2 = webTestClient.post()
                .uri("/v1/functions/echo:enqueue")
                .header("Idempotency-Key", "abc")
                .bodyValue(new InvocationRequest("payload", Map.of()))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();

        assertNotNull(body1);
        assertNotNull(body2);
        assertEquals(body1.get("executionId"), body2.get("executionId"));
    }

    @Test
    void issue009_schedulerCompletesLocalInvocation() throws InterruptedException {
        rateLimiter.setMaxPerSecond(1000);

        Map<String, Object> body = webTestClient.post()
                .uri("/v1/functions/echo:enqueue")
                .bodyValue(new InvocationRequest("payload", Map.of()))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();

        assertNotNull(body);
        String executionId = body.get("executionId").toString();

        Thread.sleep(100);

        webTestClient.get()
                .uri("/v1/executions/{id}", executionId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("success");
    }

    @Test
    void issue013_syncWaitReturnsOutput() {
        rateLimiter.setMaxPerSecond(1000);

        webTestClient.post()
                .uri("/v1/functions/echo:invoke")
                .bodyValue(new InvocationRequest("payload", Map.of()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("success")
                .jsonPath("$.output").isEqualTo("payload");
    }

    @Test
    void issue017_prometheusMetricsExposed() {
        webTestClient.post()
                .uri("/v1/functions/echo:enqueue")
                .bodyValue(new InvocationRequest("payload", Map.of()))
                .exchange()
                .expectStatus().isAccepted();

        assertNotNull(meterRegistry.find("function_enqueue_total").counter());
    }

    @Test
    void issue018_healthAndSchedulerRunning() {
        assertEquals("UP", healthEndpoint.health().getStatus().getCode());
        assertTrue(scheduler.isRunning());
    }
}
