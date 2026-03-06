package it.unimib.datai.nanofaas.controlplane.api;

import it.unimib.datai.nanofaas.controlplane.registry.ImageValidationException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;

import java.util.List;
import java.util.Map;

/**
 * Global exception handler for consistent error responses across all controllers.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handles validation errors from @Valid annotations on request bodies.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationErrors(
            MethodArgumentNotValidException ex) {
        List<String> errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList();

        log.debug("Validation failed: {}", errors);
        return ResponseEntity.badRequest().body(validationErrorBody(errors));
    }

    /**
     * Handles validation errors from WebFlux binding (reactive stack).
     */
    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<Map<String, Object>> handleWebExchangeBindException(
            WebExchangeBindException ex) {
        List<String> errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList();

        log.debug("Validation failed: {}", errors);
        return ResponseEntity.badRequest().body(validationErrorBody(errors));
    }

    /**
     * Handles constraint violations from @Validated annotations on path/query params.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(
            ConstraintViolationException ex) {
        List<String> errors = ex.getConstraintViolations()
                .stream()
                .map(v -> {
                    String path = v.getPropertyPath().toString();
                    // Extract just the parameter name from paths like "invokeSync.name"
                    int lastDot = path.lastIndexOf('.');
                    String paramName = lastDot >= 0 ? path.substring(lastDot + 1) : path;
                    return paramName + ": " + v.getMessage();
                })
                .toList();

        log.debug("Constraint violation: {}", errors);
        return ResponseEntity.badRequest().body(validationErrorBody(errors));
    }

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<Map<String, Object>> handleServerWebInputException(
            ServerWebInputException ex) {
        log.debug("Bad request: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(errorBody(
                "BAD_REQUEST",
                ex.getReason() != null ? ex.getReason() : "Invalid request"
        ));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatusException(
            ResponseStatusException ex) {
        log.debug("Response status exception: {} {}", ex.getStatusCode(), ex.getReason());
        return ResponseEntity.status(ex.getStatusCode()).body(errorBody(
                ex.getStatusCode().toString(),
                ex.getReason() != null ? ex.getReason() : "Request error"
        ));
    }

    @ExceptionHandler(ImageValidationException.class)
    public ResponseEntity<Map<String, Object>> handleImageValidationException(
            ImageValidationException ex) {
        log.debug("Image validation failed: {} {}", ex.errorCode(), ex.getMessage());
        return ResponseEntity.status(ex.status()).body(errorBody(ex.errorCode(), ex.getMessage()));
    }

    /**
     * Handles unexpected exceptions with a generic error response.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorBody("INTERNAL_ERROR", "An unexpected error occurred"));
    }

    private static Map<String, Object> validationErrorBody(List<String> errors) {
        return Map.of(
                "error", "VALIDATION_ERROR",
                "message", "Request validation failed",
                "details", errors
        );
    }

    private static Map<String, Object> errorBody(String error, String message) {
        return Map.of(
                "error", error,
                "message", message
        );
    }
}
