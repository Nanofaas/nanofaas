package it.unimib.datai.mcfaas.runtime.core;

import it.unimib.datai.mcfaas.common.model.InvocationRequest;
import it.unimib.datai.mcfaas.common.runtime.FunctionHandler;
import org.springframework.stereotype.Component;

@Component
public class EchoHandler implements FunctionHandler {
    @Override
    public Object handle(InvocationRequest request) {
        return request.input();
    }
}
