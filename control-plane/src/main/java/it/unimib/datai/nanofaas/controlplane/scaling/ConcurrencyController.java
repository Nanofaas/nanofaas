package it.unimib.datai.nanofaas.controlplane.scaling;

import it.unimib.datai.nanofaas.common.model.FunctionSpec;

public interface ConcurrencyController {
    int computeEffectiveConcurrency(FunctionSpec spec, int readyReplicas);
}
