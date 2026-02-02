# Issue 001: REMOTE dispatch non completa mai l'esecuzione

**Severità**: CRITICA
**Componente**: control-plane/core/Scheduler.java
**Linee**: 118-123

## Descrizione

Quando una funzione viene invocata in modalità `REMOTE` (Kubernetes Job), il completamento del dispatch non viene mai gestito nel caso di successo. Solo il caso di errore chiama `completeExecution()`.

```java
// Scheduler.java:118-123 - CODICE ATTUALE (BUGGATO)
dispatcherRouter.dispatchRemote(task).whenComplete((result, error) -> {
    if (error != null) {
        invocationService.completeExecution(task.executionId(),
                InvocationResult.error("DISPATCH_ERROR", error.getMessage()));
    }
    // MANCA: else branch per gestire il successo!
});
```

## Impatto

1. Le invocazioni sincrone in modalità REMOTE rimangono bloccate indefinitamente
2. Il client attende il timeout (default 30s) senza mai ricevere risposta
3. Le risorse (thread, memoria) vengono sprecate
4. ExecutionState rimane in stato RUNNING per sempre (fino a eviction TTL)

## Nota Importante

Analizzando meglio il flusso, in modalità REMOTE il completamento avviene tramite callback dal Job pod:
- Il Job esegue function-runtime
- function-runtime chiama `CallbackClient.sendResult()`
- Il callback arriva a `InvocationController.completeExecution()`

Tuttavia, `KubernetesDispatcher.dispatch()` ritorna `InvocationResult.success(null)` immediatamente dopo aver creato il Job, PRIMA che il Job sia completato. Questo è il design inteso.

**Il vero bug è**: se la creazione del Job fallisce o se `dispatchRemote()` lancia eccezione, l'errore viene gestito. Ma se la creazione del Job ha successo, il `result` è `InvocationResult.success(null)` e non viene fatto nulla con esso.

In realtà il flusso corretto per REMOTE è:
1. `dispatchRemote()` crea il Job e ritorna `success(null)`
2. Il Job viene schedulato da K8s
3. Il Job pod esegue la funzione
4. Il pod chiama il callback URL
5. Il callback completa l'esecuzione

Quindi il bug potrebbe non essere critico se il callback funziona. MA: se il callback fallisce (network issue, pod crash), l'esecuzione rimane appesa.

## Piano di Risoluzione

### Step 1: Verificare il comportamento attuale
- [ ] Scrivere test che simula dispatch REMOTE con callback funzionante
- [ ] Scrivere test che simula dispatch REMOTE con callback fallito
- [ ] Verificare cosa succede quando `dispatchRemote()` ritorna success ma il callback non arriva mai

### Step 2: Decidere la strategia
**Opzione A**: Lasciare il codice attuale ma aggiungere timeout/cleanup per esecuzioni stale
**Opzione B**: Aggiungere logging/metriche quando dispatch REMOTE completa
**Opzione C**: Implementare polling del Job status invece di aspettare callback

### Step 3: Implementare la fix
```java
// Scheduler.java - CODICE CORRETTO
dispatcherRouter.dispatchRemote(task).whenComplete((result, error) -> {
    if (error != null) {
        invocationService.completeExecution(task.executionId(),
                InvocationResult.error("DISPATCH_ERROR", error.getMessage()));
    } else {
        // Job creato con successo - il completamento arriverà via callback
        // Log per debugging/monitoring
        log.debug("Job created for execution {}, waiting for callback", task.executionId());
        metrics.jobCreated(task.functionName());
    }
});
```

### Step 4: Aggiungere timeout per esecuzioni orfane
Implementare un meccanismo che:
- Controlla esecuzioni in stato RUNNING da più di X minuti
- Le marca come TIMEOUT se il callback non è mai arrivato
- Emette metrica per monitoring

## Test da Creare

### Test 1: RemoteDispatchSuccessCallbackTest
```java
@Test
void remoteDispatch_withSuccessfulCallback_completesExecution() {
    // Given: funzione registrata in modalità REMOTE
    // When: invocazione sincrona
    // And: callback arriva con successo
    // Then: esecuzione completata con SUCCESS
}
```

### Test 2: RemoteDispatchCallbackTimeoutTest
```java
@Test
void remoteDispatch_withoutCallback_timesOut() {
    // Given: funzione registrata in modalità REMOTE
    // When: invocazione sincrona
    // And: callback non arriva mai
    // Then: esecuzione completata con TIMEOUT dopo timeoutMs
}
```

### Test 3: RemoteDispatchJobCreationFailureTest
```java
@Test
void remoteDispatch_whenJobCreationFails_returnsError() {
    // Given: funzione registrata in modalità REMOTE
    // And: K8s client ritorna errore
    // When: invocazione sincrona
    // Then: esecuzione completata con ERROR
}
```

### Test 4: RemoteDispatchCallbackFailureRetryTest
```java
@Test
void remoteDispatch_whenCallbackFails_retriesCallback() {
    // Given: funzione registrata in modalità REMOTE
    // When: callback fallisce la prima volta
    // Then: callback viene ritentato (o esecuzione marcata come errore)
}
```

## File da Modificare

1. `control-plane/src/main/java/com/nanofaas/controlplane/core/Scheduler.java`
2. `control-plane/src/main/java/com/nanofaas/controlplane/core/Metrics.java` (nuova metrica)
3. `control-plane/src/test/java/com/nanofaas/controlplane/core/SchedulerRemoteDispatchTest.java` (nuovo)

## Criteri di Accettazione

- [ ] Il dispatch REMOTE logga quando il Job viene creato
- [ ] Metrica `nanofaas_job_created_total` emessa
- [ ] Test per callback success passa
- [ ] Test per callback timeout passa
- [ ] Test per job creation failure passa
- [ ] Documentazione aggiornata in docs/control-plane.md

## Note di Sviluppo

_Spazio per appunti durante l'implementazione_

---
