package it.unimib.datai.nanofaas.cli.http;

public class ControlPlaneHttpException extends RuntimeException {
    private final int status;
    private final String body;

    // Used for transport errors (connection refused, timeout, DNS errors, etc.).
    public ControlPlaneHttpException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.body = "";
    }

    // Used for HTTP responses with an error status code.
    public ControlPlaneHttpException(int status, String message, String body) {
        super(message + (body == null || body.isBlank() ? "" : ": " + body));
        this.status = status;
        this.body = body;
    }

    public int getStatus() {
        return status;
    }

    public String getBody() {
        return body;
    }
}
