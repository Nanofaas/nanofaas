# Issue 011: Validazione input mancante nei controller

**Severità**: BASSA
**Componente**: control-plane/api/*.java, function-runtime/api/*.java
**Linee**: Varie

## Descrizione

I controller non validano adeguatamente gli input. Path variables, request body e query parameters non sono validati, causando messaggi di errore poco chiari e potenziali NullPointerException.

```java
// InvocationController.java - NESSUNA VALIDAZIONE
@PostMapping("/functions/{name}:invoke")
public ResponseEntity<InvocationResponse> invokeSync(
        @PathVariable String name,                    // Può essere vuoto?
        @RequestBody InvocationRequest request,       // Può essere null?
        @RequestParam(required = false) String idempotencyKey,
        @RequestParam(required = false) String traceId,
        @RequestParam(required = false) Integer timeoutMs) {  // Può essere negativo?
    // ...
}

// FunctionController.java - NESSUNA VALIDAZIONE
@PostMapping
public ResponseEntity<FunctionSpec> register(@RequestBody FunctionSpec spec) {
    // spec.name() può essere null/vuoto?
    // spec.image() può essere null?
    // spec.concurrency() può essere 0 o negativo?
}
```

## Problemi Specifici

1. **Function name**: può essere vuoto, null, contenere caratteri speciali
2. **FunctionSpec.image**: può essere null o vuoto
3. **FunctionSpec.concurrency**: può essere 0 o negativo
4. **FunctionSpec.queueSize**: può essere 0 o negativo
5. **FunctionSpec.timeoutMs**: può essere 0 o negativo
6. **timeoutMs override**: può essere negativo
7. **InvocationRequest**: può essere null
8. **executionId** in path: può essere vuoto

## Impatto

1. Messaggi di errore poco chiari (NPE invece di "name is required")
2. Stato inconsistente (funzione con concurrency=0)
3. Potenziali bug difficili da debuggare
4. Esperienza utente scadente

## Piano di Risoluzione

### Step 1: Aggiungere validazione a FunctionSpec

```java
// FunctionSpec.java - con validazione Jakarta
public record FunctionSpec(
    @NotBlank(message = "Function name is required")
    @Pattern(regexp = "^[a-z0-9][a-z0-9-]*[a-z0-9]$",
             message = "Function name must be lowercase alphanumeric with hyphens")
    @Size(max = 63, message = "Function name must be at most 63 characters")
    String name,

    @NotBlank(message = "Image is required")
    String image,

    List<String> command,

    Map<String, String> env,

    ResourceSpec resources,

    @Min(value = 1, message = "timeoutMs must be at least 1")
    @Max(value = 300000, message = "timeoutMs must be at most 300000 (5 minutes)")
    Integer timeoutMs,

    @Min(value = 1, message = "concurrency must be at least 1")
    @Max(value = 100, message = "concurrency must be at most 100")
    Integer concurrency,

    @Min(value = 1, message = "queueSize must be at least 1")
    @Max(value = 10000, message = "queueSize must be at most 10000")
    Integer queueSize,

    @Min(value = 0, message = "maxRetries cannot be negative")
    @Max(value = 10, message = "maxRetries must be at most 10")
    Integer maxRetries,

    String endpointUrl,

    ExecutionMode executionMode
) {}
```

### Step 2: Aggiungere validazione a InvocationRequest

```java
// InvocationRequest.java
public record InvocationRequest(
    @NotNull(message = "Input payload is required")
    Object input,

    Map<String, String> metadata
) {}
```

### Step 3: Abilitare validazione nei controller

```java
// FunctionController.java
@PostMapping
public ResponseEntity<FunctionSpec> register(@RequestBody @Valid FunctionSpec spec) {
    // Spring valida automaticamente con @Valid
    // ...
}

// InvocationController.java
@PostMapping("/functions/{name}:invoke")
public ResponseEntity<InvocationResponse> invokeSync(
        @PathVariable @NotBlank String name,
        @RequestBody @Valid InvocationRequest request,
        @RequestParam(required = false) @Size(max = 255) String idempotencyKey,
        @RequestParam(required = false) @Size(max = 64) String traceId,
        @RequestParam(required = false) @Min(1) @Max(300000) Integer timeoutMs) {
    // ...
}
```

### Step 4: Configurare exception handler per validazione

```java
// GlobalExceptionHandler.java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationErrors(
            MethodArgumentNotValidException ex) {

        List<String> errors = ex.getBindingResult()
            .getFieldErrors()
            .stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .toList();

        Map<String, Object> body = Map.of(
            "error", "VALIDATION_ERROR",
            "message", "Request validation failed",
            "details", errors
        );

        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(
            ConstraintViolationException ex) {

        List<String> errors = ex.getConstraintViolations()
            .stream()
            .map(v -> v.getPropertyPath() + ": " + v.getMessage())
            .toList();

        Map<String, Object> body = Map.of(
            "error", "VALIDATION_ERROR",
            "message", "Request validation failed",
            "details", errors
        );

        return ResponseEntity.badRequest().body(body);
    }
}
```

### Step 5: Aggiornare FunctionDefaults con validazione

```java
// FunctionDefaults.java
@ConfigurationProperties(prefix = "nanofaas.defaults")
@Validated
public record FunctionDefaults(
    @Min(1) @Max(300000) int timeoutMs,
    @Min(1) @Max(100) int concurrency,
    @Min(1) @Max(10000) int queueSize,
    @Min(0) @Max(10) int maxRetries
) {}
```

## Test da Creare

### Test 1: FunctionSpecValidationTest
```java
@Test
void register_withBlankName_returns400() {
    FunctionSpec spec = FunctionSpec.builder()
        .name("")
        .image("myimage")
        .build();

    ResponseEntity<String> response = restTemplate.postForEntity(
        "/v1/functions", spec, String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).contains("name");
}

@Test
void register_withInvalidNameFormat_returns400() {
    FunctionSpec spec = FunctionSpec.builder()
        .name("My Function!")  // Invalid characters
        .image("myimage")
        .build();

    ResponseEntity<String> response = restTemplate.postForEntity(
        "/v1/functions", spec, String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
}

@Test
void register_withNullImage_returns400() {
    FunctionSpec spec = FunctionSpec.builder()
        .name("myfunc")
        .image(null)
        .build();

    ResponseEntity<String> response = restTemplate.postForEntity(
        "/v1/functions", spec, String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).contains("image");
}

@Test
void register_withZeroConcurrency_returns400() {
    FunctionSpec spec = FunctionSpec.builder()
        .name("myfunc")
        .image("myimage")
        .concurrency(0)
        .build();

    ResponseEntity<String> response = restTemplate.postForEntity(
        "/v1/functions", spec, String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).contains("concurrency");
}
```

### Test 2: InvocationValidationTest
```java
@Test
void invoke_withBlankFunctionName_returns400() {
    ResponseEntity<String> response = restTemplate.postForEntity(
        "/v1/functions/ :invoke",  // Blank name
        new InvocationRequest("payload"),
        String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
}

@Test
void invoke_withNegativeTimeout_returns400() {
    ResponseEntity<String> response = restTemplate.postForEntity(
        "/v1/functions/myfunc:invoke?timeoutMs=-1",
        new InvocationRequest("payload"),
        String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
}

@Test
void invoke_withNullBody_returns400() {
    ResponseEntity<String> response = restTemplate.postForEntity(
        "/v1/functions/myfunc:invoke",
        null,
        String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
}
```

### Test 3: ValidationErrorFormatTest
```java
@Test
void validationError_hasCorrectFormat() {
    FunctionSpec spec = FunctionSpec.builder()
        .name("")
        .image("")
        .concurrency(-1)
        .build();

    ResponseEntity<Map> response = restTemplate.postForEntity(
        "/v1/functions", spec, Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsKey("error");
    assertThat(response.getBody()).containsKey("message");
    assertThat(response.getBody()).containsKey("details");
    assertThat((List<?>) response.getBody().get("details")).hasSizeGreaterThan(1);
}
```

## File da Modificare

1. `common/src/main/java/com/nanofaas/common/FunctionSpec.java` (aggiungere annotazioni)
2. `common/src/main/java/com/nanofaas/common/InvocationRequest.java` (aggiungere annotazioni)
3. `control-plane/src/main/java/com/nanofaas/controlplane/api/FunctionController.java` (aggiungere @Valid)
4. `control-plane/src/main/java/com/nanofaas/controlplane/api/InvocationController.java` (aggiungere @Valid)
5. `control-plane/src/main/java/com/nanofaas/controlplane/api/GlobalExceptionHandler.java` (nuovo)
6. `control-plane/src/main/java/com/nanofaas/controlplane/core/FunctionDefaults.java` (aggiungere @Validated)
7. `common/build.gradle` (aggiungere jakarta.validation-api se non presente)
8. `control-plane/src/test/java/com/nanofaas/controlplane/api/ValidationTest.java` (nuovo)

## Criteri di Accettazione

- [ ] FunctionSpec validato: name, image required; concurrency, queueSize, timeoutMs, maxRetries con range
- [ ] InvocationRequest validato: input required
- [ ] Path variables e query params validati
- [ ] Errori di validazione ritornano 400 con messaggio chiaro
- [ ] Formato errore consistente: {error, message, details}
- [ ] Tutti i test passano
- [ ] OpenAPI spec aggiornato con constraints

## Note di Sviluppo

_Spazio per appunti durante l'implementazione_

### Dipendenze necessarie

```groovy
// build.gradle (common)
dependencies {
    implementation 'jakarta.validation:jakarta.validation-api:3.0.2'
}

// build.gradle (control-plane)
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-validation'
}
```

### Pattern per nome funzione

Il pattern `^[a-z0-9][a-z0-9-]*[a-z0-9]$` segue le convenzioni Kubernetes per nomi di risorse.

---
