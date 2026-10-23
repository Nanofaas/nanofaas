package it.unimib.datai.nanofaas.modules.offload.oneshot.actuation;

/** Destination and incoming epoch while local dispatch waits for the new allocation. */
public record AllocationTransition(String nodeId, long epoch) {}
