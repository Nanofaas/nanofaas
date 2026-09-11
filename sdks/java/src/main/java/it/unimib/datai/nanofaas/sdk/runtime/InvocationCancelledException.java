package it.unimib.datai.nanofaas.sdk.runtime;

final class InvocationCancelledException extends RuntimeException {
    InvocationCancelledException(InterruptedException cause) {
        super("Invocation cancelled", cause);
    }
}
