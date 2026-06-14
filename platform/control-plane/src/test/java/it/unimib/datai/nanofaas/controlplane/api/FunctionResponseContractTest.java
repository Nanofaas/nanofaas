package it.unimib.datai.nanofaas.controlplane.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.registry.DeploymentMetadata;
import it.unimib.datai.nanofaas.controlplane.registry.RegisteredFunction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FunctionResponseContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serialization_includesManagedDeploymentMetadata() throws Exception {
        FunctionSpec spec = new FunctionSpec(
                "fn",
                "img:latest",
                null,
                null,
                null,
                30000,
                4,
                100,
                3,
                "http://svc:8080/invoke",
                ExecutionMode.DEPLOYMENT,
                null,
                null,
                null
        );

        FunctionResponse response = FunctionResponse.from(
                spec,
                ExecutionMode.DEPLOYMENT,
                ExecutionMode.DEPLOYMENT,
                "k8s",
                null,
                "http://svc:8080/invoke"
        );

        String json = objectMapper.writeValueAsString(response);

        assertThat(json).contains("\"requestedExecutionMode\":\"DEPLOYMENT\"");
        assertThat(json).contains("\"effectiveExecutionMode\":\"DEPLOYMENT\"");
        assertThat(json).contains("\"deploymentBackend\":\"k8s\"");
        assertThat(json).contains("\"endpointUrl\":\"http://svc:8080/invoke\"");
        assertThat(json).doesNotContain("\"degradationReason\":");
    }

    @Test
    void serialization_includesDegradationReasonWhenPresent() throws Exception {
        FunctionSpec spec = new FunctionSpec(
                "fn",
                "img:latest",
                null,
                null,
                null,
                30000,
                4,
                100,
                3,
                "http://external:8080/invoke",
                ExecutionMode.POOL,
                null,
                null,
                null
        );

        FunctionResponse response = FunctionResponse.from(
                spec,
                ExecutionMode.DEPLOYMENT,
                ExecutionMode.POOL,
                null,
                "No provider available",
                "http://external:8080/invoke"
        );

        String json = objectMapper.writeValueAsString(response);

        assertThat(json).contains("\"requestedExecutionMode\":\"DEPLOYMENT\"");
        assertThat(json).contains("\"effectiveExecutionMode\":\"POOL\"");
        assertThat(json).contains("\"degradationReason\":\"No provider available\"");
    }

    @Test
    void serialization_omitsBackendFieldsForLocalMode() throws Exception {
        FunctionSpec spec = new FunctionSpec(
                "fn",
                "img:latest",
                null,
                null,
                null,
                30000,
                4,
                100,
                3,
                null,
                ExecutionMode.LOCAL,
                null,
                null,
                null
        );

        FunctionResponse response = FunctionResponse.fromNonManaged(spec);

        String json = objectMapper.writeValueAsString(response);

        assertThat(json).contains("\"requestedExecutionMode\":\"LOCAL\"");
        assertThat(json).contains("\"effectiveExecutionMode\":\"LOCAL\"");
        assertThat(json).doesNotContain("\"deploymentBackend\":");
        assertThat(json).doesNotContain("\"degradationReason\":");
    }

    @Test
    void serialization_prefersEffectiveEndpointFromDeploymentMetadata() throws Exception {
        FunctionSpec spec = new FunctionSpec(
                "fn",
                "img:latest",
                null,
                null,
                null,
                30000,
                4,
                100,
                3,
                "http://user-provided:8080/invoke",
                ExecutionMode.DEPLOYMENT,
                null,
                null,
                null
        );

        FunctionResponse response = FunctionResponse.from(new RegisteredFunction(
                spec,
                new DeploymentMetadata(
                        ExecutionMode.DEPLOYMENT,
                        ExecutionMode.DEPLOYMENT,
                        "k8s",
                        null,
                        "http://managed:8080/invoke"
                )
        ));

        String json = objectMapper.writeValueAsString(response);

        assertThat(json).contains("\"endpointUrl\":\"http://managed:8080/invoke\"");
        assertThat(json).doesNotContain("\"endpointUrl\":\"http://user-provided:8080/invoke\"");
    }

    @Test
    void listSerialization_usesConsistentShape() throws Exception {
        FunctionSpec spec = new FunctionSpec(
                "fn",
                "img:latest",
                null,
                null,
                null,
                30000,
                4,
                100,
                3,
                "http://svc:8080/invoke",
                ExecutionMode.DEPLOYMENT,
                null,
                null,
                null
        );

        String json = objectMapper.writeValueAsString(List.of(FunctionResponse.from(new RegisteredFunction(
                spec,
                new DeploymentMetadata(
                        ExecutionMode.DEPLOYMENT,
                        ExecutionMode.DEPLOYMENT,
                        "k8s",
                        null,
                        "http://svc:8080/invoke"
                )
        ))));

        assertThat(json).startsWith("[{");
        assertThat(json).contains("\"requestedExecutionMode\":\"DEPLOYMENT\"");
        assertThat(json).contains("\"effectiveExecutionMode\":\"DEPLOYMENT\"");
        assertThat(json).contains("\"deploymentBackend\":\"k8s\"");
        assertThat(json).contains("\"endpointUrl\":\"http://svc:8080/invoke\"");
    }
}
