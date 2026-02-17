package it.unimib.datai.nanofaas.controlplane.api;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.ScalingConfig;
import it.unimib.datai.nanofaas.common.model.ScalingStrategy;
import it.unimib.datai.nanofaas.common.model.ScalingMetric;
import it.unimib.datai.nanofaas.common.model.ConcurrencyControlConfig;
import it.unimib.datai.nanofaas.common.model.ConcurrencyControlMode;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@WebFluxTest(controllers = FunctionController.class)
@Import(GlobalExceptionHandler.class)
class FunctionControllerTest {

    @Autowired
    private WebTestClient webClient;

    @MockitoBean
    private FunctionService functionService;

    @Test
    void list_returnsRegisteredFunctions() {
        when(functionService.list()).thenReturn(List.of(spec("echo"), spec("sum")));

        webClient.get()
                .uri("/v1/functions")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].name").isEqualTo("echo")
                .jsonPath("$[1].name").isEqualTo("sum");
    }

    @Test
    void register_conflict_returns409() {
        FunctionSpec request = spec("echo");
        when(functionService.register(any())).thenReturn(Optional.empty());

        webClient.post()
                .uri("/v1/functions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    @Test
    void get_missingFunction_returns404() {
        when(functionService.get("missing")).thenReturn(Optional.empty());

        webClient.get()
                .uri("/v1/functions/missing")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void delete_missingFunction_returns404() {
        when(functionService.remove("missing")).thenReturn(Optional.empty());

        webClient.delete()
                .uri("/v1/functions/missing")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void delete_existingFunction_returns204() {
        when(functionService.remove("echo")).thenReturn(Optional.of(spec("echo")));

        webClient.delete()
                .uri("/v1/functions/echo")
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void setReplicas_illegalState_returns503WithMessage() {
        when(functionService.setReplicas("echo", 3))
                .thenThrow(new IllegalStateException("Scaler unavailable"));

        webClient.put()
                .uri("/v1/functions/echo/replicas")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ReplicaRequest(3))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody(String.class)
                .isEqualTo("Scaler unavailable");
    }

    @Test
    void register_withConcurrencyControl_returnsResolvedSpec() {
        FunctionSpec resolved = new FunctionSpec(
                "echo",
                "ghcr.io/example/echo:v2",
                null,
                Map.of(),
                null,
                1000,
                12,
                10,
                3,
                "http://fn-echo.default.svc:8080/invoke",
                ExecutionMode.DEPLOYMENT,
                null,
                null,
                new ScalingConfig(
                        ScalingStrategy.INTERNAL,
                        1,
                        4,
                        List.of(new ScalingMetric("queue_depth", "5", null)),
                        new ConcurrencyControlConfig(
                                ConcurrencyControlMode.ADAPTIVE_PER_POD,
                                3,
                                1,
                                6,
                                20000L,
                                45000L,
                                0.85,
                                0.35
                        )
                )
        );
        when(functionService.register(any())).thenReturn(Optional.of(resolved));

        String payload = """
                {
                  "name": "echo",
                  "image": "ghcr.io/example/echo:v2",
                  "executionMode": "DEPLOYMENT",
                  "concurrency": 12,
                  "queueSize": 10,
                  "maxRetries": 3,
                  "scalingConfig": {
                    "strategy": "INTERNAL",
                    "minReplicas": 1,
                    "maxReplicas": 4,
                    "metrics": [
                      { "type": "queue_depth", "target": "5" }
                    ],
                    "concurrencyControl": {
                      "mode": "ADAPTIVE_PER_POD",
                      "targetInFlightPerPod": 3,
                      "minTargetInFlightPerPod": 1,
                      "maxTargetInFlightPerPod": 6,
                      "upscaleCooldownMs": 20000,
                      "downscaleCooldownMs": 45000,
                      "highLoadThreshold": 0.85,
                      "lowLoadThreshold": 0.35
                    }
                  }
                }
                """;

        webClient.post()
                .uri("/v1/functions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.name").isEqualTo("echo")
                .jsonPath("$.scalingConfig.concurrencyControl.mode").isEqualTo("ADAPTIVE_PER_POD")
                .jsonPath("$.scalingConfig.concurrencyControl.targetInFlightPerPod").isEqualTo(3)
                .jsonPath("$.scalingConfig.concurrencyControl.minTargetInFlightPerPod").isEqualTo(1)
                .jsonPath("$.scalingConfig.concurrencyControl.maxTargetInFlightPerPod").isEqualTo(6);
    }

    private FunctionSpec spec(String name) {
        return new FunctionSpec(
                name,
                "ghcr.io/example/" + name + ":v1",
                null,
                Map.of(),
                null,
                1000,
                1,
                10,
                3,
                null,
                ExecutionMode.DEPLOYMENT,
                null,
                null,
                null
        );
    }
}
