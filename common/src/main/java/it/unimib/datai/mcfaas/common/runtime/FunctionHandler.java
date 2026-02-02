package com.mcfaas.common.runtime;

import com.mcfaas.common.model.InvocationRequest;

public interface FunctionHandler {
    Object handle(InvocationRequest request);
}
