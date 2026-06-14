package it.unimib.datai.nanofaas.controlplane.registry;

import org.springframework.http.HttpStatus;

public final class ImageValidationException extends RuntimeException {
    private final String errorCode;
    private final HttpStatus status;

    private ImageValidationException(String errorCode, HttpStatus status, String message) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
    }

    public static ImageValidationException notFound(String image) {
        return new ImageValidationException(
                "IMAGE_NOT_FOUND",
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Image not found in registry: " + image
        );
    }

    public static ImageValidationException authRequired(String image) {
        return new ImageValidationException(
                "IMAGE_PULL_AUTH_REQUIRED",
                HttpStatus.FAILED_DEPENDENCY,
                "Image pull authentication failed for: " + image
        );
    }

    public static ImageValidationException registryUnavailable(String image, String details) {
        String suffix = (details == null || details.isBlank()) ? "" : " (" + details + ")";
        return new ImageValidationException(
                "IMAGE_REGISTRY_UNAVAILABLE",
                HttpStatus.SERVICE_UNAVAILABLE,
                "Unable to validate image in registry: " + image + suffix
        );
    }

    public String errorCode() {
        return errorCode;
    }

    public HttpStatus status() {
        return status;
    }
}
