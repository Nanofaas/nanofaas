package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.registry.ImageValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class DockerImageValidatorTest {

    @Test
    void validate_deployment_pullsTheImageBeforeRegistration() {
        ContainerRuntimeAdapter adapter = mock(ContainerRuntimeAdapter.class);

        new DockerImageValidator(adapter).validate(spec(ExecutionMode.DEPLOYMENT));

        verify(adapter).pullImage("ghcr.io/example/function:v1");
    }

    @Test
    void validate_nonDeployment_skipsThePull() {
        ContainerRuntimeAdapter adapter = mock(ContainerRuntimeAdapter.class);

        new DockerImageValidator(adapter).validate(spec(ExecutionMode.LOCAL));

        verifyNoInteractions(adapter);
    }

    @Test
    void validate_pullFailure_isReportedAsRegistryUnavailable() {
        ContainerRuntimeAdapter adapter = mock(ContainerRuntimeAdapter.class);
        doThrow(new IllegalStateException("denied")).when(adapter).pullImage("ghcr.io/example/function:v1");

        assertThatThrownBy(() -> new DockerImageValidator(adapter).validate(spec(ExecutionMode.DEPLOYMENT)))
                .isInstanceOf(ImageValidationException.class)
                .extracting(error -> ((ImageValidationException) error).errorCode())
                .isEqualTo("IMAGE_REGISTRY_UNAVAILABLE");
    }

    private static FunctionSpec spec(ExecutionMode executionMode) {
        return new FunctionSpec("function", "ghcr.io/example/function:v1", null, null, null, null,
                null, null, null, null, executionMode, null, null, null);
    }
}
