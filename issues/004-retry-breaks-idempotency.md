# Issue 004: Retry logic rompe le garanzie di idempotenza

**Severità**: ALTA
**Componente**: control-plane/core/InvocationService.java
**Linee**: 115-130

## Descrizione

Quando un'invocazione fallisce e viene ritentata, il nuovo task riutilizza lo stesso `idempotencyKey` dell'invocazione originale. Questo causa un problema: il retry viene deduplificato dall'IdempotencyStore e non viene mai eseguito.

```java
// InvocationService.java:115-130 - CODICE ATTUALE (BUGGATO)
if (!result.success() && record.task().attempt() < record.task().functionSpec().maxRetries()) {
    metrics.retry(record.task().functionName());
    InvocationTask retryTask = new InvocationTask(
            record.executionId(),
            record.task().functionName(),
            record.task().functionSpec(),
            record.task().request(),
            record.task().idempotencyKey(),  // <-- PROBLEMA: stesso key!
            record.task().traceId(),
            Instant.now(),
            record.task().attempt() + 1
    );
    ExecutionRecord retryRecord = new ExecutionRecord(retryTask);
    executionStore.put(retryRecord);
    queueManager.enqueue(retryTask);
}
```

## Scenario del Bug

```
1. Client invia: POST /functions/myFunc:invoke
   Headers: Idempotency-Key: abc123

2. InvocationService.invokeSync():
   - IdempotencyStore.getExecutionId("myFunc", "abc123") → empty
   - Crea executionId = "exec-001"
   - IdempotencyStore.put("myFunc", "abc123", "exec-001")
   - Enqueue task con idempotencyKey = "abc123"

3. Scheduler dispatcha, funzione fallisce (timeout/errore)

4. completeExecution() vede errore, attempt=1, maxRetries=3
   - Crea retryTask con idempotencyKey = "abc123" (stesso!)
   - Crea nuovo ExecutionRecord con executionId = "exec-001" (stesso!)
   - executionStore.put(retryRecord) → sovrascrive record precedente

5. retryTask viene enqueued

6. Quando retryTask viene processato:
   - getOrCreateExecution() chiama IdempotencyStore.getExecutionId("myFunc", "abc123")
   - Ritorna "exec-001" (trovato!)
   - Ritorna l'esecuzione esistente invece di crearne una nuova
   - IL RETRY NON VIENE MAI ESEGUITO!
```

## Impatto

1. I retry non vengono mai eseguiti
2. Le funzioni che falliscono rimangono in stato di errore
3. Il maxRetries configurato non ha effetto
4. I client pensano che ci siano stati retry ma non è vero
5. Metriche di retry sono sbagliate

## Analisi Approfondita

Guardando il codice più attentamente:

```java
// InvocationService.java:30-37
private ExecutionRecord getOrCreateExecution(...) {
    if (idempotencyKey != null) {
        Optional<String> existingId = idempotencyStore.getExecutionId(functionName, idempotencyKey);
        if (existingId.isPresent()) {
            return executionStore.get(existingId.get());  // Ritorna esecuzione esistente!
        }
    }
    // ... crea nuova esecuzione
}
```

Il retry chiama la stessa logica, quindi viene deduplificato.

## Piano di Risoluzione

### Step 1: Decidere la strategia corretta

**Opzione A: Disabilitare idempotenza per retry**
```java
InvocationTask retryTask = new InvocationTask(
        record.executionId(),
        record.task().functionName(),
        record.task().functionSpec(),
        record.task().request(),
        null,  // <-- Nessun idempotencyKey per retry
        record.task().traceId(),
        Instant.now(),
        record.task().attempt() + 1
);
```

Pro: Semplice
Contro: I retry non sono idempotenti (ma forse è corretto?)

**Opzione B: Usare chiave diversa per ogni tentativo**
```java
String retryIdempotencyKey = record.task().idempotencyKey() == null
    ? null
    : record.task().idempotencyKey() + "#attempt" + (record.task().attempt() + 1);
```

Pro: Ogni tentativo è identificabile univocamente
Contro: Chiavi multiple per la stessa operazione logica

**Opzione C: Separare idempotenza dal retry (RACCOMANDATO)**

L'idempotenza dovrebbe proteggere da richieste duplicate DEL CLIENT, non dai retry interni.
I retry interni sono gestiti dal sistema e NON dovrebbero passare per idempotencyStore.

```java
// Nel flusso di retry, non chiamare getOrCreateExecution()
// ma creare direttamente il task senza idempotency check
```

### Step 2: Implementare Opzione C

Il retry dovrebbe bypassare l'idempotency check perché:
1. È un'operazione interna del sistema
2. L'esecuzione originale è già stata creata
3. Il retry usa lo stesso executionId

```java
// InvocationService.java - CODICE CORRETTO

public void completeExecution(String executionId, InvocationResult result) {
    ExecutionRecord record = executionStore.get(executionId);
    if (record == null) {
        log.warn("Execution {} not found for completion", executionId);
        return;
    }

    // ... gestione completamento ...

    if (!result.success() && shouldRetry(record)) {
        scheduleRetry(record);
    }
}

private boolean shouldRetry(ExecutionRecord record) {
    return record.task().attempt() < record.task().functionSpec().maxRetries();
}

private void scheduleRetry(ExecutionRecord record) {
    metrics.retry(record.task().functionName());

    // Crea task di retry SENZA idempotency key
    // Il retry usa lo stesso executionId, quindi non serve idempotency
    InvocationTask retryTask = new InvocationTask(
            record.executionId(),  // Stesso executionId
            record.task().functionName(),
            record.task().functionSpec(),
            record.task().request(),
            null,  // No idempotency key per retry
            record.task().traceId(),
            Instant.now(),
            record.task().attempt() + 1
    );

    // Aggiorna il record esistente invece di crearne uno nuovo
    record.state(ExecutionState.QUEUED);
    record.task(retryTask);  // Aggiorna con nuovo attempt
    record.lastError(null);  // Resetta errore

    // Enqueue direttamente, senza passare per getOrCreateExecution
    queueManager.enqueue(retryTask);
}
```

### Step 3: Verificare il flusso di enqueue

Il `QueueManager.enqueue()` non dovrebbe avere logica di idempotenza, solo accodamento.

### Step 4: Aggiornare ExecutionRecord per supportare update del task

```java
// ExecutionRecord.java - aggiungere setter per task se non esiste
public void task(InvocationTask task) {
    this.task = task;
}
```

## Test da Creare

### Test 1: RetryActuallyExecutesTest
```java
@Test
void retry_whenFirstAttemptFails_executesAgain() {
    // Given: funzione che fallisce la prima volta e succede la seconda
    AtomicInteger attempts = new AtomicInteger(0);
    FunctionHandler handler = req -> {
        if (attempts.incrementAndGet() == 1) {
            throw new RuntimeException("First attempt fails");
        }
        return "success";
    };

    // When: invocazione con maxRetries=3
    InvocationResponse response = invoke("myFunc", "payload", "idempKey123");

    // Then: dovrebbe succedere al secondo tentativo
    assertThat(response.status()).isEqualTo("success");
    assertThat(attempts.get()).isEqualTo(2);
}
```

### Test 2: RetryPreservesExecutionIdTest
```java
@Test
void retry_preservesOriginalExecutionId() {
    // Given: funzione che fallisce
    String[] executionIds = new String[3];
    AtomicInteger attempt = new AtomicInteger(0);

    // Configura mock per catturare executionId ad ogni tentativo
    doAnswer(inv -> {
        String execId = inv.getArgument(0);
        executionIds[attempt.getAndIncrement()] = execId;
        if (attempt.get() < 3) {
            throw new RuntimeException("fail");
        }
        return "success";
    }).when(dispatcher).dispatch(any());

    // When: invocazione con maxRetries=3
    invoke("myFunc", "payload");

    // Then: tutti i tentativi usano lo stesso executionId
    assertThat(executionIds[0]).isEqualTo(executionIds[1]);
    assertThat(executionIds[1]).isEqualTo(executionIds[2]);
}
```

### Test 3: RetryDoesNotCheckIdempotencyTest
```java
@Test
void retry_doesNotInvokeIdempotencyStore() {
    // Given: funzione che fallisce al primo tentativo
    when(handler.handle(any()))
        .thenThrow(new RuntimeException("fail"))
        .thenReturn("success");

    // When: invocazione con idempotencyKey
    invoke("myFunc", "payload", "idempKey123");

    // Then: idempotencyStore.getExecutionId chiamato solo UNA volta
    // (per la richiesta iniziale, non per il retry)
    verify(idempotencyStore, times(1)).getExecutionId("myFunc", "idempKey123");
}
```

### Test 4: IdempotencyStillWorksForClientRetriesTest
```java
@Test
void idempotency_stillPreventsClientDuplicates() {
    // Given: funzione che succede
    when(handler.handle(any())).thenReturn("result1");

    // When: prima invocazione
    InvocationResponse resp1 = invoke("myFunc", "payload", "idempKey123");

    // When: seconda invocazione con stessa chiave
    InvocationResponse resp2 = invoke("myFunc", "payload", "idempKey123");

    // Then: stessa risposta, funzione eseguita una sola volta
    assertThat(resp1.executionId()).isEqualTo(resp2.executionId());
    verify(handler, times(1)).handle(any());
}
```

### Test 5: RetryCounterMetricsTest
```java
@Test
void retry_incrementsRetryMetric() {
    // Given: funzione che fallisce 2 volte poi succede
    AtomicInteger attempts = new AtomicInteger(0);
    when(handler.handle(any())).thenAnswer(inv -> {
        if (attempts.incrementAndGet() <= 2) {
            throw new RuntimeException("fail");
        }
        return "success";
    });

    // When: invocazione con maxRetries=3
    invoke("myFunc", "payload");

    // Then: retry metric incrementata 2 volte
    verify(metrics, times(2)).retry("myFunc");
}
```

## File da Modificare

1. `control-plane/src/main/java/com/nanofaas/controlplane/core/InvocationService.java`
2. `control-plane/src/main/java/com/nanofaas/controlplane/core/ExecutionRecord.java` (se serve setter)
3. `control-plane/src/test/java/com/nanofaas/controlplane/core/InvocationServiceRetryTest.java` (nuovo)

## Criteri di Accettazione

- [ ] Retry effettivamente esegue la funzione di nuovo
- [ ] Retry usa lo stesso executionId
- [ ] Retry non passa per IdempotencyStore
- [ ] Idempotenza client funziona ancora correttamente
- [ ] Metriche di retry sono accurate
- [ ] Tutti i test passano
- [ ] maxRetries rispettato (non più di N tentativi totali)

## Note di Sviluppo

_Spazio per appunti durante l'implementazione_

### Domanda aperta: exponential backoff?

Attualmente i retry vengono enqueued immediatamente. Sarebbe meglio implementare exponential backoff?

```java
private void scheduleRetry(ExecutionRecord record) {
    int attempt = record.task().attempt();
    long delayMs = (long) Math.pow(2, attempt) * 100;  // 100ms, 200ms, 400ms, ...
    long maxDelayMs = 30_000;  // Cap a 30 secondi
    delayMs = Math.min(delayMs, maxDelayMs);

    // Enqueue con delay
    scheduler.schedule(() -> queueManager.enqueue(retryTask), delayMs, TimeUnit.MILLISECONDS);
}
```

Questo potrebbe essere una issue separata (Issue 005?).

---
