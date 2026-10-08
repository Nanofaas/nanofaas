package it.unimib.datai.nanofaas.controlplane.registry;

/**
 * Image validation refused a registration, carrying the HTTP status the API must answer with.
 *
 * <p>The status is the raw status code rather than a Spring {@code HttpStatus}: this contract
 * lives in the SPI, which admits no Web dependency, and the only reader is the core exception
 * handler, which passes an {@code int} to {@code ResponseEntity.status} unchanged.</p>
 */
public final class ImageValidationException extends RuntimeException {
    private final String errorCode;
    private final int status;

    private ImageValidationException(String errorCode, int status, String message) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
    }

    public static ImageValidationException notFound(String image) {
        return new ImageValidationException(
                "IMAGE_NOT_FOUND",
                422,
                "Image not found in registry: " + image
        );
    }

    public static ImageValidationException authRequired(String image) {
        return new ImageValidationException(
                "IMAGE_PULL_AUTH_REQUIRED",
                424,
                "Image pull authentication failed for: " + image
        );
    }

    public static ImageValidationException registryUnavailable(String image, String details) {
        String suffix = (details == null || details.isBlank()) ? "" : " (" + details + ")";
        return new ImageValidationException(
                "IMAGE_REGISTRY_UNAVAILABLE",
                503,
                "Unable to validate image in registry: " + image + suffix
        );
    }

    public String errorCode() {
        return errorCode;
    }

    /** The HTTP status code the API answers with; 422, 424 or 503. */
    public int status() {
        return status;
    }
}
