package it.unimib.datai.nanofaas.modules.containerddeploymentprovider;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.containerdeployment.ContainerRuntimeAdapter;
import it.unimib.datai.nanofaas.controlplane.registry.ImageValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ContainerdImageValidatorTest {
    @Test
    void deploymentPullsImageAndReportsRegistryFailure() {
        var adapter = mock(ContainerRuntimeAdapter.class);
        var validator = new ContainerdImageValidator(adapter);
        validator.validate(spec(ExecutionMode.DEPLOYMENT));
        verify(adapter).pullImage("example/echo:1");
        doThrow(new IllegalStateException("registry unavailable")).when(adapter).pullImage("example/echo:1");
        var deploymentSpec = spec(ExecutionMode.DEPLOYMENT);
        assertThatThrownBy(() -> validator.validate(deploymentSpec))
                .isInstanceOf(ImageValidationException.class)
                .extracting(error -> ((ImageValidationException) error).errorCode())
                .isEqualTo("IMAGE_REGISTRY_UNAVAILABLE");
    }

    @Test
    void localFunctionSkipsPull() {
        var adapter = mock(ContainerRuntimeAdapter.class);
        new ContainerdImageValidator(adapter).validate(spec(ExecutionMode.LOCAL));
        verifyNoInteractions(adapter);
    }

    private static FunctionSpec spec(ExecutionMode mode) {
        return new FunctionSpec("echo", "example/echo:1", null, null, null, null,
                null, null, null, null, mode, null, null, null);
    }
}
