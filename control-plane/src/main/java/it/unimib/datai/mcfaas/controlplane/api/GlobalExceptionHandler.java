package com.mcfaas.controlplane.api;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;

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

        Map<String, Object> body = Map.of(
                "error", "VALIDATION_ERROR",
                "message", "Request validation failed",
                "details", errors
        );

        return ResponseEntity.badRequest().body(body);
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

        Map<String, Object> body = Map.of(
                "error", "VALIDATION_ERROR",
                "message", "Request validation failed",
                "details", errors
        );

        return ResponseEntity.badRequest().body(body);
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

        Map<String, Object> body = Map.of(
                "error", "VALIDATION_ERROR",
                "message", "Request validation failed",
                "details", errors
        );

        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Handles unexpected exceptions with a generic error response.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);

        Map<String, Object> body = Map.of(
                "error", "INTERNAL_ERROR",
                "message", "An unexpected error occurred"
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
