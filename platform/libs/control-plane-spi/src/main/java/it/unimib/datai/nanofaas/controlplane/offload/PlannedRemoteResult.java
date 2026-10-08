package it.unimib.datai.nanofaas.controlplane.offload;
import it.unimib.datai.nanofaas.common.model.InvocationResult;
/** Execution attribution is trusted transport metadata, never a function-supplied envelope header. */
public record PlannedRemoteResult(InvocationResult result,String executionNode) {}
