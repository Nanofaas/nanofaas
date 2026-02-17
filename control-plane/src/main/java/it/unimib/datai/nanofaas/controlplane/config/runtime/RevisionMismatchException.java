package it.unimib.datai.nanofaas.controlplane.config.runtime;

public class RevisionMismatchException extends RuntimeException {

    private final long expected;
    private final long actual;

    public RevisionMismatchException(long expected, long actual) {
        super("Revision mismatch: expected %d but current is %d".formatted(expected, actual));
        this.expected = expected;
        this.actual = actual;
    }

    public long getExpected() {
        return expected;
    }

    public long getActual() {
        return actual;
    }
}
