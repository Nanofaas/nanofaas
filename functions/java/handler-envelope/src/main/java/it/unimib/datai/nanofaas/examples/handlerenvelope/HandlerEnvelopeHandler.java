package it.unimib.datai.nanofaas.examples.handlerenvelope;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import it.unimib.datai.nanofaas.sdk.NanofaasFunction;
import java.util.Map;

@NanofaasFunction
public class HandlerEnvelopeHandler implements FunctionHandler {
    @Override
    public Object handle(InvocationRequest request) {
        if (!(request.input() instanceof Map<?, ?> input)) {
            return Map.of("body", null, "header", null);
        }
        return Map.of(
                "body", input.get("message"),
                "header", request.headers() == null ? null : request.headers().get("x-e2e-token")
        );
    }
}
