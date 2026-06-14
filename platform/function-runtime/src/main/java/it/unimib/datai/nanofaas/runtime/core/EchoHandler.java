package it.unimib.datai.nanofaas.runtime.core;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import org.springframework.stereotype.Component;

@Component
public class EchoHandler implements FunctionHandler {
    @Override
    public Object handle(InvocationRequest request) {
        return request.input();
    }
}
