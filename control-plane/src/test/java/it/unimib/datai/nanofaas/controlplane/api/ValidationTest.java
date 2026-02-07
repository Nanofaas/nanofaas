package it.unimib.datai.nanofaas.controlplane.api;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionService;
import it.unimib.datai.nanofaas.controlplane.service.InvocationService;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@WebFluxTest(controllers = {FunctionController.class, InvocationController.class})
@Import(GlobalExceptionHandler.class)
class ValidationTest {

    @Autowired
    private WebTestClient webClient;

    @MockBean
    private FunctionService functionService;

    @MockBean
    private InvocationService invocationService;

    @Test
    void register_withBlankName_returns400() {
        FunctionSpec spec = new FunctionSpec(
                "",  // blank name
                "myimage",
                null, null, null, null, null, null, null, null,
                ExecutionMode.REMOTE, null, null
        );

        webClient.post()
                .uri("/v1/functions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(spec)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.details").isArray();
    }

    @Test
    void register_withNullImage_returns400() {
        FunctionSpec spec = new FunctionSpec(
                "myfunc",
                null,  // null image
                null, null, null, null, null, null, null, null,
                ExecutionMode.REMOTE, null, null
        );

        webClient.post()
                .uri("/v1/functions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(spec)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void register_withZeroConcurrency_returns400() {
        FunctionSpec spec = new FunctionSpec(
                "myfunc",
                "myimage",
                null, null, null, null,
                0,  // zero concurrency
                null, null, null,
                ExecutionMode.REMOTE, null, null
        );

        webClient.post()
                .uri("/v1/functions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(spec)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.details[0]").value(s -> ((String) s).contains("concurrency"));
    }

    @Test
    void register_withValidSpec_returns201() {
        FunctionSpec spec = new FunctionSpec(
                "myfunc",
                "myimage",
                null, null, null, null, null, null, null, null,
                ExecutionMode.REMOTE, null, null
        );

        when(functionService.register(any())).thenReturn(Optional.of(spec));

        webClient.post()
                .uri("/v1/functions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(spec)
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    void invoke_withNullInput_returns400() {
        InvocationRequest request = new InvocationRequest(null, null);

        webClient.post()
                .uri("/v1/functions/myfunc:invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.details[0]").value(s -> ((String) s).contains("input"));
    }

    @Test
    void invoke_withValidRequest_callsService() {
        InvocationRequest request = new InvocationRequest("payload", null);

        when(invocationService.invokeSyncReactive(any(), any(), any(), any(), any()))
                .thenThrow(new FunctionNotFoundException("myfunc"));

        webClient.post()
                .uri("/v1/functions/myfunc:invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isNotFound();  // Function not found, but validation passed
    }

    @Test
    void validationError_hasCorrectFormat() {
        FunctionSpec spec = new FunctionSpec(
                "",    // blank name
                "",    // blank image
                null, null, null, null,
                -1,   // negative concurrency
                null, null, null,
                ExecutionMode.REMOTE, null, null
        );

        webClient.post()
                .uri("/v1/functions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(spec)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.message").isEqualTo("Request validation failed")
                .jsonPath("$.details").isArray()
                .jsonPath("$.details").isNotEmpty();
    }
}
