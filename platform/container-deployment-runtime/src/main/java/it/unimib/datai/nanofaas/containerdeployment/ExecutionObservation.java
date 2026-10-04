package it.unimib.datai.nanofaas.containerdeployment;
/** UNKNOWN, including transport and decode failures, never authorizes reuse. */
public record ExecutionObservation(String state, String incarnation, String executionId, String dispatchAttempt) {
    public static ExecutionObservation unknown() { return new ExecutionObservation("UNKNOWN", null, null, null); }
}
