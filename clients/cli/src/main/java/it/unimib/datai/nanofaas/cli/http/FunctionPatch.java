package it.unimib.datai.nanofaas.cli.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import it.unimib.datai.nanofaas.common.model.ConcurrencyControlConfig;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FunctionPatch(
        Integer concurrency,
        Integer timeoutMs,
        Integer maxRetries,
        ConcurrencyControlConfig concurrencyControl
) {
    public boolean isEmpty() {
        return concurrency == null && timeoutMs == null && maxRetries == null
                && concurrencyControl == null;
    }
}
