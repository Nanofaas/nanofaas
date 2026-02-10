package it.unimib.datai.nanofaas.cli.http;

public final class ControlPlaneHttpException extends RuntimeException {
    private final int status;
    private final String body;

    public ControlPlaneHttpException(int status, String message, String body) {
        super(message);
        this.status = status;
        this.body = body;
    }

    public int status() {
        return status;
    }

    public String body() {
        return body;
    }
}
