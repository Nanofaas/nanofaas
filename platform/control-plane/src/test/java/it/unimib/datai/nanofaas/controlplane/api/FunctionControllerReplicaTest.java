package it.unimib.datai.nanofaas.controlplane.api;

import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatus;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionService;
import it.unimib.datai.nanofaas.controlplane.service.InvocationService;
import it.unimib.datai.nanofaas.controlplane.service.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Optional;

import static org.mockito.Mockito.*;

@WebFluxTest(controllers = {FunctionController.class, InvocationController.class})
@Import({GlobalExceptionHandler.class, RateLimiter.class})
class FunctionControllerReplicaTest {

    @Autowired
    private WebTestClient webClient;

    @MockitoBean
    private FunctionService functionService;

    @MockitoBean
    private InvocationService invocationService;

    @Test
    void getReplicas_returnsDesiredAndReadyCounts() {
        when(functionService.getReplicaStatus("echo"))
                .thenReturn(Optional.of(new ReplicaStatus(5, 3)));

        webClient.get()
                .uri("/v1/functions/echo/replicas")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isEqualTo("echo")
                .jsonPath("$.desiredReplicas").isEqualTo(5)
                .jsonPath("$.readyReplicas").isEqualTo(3);

        verify(functionService).getReplicaStatus("echo");
    }

    @Test
    void getReplicas_returns404WhenFunctionNotFound() {
        when(functionService.getReplicaStatus("nonexistent")).thenReturn(Optional.empty());

        webClient.get()
                .uri("/v1/functions/nonexistent/replicas")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void getReplicas_returns400WhenNotDeploymentMode() {
        when(functionService.getReplicaStatus("echo"))
                .thenThrow(new IllegalArgumentException("Function 'echo' is not in DEPLOYMENT mode"));

        webClient.get()
                .uri("/v1/functions/echo/replicas")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void setReplicas_returns200WithCorrectBody() {
        when(functionService.setReplicas("echo", 5)).thenReturn(Optional.of(5));

        webClient.put()
                .uri("/v1/functions/echo/replicas")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ReplicaRequest(5))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.function").isEqualTo("echo")
                .jsonPath("$.replicas").isEqualTo(5);

        verify(functionService).setReplicas("echo", 5);
    }

    @Test
    void setReplicas_returns404WhenFunctionNotFound() {
        when(functionService.setReplicas("nonexistent", 3)).thenReturn(Optional.empty());

        webClient.put()
                .uri("/v1/functions/nonexistent/replicas")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ReplicaRequest(3))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void setReplicas_returns400WhenNotDeploymentMode() {
        when(functionService.setReplicas("echo", 3))
                .thenThrow(new IllegalArgumentException("Function 'echo' is not in DEPLOYMENT mode"));

        webClient.put()
                .uri("/v1/functions/echo/replicas")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ReplicaRequest(3))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void setReplicas_allowsZeroReplicas() {
        when(functionService.setReplicas("echo", 0)).thenReturn(Optional.of(0));

        webClient.put()
                .uri("/v1/functions/echo/replicas")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ReplicaRequest(0))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.replicas").isEqualTo(0);
    }
}
