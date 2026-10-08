package it.unimib.datai.nanofaas.controlplane.input;

import java.util.Locale;

/**
 * A bounded canonical representation cannot be created for this invocation input.
 *
 * <p>A domain exception: this mandatory runtime library must not depend on Spring Web, so this
 * no longer extends {@code ServerWebInputException} as it used to. The control plane maps it to
 * the same HTTP 400 response it always returned, via a dedicated
 * {@code @ExceptionHandler(InvocationInputRejectedException.class)} in {@code GlobalExceptionHandler}.
 */
public final class InvocationInputRejectedException extends RuntimeException {
    public InvocationInputRejectedException(RetainedInputEstimator.Rejection rejection) {
        super("Invocation input rejected: " + rejection.name().toLowerCase(Locale.ROOT));
    }
}
