package it.unimib.datai.nanofaas.common.runtime;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;

public interface FunctionHandler {
    Object handle(InvocationRequest request);
}
