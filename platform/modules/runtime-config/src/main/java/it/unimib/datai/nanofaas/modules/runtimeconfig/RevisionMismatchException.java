package it.unimib.datai.nanofaas.modules.runtimeconfig;

public class RevisionMismatchException extends RuntimeException {

    private final long actual;

    public RevisionMismatchException(long expected, long actual) {
        super("Revision mismatch: expected %d but current is %d".formatted(expected, actual));
        this.actual = actual;
    }

    public long getActual() {
        return actual;
    }
}
