package it.unimib.datai.nanofaas.containerdeployment;
import java.net.URI;
/** Implementations must bound both response bytes and wall time. */
public interface RuntimeExecutionProbe {
    ExecutionObservation observe(URI backend, String executionId);
    String eligibleIncarnation(URI backend);
}
