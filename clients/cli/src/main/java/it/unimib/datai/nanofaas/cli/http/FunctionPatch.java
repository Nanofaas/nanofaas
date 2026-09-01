package it.unimib.datai.nanofaas.cli.http;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import it.unimib.datai.nanofaas.common.model.ConcurrencyControlConfig;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FunctionPatch(
        Integer concurrency,
        Integer timeoutMs,
        Integer maxRetries,
        ConcurrencyControlConfig concurrencyControl
) {
    /**
     * Whether this patch changes nothing.
     *
     * <p>{@code @JsonIgnore} is load-bearing: Jackson treats {@code isEmpty()} as a
     * bean property and used to serialize {@code "empty": false} into the request
     * body, which the control plane rejects as an unknown field — every
     * {@code fn update} failed with HTTP 400 "Failed to read HTTP message".</p>
     */
    @JsonIgnore
    public boolean isEmpty() {
        return concurrency == null && timeoutMs == null && maxRetries == null
                && concurrencyControl == null;
    }
}
