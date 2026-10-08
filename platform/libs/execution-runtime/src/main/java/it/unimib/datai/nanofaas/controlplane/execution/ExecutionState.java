package it.unimib.datai.nanofaas.controlplane.execution;

public enum ExecutionState {
    QUEUED,
    RUNNING,
    SUCCESS,
    ERROR,
    TIMEOUT
}
