package it.unimib.datai.mcfaas.controlplane.execution;

public enum ExecutionState {
    QUEUED,
    RUNNING,
    SUCCESS,
    ERROR,
    TIMEOUT
}
