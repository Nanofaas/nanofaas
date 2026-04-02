package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;

public record RegisteredFunction(
        FunctionSpec spec,
        DeploymentMetadata deploymentMetadata
) {
    public RegisteredFunction {
        if (spec == null) {
            throw new IllegalArgumentException("spec is required");
        }
        deploymentMetadata = deploymentMetadata == null
                ? DeploymentMetadata.nonManaged(spec.executionMode())
                : deploymentMetadata;
    }

    public static RegisteredFunction nonManaged(FunctionSpec spec) {
        return new RegisteredFunction(spec, DeploymentMetadata.nonManaged(spec.executionMode()));
    }

    public String name() {
        return spec.name();
    }
}
