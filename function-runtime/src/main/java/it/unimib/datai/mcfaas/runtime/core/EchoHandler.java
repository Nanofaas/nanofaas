package com.mcfaas.runtime.core;

import com.mcfaas.common.model.InvocationRequest;
import com.mcfaas.common.runtime.FunctionHandler;
import org.springframework.stereotype.Component;

@Component
public class EchoHandler implements FunctionHandler {
    @Override
    public Object handle(InvocationRequest request) {
        return request.input();
    }
}
