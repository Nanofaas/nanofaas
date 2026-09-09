package it.unimib.datai.nanofaas.controlplane.input;

import org.springframework.web.server.ServerWebInputException;

/** A bounded canonical representation cannot be created for this invocation input. */
public final class InvocationInputRejectedException extends ServerWebInputException {
    private final RetainedInputEstimator.Rejection rejection;

    public InvocationInputRejectedException(RetainedInputEstimator.Rejection rejection) {
        super("Invocation input rejected: " + rejection.name().toLowerCase(java.util.Locale.ROOT));
        this.rejection = rejection;
    }

    public RetainedInputEstimator.Rejection rejection() {
        return rejection;
    }
}
