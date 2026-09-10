package it.unimib.datai.nanofaas.controlplane.api;

import it.unimib.datai.nanofaas.controlplane.registry.FunctionApplicationPendingException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class FunctionApplicationPendingHttpMappingTest {

    @Test
    void unavailableFunctionIsReportedAsConflictWithAnExplicitErrorCode() {
        var response = new GlobalExceptionHandler().handleFunctionApplicationPending(
                new FunctionApplicationPendingException("fn", "reconcile failed"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry(
                "error", FunctionApplicationPendingException.ERROR_CODE);
        assertThat(response.getBody().get("message").toString())
                .contains("fn", "reconcile failed");
    }
}
