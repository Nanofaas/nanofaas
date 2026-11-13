package it.unimib.datai.nanofaas.controlplane.api;

import it.unimib.datai.nanofaas.common.model.RawJson;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.Map;

/**
 * The invocation body as the control plane reads it. Same wire shape as
 * {@link it.unimib.datai.nanofaas.common.model.InvocationRequest}, but the payload is kept as JSON
 * text: the control plane forwards it and never looks inside.
 */
public record IngressInvocationRequest(
        @JsonDeserialize(using = RawJsonDeserializer.class)
        RawJson input,
        Map<String, String> metadata,
        Map<String, String> headers
) {
}
