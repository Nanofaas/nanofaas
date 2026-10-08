package it.unimib.datai.nanofaas.controlplane.scheduler;

import java.util.Objects;

/**
 * Identity of one attempt of one execution inside a scheduling index: the execution id
 * plus the attempt number. Two attempts of the same execution are distinct tickets.
 *
 * @param executionId the execution this attempt belongs to; never blank
 * @param attempt     1-based attempt number; positive
 */
public record TicketId(String executionId, int attempt) {

    public TicketId {
        Objects.requireNonNull(executionId, "executionId must not be null");
        if (executionId.isBlank()) {
            throw new IllegalArgumentException("executionId must not be blank");
        }
        if (attempt <= 0) {
            throw new IllegalArgumentException("attempt must be positive, was " + attempt);
        }
    }
}
