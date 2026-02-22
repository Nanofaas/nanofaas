package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;

@FunctionalInterface
public interface ImageValidator {
    void validate(FunctionSpec spec);

    static ImageValidator noOp() {
        return spec -> {};
    }
}
