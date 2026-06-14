package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;

public interface FunctionRegistrationListener {
    void onRegister(FunctionSpec spec);

    void onRemove(String functionName);
}
