# Issue 008: CallbackClient ignora silenziosamente le eccezioni

**Severità**: MEDIA
**Componente**: function-runtime/core/CallbackClient.java
**Linee**: 20-31

## Descrizione

`CallbackClient.sendResult()` non gestisce gli errori di rete. Se il callback al control-plane fallisce, l'eccezione viene propagata ma non c'è logging né retry. Il chiamante (`InvokeController`) non gestisce questo caso correttamente.

```java
// CallbackClient.java - CODICE ATTUALE
public void sendResult(String executionId, InvocationResult result) {
    if (baseUrl == null || baseUrl.isBlank() || executionId == null || executionId.isBlank()) {
        return;  // Silenziosamente ignora
    }
    String url = baseUrl + "/" + executionId + ":complete";
    RestClient.RequestBodySpec request = restClient.post()
            .uri(url)
            .contentType(MediaType.APPLICATION_JSON);
    request.body(result)
            .retrieve()
            .toBodilessEntity();  // Eccezione non gestita!
}
```

## Flusso del Problema

```
1. Job Pod esegue funzione con successo
2. InvokeController.invoke() chiama handler.handle()
3. handler ritorna risultato
4. callbackClient.sendResult(executionId, result)
5. Network error: control-plane non raggiungibile
6. RestClient lancia RestClientException
7. Eccezione propagata a InvokeController
8. InvokeController ritorna HTTP 500
9. MA: il risultato della funzione è perso!
10. Control-plane non sa che la funzione è completata
11. Esecuzione rimane in stato RUNNING → timeout
```

## Impatto

1. Risultati di funzioni persi se callback fallisce
2. Esecuzioni rimangono in stato RUNNING indefinitamente
3. Nessun log per debuggare problemi di rete
4. Nessun retry automatico
5. Metriche di successo/errore sbagliate

## Piano di Risoluzione

### Step 1: Aggiungere error handling e logging

```java
// CallbackClient.java - CODICE CORRETTO
@Component
public class CallbackClient {
    private static final Logger log = LoggerFactory.getLogger(CallbackClient.class);
    private final RestClient restClient;
    private final String baseUrl;

    public CallbackClient() {
        this.restClient = RestClient.create();
        this.baseUrl = System.getenv("CALLBACK_URL");
    }

    public boolean sendResult(String executionId, InvocationResult result) {
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("CALLBACK_URL not configured, skipping callback");
            return false;
        }
        if (executionId == null || executionId.isBlank()) {
            log.warn("executionId is null/blank, skipping callback");
            return false;
        }

        String url = baseUrl + "/" + executionId + ":complete";
        try {
            restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(result)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("Callback sent successfully for execution {}", executionId);
            return true;
        } catch (RestClientException ex) {
            log.error("Failed to send callback for execution {}: {}", executionId, ex.getMessage());
            return false;
        }
    }
}
```

### Step 2: Implementare retry con backoff

```java
public boolean sendResultWithRetry(String executionId, InvocationResult result) {
    int maxRetries = 3;
    int[] delays = {100, 500, 2000};  // ms

    for (int attempt = 0; attempt < maxRetries; attempt++) {
        if (sendResult(executionId, result)) {
            return true;
        }
        if (attempt < maxRetries - 1) {
            try {
                Thread.sleep(delays[attempt]);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    log.error("All {} callback attempts failed for execution {}", maxRetries, executionId);
    return false;
}
```

### Step 3: Aggiornare InvokeController

```java
// InvokeController.java - CODICE CORRETTO
@PostMapping("/invoke")
public ResponseEntity<Object> invoke(@RequestBody InvocationRequest request) {
    String executionId = System.getenv("EXECUTION_ID");
    if (executionId == null || executionId.isBlank()) {
        log.error("EXECUTION_ID environment variable not set");
        return ResponseEntity.status(500)
            .body(Map.of("error", "EXECUTION_ID not configured"));
    }

    try {
        FunctionHandler handler = handlerRegistry.resolve();
        Object output = handler.handle(request);

        // Callback è best-effort, non fallire la risposta se callback fallisce
        boolean callbackSent = callbackClient.sendResultWithRetry(
            executionId,
            InvocationResult.success(output)
        );

        if (!callbackSent) {
            log.warn("Callback failed but function succeeded, returning result anyway");
        }

        return ResponseEntity.ok(output);

    } catch (Exception ex) {
        log.error("Handler error for execution {}: {}", executionId, ex.getMessage(), ex);

        // Prova a inviare errore al control-plane
        callbackClient.sendResultWithRetry(
            executionId,
            InvocationResult.error("HANDLER_ERROR", ex.getMessage())
        );

        return ResponseEntity.status(500)
            .body(Map.of("error", ex.getMessage()));
    }
}
```

### Step 4: Aggiungere metriche

```java
// CallbackClient.java
private final Counter callbackSuccess;
private final Counter callbackFailure;

public CallbackClient(MeterRegistry registry) {
    // ...
    this.callbackSuccess = Counter.builder("function_callback_success")
        .register(registry);
    this.callbackFailure = Counter.builder("function_callback_failure")
        .register(registry);
}

// In sendResult:
if (success) {
    callbackSuccess.increment();
} else {
    callbackFailure.increment();
}
```

## Test da Creare

### Test 1: CallbackClientSuccessTest
```java
@Test
void sendResult_whenServerResponds_returnsTrue() {
    // Mock server che risponde 200
    mockServer.expect(requestTo(containsString("/exec-123:complete")))
        .andRespond(withSuccess());

    boolean result = callbackClient.sendResult("exec-123", InvocationResult.success("ok"));

    assertThat(result).isTrue();
    mockServer.verify();
}
```

### Test 2: CallbackClientFailureTest
```java
@Test
void sendResult_whenServerErrors_returnsFalse() {
    mockServer.expect(requestTo(containsString("/exec-123:complete")))
        .andRespond(withServerError());

    boolean result = callbackClient.sendResult("exec-123", InvocationResult.success("ok"));

    assertThat(result).isFalse();
}
```

### Test 3: CallbackClientRetryTest
```java
@Test
void sendResultWithRetry_retriesOnFailure() {
    AtomicInteger attempts = new AtomicInteger(0);

    // Prima due falliscono, terza succede
    mockServer.expect(times(2), requestTo(containsString(":complete")))
        .andRespond(invocation -> {
            if (attempts.incrementAndGet() <= 2) {
                throw new RuntimeException("Connection refused");
            }
            return withSuccess().createResponse(null);
        });

    boolean result = callbackClient.sendResultWithRetry("exec-123", InvocationResult.success("ok"));

    assertThat(result).isTrue();
    assertThat(attempts.get()).isEqualTo(3);
}
```

### Test 4: CallbackClientNullUrlTest
```java
@Test
void sendResult_whenUrlNotConfigured_returnsFalse() {
    CallbackClient client = new CallbackClient(null);  // No URL

    boolean result = client.sendResult("exec-123", InvocationResult.success("ok"));

    assertThat(result).isFalse();
}
```

### Test 5: InvokeControllerCallbackFailureTest
```java
@Test
void invoke_whenCallbackFails_stillReturnsSuccess() {
    // Handler succede
    when(handlerRegistry.resolve()).thenReturn(req -> "result");

    // Callback fallisce
    when(callbackClient.sendResultWithRetry(any(), any())).thenReturn(false);

    ResponseEntity<Object> response = controller.invoke(new InvocationRequest("input"));

    // La risposta al client è comunque success
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo("result");
}
```

## File da Modificare

1. `function-runtime/src/main/java/com/nanofaas/runtime/core/CallbackClient.java`
2. `function-runtime/src/main/java/com/nanofaas/runtime/api/InvokeController.java`
3. `function-runtime/src/test/java/com/nanofaas/runtime/core/CallbackClientTest.java` (nuovo)
4. `function-runtime/src/test/java/com/nanofaas/runtime/api/InvokeControllerTest.java` (estendere)

## Criteri di Accettazione

- [ ] CallbackClient logga errori di rete
- [ ] CallbackClient ritorna boolean per indicare successo/fallimento
- [ ] Retry con backoff (3 tentativi: 100ms, 500ms, 2000ms)
- [ ] InvokeController non fallisce se callback fallisce ma handler succede
- [ ] Metriche `function_callback_success` e `function_callback_failure`
- [ ] Tutti i test passano

## Note di Sviluppo

_Spazio per appunti durante l'implementazione_

### Considerazione: timeout del RestClient

Il RestClient non ha timeout configurato. Aggiungere:

```java
private final RestClient restClient = RestClient.builder()
    .requestFactory(() -> {
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        return factory;
    })
    .build();
```

---
