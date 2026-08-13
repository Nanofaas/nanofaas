package it.unimib.datai.nanofaas.examples.binaryenvelope;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import it.unimib.datai.nanofaas.common.runtime.HandlerResponse;
import it.unimib.datai.nanofaas.sdk.NanofaasFunction;
import java.util.Map;

@NanofaasFunction
public class BinaryEnvelopeHandler implements FunctionHandler {
    @Override
    public Object handle(InvocationRequest request) {
        return new HandlerResponse("AAEC", 200, Map.of("Content-Type", "application/octet-stream"), "base64");
    }
}
