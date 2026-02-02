package it.unimib.datai.mcfaas.common.runtime;

import it.unimib.datai.mcfaas.common.model.InvocationRequest;

public interface FunctionHandler {
    Object handle(InvocationRequest request);
}
