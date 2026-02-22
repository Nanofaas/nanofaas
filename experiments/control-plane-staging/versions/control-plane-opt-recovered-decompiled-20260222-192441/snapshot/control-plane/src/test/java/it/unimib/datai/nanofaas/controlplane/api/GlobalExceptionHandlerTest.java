package it.unimib.datai.nanofaas.controlplane.api;

import it.unimib.datai.nanofaas.controlplane.registry.ImageValidationException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleValidationErrors_returnsBadRequest() {
        BindingResult bindingResult = mock(BindingResult.class);
        when(bindingResult.getFieldErrors()).thenReturn(List.of(
                new FieldError("obj", "name", "must not be blank")
        ));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, bindingResult);

        ResponseEntity<Map<String, Object>> response = handler.handleValidationErrors(ex);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("VALIDATION_ERROR", response.getBody().get("error"));
        @SuppressWarnings("unchecked")
        List<String> details = (List<String>) response.getBody().get("details");
        assertTrue(details.get(0).contains("name"));
    }

    @Test
    void handleWebExchangeBindException_returnsBadRequest() {
        BindingResult bindingResult = mock(BindingResult.class);
        when(bindingResult.getFieldErrors()).thenReturn(List.of(
                new FieldError("obj", "image", "must not be blank")
        ));
        WebExchangeBindException ex = mock(WebExchangeBindException.class);
        when(ex.getBindingResult()).thenReturn(bindingResult);

        ResponseEntity<Map<String, Object>> response = handler.handleWebExchangeBindException(ex);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("VALIDATION_ERROR", response.getBody().get("error"));
    }

    @Test
    void handleConstraintViolation_returnsBadRequest() {
        ConstraintViolation<?> violation = mock(ConstraintViolation.class);
        Path path = mock(Path.class);
        when(path.toString()).thenReturn("invokeSync.name");
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("must not be blank");

        ConstraintViolationException ex = new ConstraintViolationException(Set.of(violation));

        ResponseEntity<Map<String, Object>> response = handler.handleConstraintViolation(ex);

        assertEquals(400, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        List<String> details = (List<String>) response.getBody().get("details");
        assertTrue(details.get(0).contains("name: must not be blank"));
    }

    @Test
    void handleServerWebInputException_returnsBadRequest() {
        ServerWebInputException ex = new ServerWebInputException("Bad body");

        ResponseEntity<Map<String, Object>> response = handler.handleServerWebInputException(ex);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("BAD_REQUEST", response.getBody().get("error"));
    }

    @Test
    void handleResponseStatusException_returnsCorrectStatus() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");

        ResponseEntity<Map<String, Object>> response = handler.handleResponseStatusException(ex);

        assertEquals(404, response.getStatusCode().value());
        assertTrue(response.getBody().get("message").toString().contains("Not found"));
    }

    @Test
    void handleGenericException_returns500() {
        Exception ex = new RuntimeException("unexpected");

        ResponseEntity<Map<String, Object>> response = handler.handleGenericException(ex);

        assertEquals(500, response.getStatusCode().value());
        assertEquals("INTERNAL_ERROR", response.getBody().get("error"));
    }

    @Test
    void handleImageValidationNotFound_returns422() {
        ImageValidationException ex = ImageValidationException.notFound("ghcr.io/example/missing:v1");

        ResponseEntity<Map<String, Object>> response = handler.handleImageValidationException(ex);

        assertEquals(422, response.getStatusCode().value());
        assertEquals("IMAGE_NOT_FOUND", response.getBody().get("error"));
    }

    @Test
    void handleImageValidationAuth_returns424() {
        ImageValidationException ex = ImageValidationException.authRequired("ghcr.io/example/private:v1");

        ResponseEntity<Map<String, Object>> response = handler.handleImageValidationException(ex);

        assertEquals(424, response.getStatusCode().value());
        assertEquals("IMAGE_PULL_AUTH_REQUIRED", response.getBody().get("error"));
    }
}
