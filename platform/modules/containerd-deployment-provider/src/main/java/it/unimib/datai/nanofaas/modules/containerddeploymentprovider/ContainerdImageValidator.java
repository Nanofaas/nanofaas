package it.unimib.datai.nanofaas.modules.containerddeploymentprovider;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.containerdeployment.ContainerRuntimeAdapter;
import it.unimib.datai.nanofaas.controlplane.registry.ImageValidationException;
import it.unimib.datai.nanofaas.controlplane.registry.ImageValidator;

public final class ContainerdImageValidator implements ImageValidator {
    private final ContainerRuntimeAdapter adapter;

    public ContainerdImageValidator(ContainerRuntimeAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public void validate(FunctionSpec spec) {
        if (spec.executionMode() != ExecutionMode.DEPLOYMENT) return;
        try {
            adapter.pullImage(spec.image());
        } catch (RuntimeException error) {
            throw ImageValidationException.registryUnavailable(spec.image(), error.getMessage());
        }
    }
}
