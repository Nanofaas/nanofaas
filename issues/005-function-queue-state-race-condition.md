# Issue 005: Race condition tra canDispatch() e incrementInFlight()

**Severità**: MEDIA
**Componente**: control-plane/core/FunctionQueueState.java, Scheduler.java
**Linee**: FunctionQueueState:43-45, Scheduler:70-75

## Descrizione

C'è una race condition TOCTOU tra il controllo `canDispatch()` e l'incremento `incrementInFlight()`. Due thread potrebbero entrambi superare il check e incrementare, superando il limite di concorrenza configurato.

```java
// FunctionQueueState.java:43-45
public boolean canDispatch() {
    return inFlight.get() < concurrency;  // CHECK
}

// Scheduler.java:70-75
if (!state.canDispatch()) {       // CHECK
    continue;
}
InvocationTask task = state.poll();
if (task == null) {
    continue;
}
state.incrementInFlight();        // INCREMENT - RACE!
dispatch(state, task);
```

## Scenario di Race Condition

```
Concurrency = 1, inFlight = 0

Thread A (Scheduler)              Thread B (altro Scheduler? o stesso in loop veloce)
──────────────────                ──────────────────────────────────────────────────
canDispatch() → inFlight(0) < 1 → true
                                  canDispatch() → inFlight(0) < 1 → true
poll() → task1
                                  poll() → task2
incrementInFlight() → inFlight = 1
                                  incrementInFlight() → inFlight = 2  // SUPERA LIMITE!
dispatch(task1)
                                  dispatch(task2)  // NON DOVREBBE SUCCEDERE!
```

**Nota**: Nel codice attuale c'è un solo thread Scheduler, quindi questa race è improbabile. Ma:
1. Il codice non è thread-safe by design
2. Futura evoluzione potrebbe aggiungere più scheduler thread
3. È comunque una bad practice

## Impatto

1. Più task del limite di concorrenza possono essere dispatchati
2. Sovraccarico del sistema downstream
3. Metriche di concorrenza imprecise
4. Potenziale throttling/rate limiting errato

## Piano di Risoluzione

### Step 1: Implementare check-and-increment atomico

```java
// FunctionQueueState.java - CODICE CORRETTO
public class FunctionQueueState {
    private final AtomicInteger inFlight = new AtomicInteger(0);
    private final int concurrency;

    /**
     * Atomically checks if dispatch is allowed and increments inFlight if so.
     * @return true if dispatch was allowed and inFlight was incremented
     */
    public boolean tryAcquireSlot() {
        while (true) {
            int current = inFlight.get();
            if (current >= concurrency) {
                return false;  // Limite raggiunto
            }
            if (inFlight.compareAndSet(current, current + 1)) {
                return true;  // Slot acquisito
            }
            // CAS fallito, qualcun altro ha modificato - riprova
        }
    }

    /**
     * Releases a dispatch slot.
     */
    public void releaseSlot() {
        inFlight.decrementAndGet();
    }

    // Deprecato - usare tryAcquireSlot()
    @Deprecated
    public boolean canDispatch() {
        return inFlight.get() < concurrency;
    }

    // Deprecato - usare tryAcquireSlot()
    @Deprecated
    public void incrementInFlight() {
        inFlight.incrementAndGet();
    }

    // Deprecato - usare releaseSlot()
    @Deprecated
    public void decrementInFlight() {
        inFlight.decrementAndGet();
    }
}
```

### Step 2: Aggiornare Scheduler

```java
// Scheduler.java - CODICE CORRETTO
private void loop() {
    while (running.get()) {
        boolean didWork = false;
        for (FunctionQueueState state : queueManager.states()) {
            // Prova ad acquisire uno slot atomicamente
            if (!state.tryAcquireSlot()) {
                continue;  // Limite raggiunto
            }

            // Slot acquisito, proviamo a prendere un task
            InvocationTask task = state.poll();
            if (task == null) {
                // Nessun task in coda, rilascia lo slot
                state.releaseSlot();
                continue;
            }

            didWork = true;
            dispatch(state, task);  // dispatch deve chiamare releaseSlot() al completamento
        }
        if (!didWork) {
            sleep(tickMs);
        }
    }
}
```

### Step 3: Assicurarsi che releaseSlot() venga chiamato

Nel `whenComplete()` del dispatch:

```java
dispatcherRouter.dispatchLocal(task).whenComplete((result, error) -> {
    try {
        if (error != null) {
            invocationService.completeExecution(task.executionId(),
                    InvocationResult.error("DISPATCH_ERROR", error.getMessage()));
        } else {
            invocationService.completeExecution(task.executionId(), result);
        }
    } finally {
        state.releaseSlot();  // SEMPRE rilascia lo slot
    }
});
```

## Test da Creare

### Test 1: TryAcquireSlotBasicTest
```java
@Test
void tryAcquireSlot_underLimit_returnsTrue() {
    FunctionQueueState state = new FunctionQueueState("fn", 2, 100);

    assertThat(state.tryAcquireSlot()).isTrue();
    assertThat(state.tryAcquireSlot()).isTrue();
}

@Test
void tryAcquireSlot_atLimit_returnsFalse() {
    FunctionQueueState state = new FunctionQueueState("fn", 2, 100);

    state.tryAcquireSlot();
    state.tryAcquireSlot();

    assertThat(state.tryAcquireSlot()).isFalse();
}

@Test
void releaseSlot_afterAcquire_allowsNewAcquire() {
    FunctionQueueState state = new FunctionQueueState("fn", 1, 100);

    state.tryAcquireSlot();
    assertThat(state.tryAcquireSlot()).isFalse();

    state.releaseSlot();
    assertThat(state.tryAcquireSlot()).isTrue();
}
```

### Test 2: TryAcquireSlotConcurrencyTest (CRITICO)
```java
@Test
void tryAcquireSlot_underConcurrentLoad_neverExceedsLimit() throws Exception {
    int concurrencyLimit = 5;
    FunctionQueueState state = new FunctionQueueState("fn", concurrencyLimit, 100);

    int numThreads = 50;
    AtomicInteger maxConcurrent = new AtomicInteger(0);
    AtomicInteger currentConcurrent = new AtomicInteger(0);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch endLatch = new CountDownLatch(numThreads);

    for (int i = 0; i < numThreads; i++) {
        new Thread(() -> {
            try {
                startLatch.await();
                for (int j = 0; j < 100; j++) {
                    if (state.tryAcquireSlot()) {
                        int concurrent = currentConcurrent.incrementAndGet();
                        maxConcurrent.updateAndGet(max -> Math.max(max, concurrent));

                        // Simula lavoro
                        Thread.sleep(1);

                        currentConcurrent.decrementAndGet();
                        state.releaseSlot();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                endLatch.countDown();
            }
        }).start();
    }

    startLatch.countDown();
    endLatch.await();

    // Il massimo concorrente non deve MAI superare il limite
    assertThat(maxConcurrent.get()).isLessThanOrEqualTo(concurrencyLimit);
}
```

### Test 3: SchedulerRespectsConcirrencyTest
```java
@Test
void scheduler_neverDispatchesMoreThanConcurrencyLimit() throws Exception {
    // Setup: funzione con concurrency=2
    FunctionSpec spec = FunctionSpec.builder()
        .name("fn")
        .concurrency(2)
        .build();
    functionService.register(spec);

    AtomicInteger maxConcurrent = new AtomicInteger(0);
    AtomicInteger currentConcurrent = new AtomicInteger(0);

    // Mock dispatcher che traccia concorrenza
    doAnswer(inv -> {
        int concurrent = currentConcurrent.incrementAndGet();
        maxConcurrent.updateAndGet(max -> Math.max(max, concurrent));
        Thread.sleep(100);  // Simula lavoro
        currentConcurrent.decrementAndGet();
        return CompletableFuture.completedFuture(InvocationResult.success("ok"));
    }).when(dispatcher).dispatch(any());

    // Enqueue 10 task
    for (int i = 0; i < 10; i++) {
        invocationService.invokeAsync("fn", new InvocationRequest("payload" + i));
    }

    // Aspetta che tutti completino
    Thread.sleep(2000);

    // Mai più di 2 concurrent
    assertThat(maxConcurrent.get()).isLessThanOrEqualTo(2);
}
```

## File da Modificare

1. `control-plane/src/main/java/com/nanofaas/controlplane/core/FunctionQueueState.java`
2. `control-plane/src/main/java/com/nanofaas/controlplane/core/Scheduler.java`
3. `control-plane/src/main/java/com/nanofaas/controlplane/core/QueueManager.java` (se decrementa inFlight)
4. `control-plane/src/test/java/com/nanofaas/controlplane/core/FunctionQueueStateTest.java` (nuovo o estendere)

## Criteri di Accettazione

- [ ] `tryAcquireSlot()` è atomico (usa CAS)
- [ ] `releaseSlot()` sempre chiamato dopo dispatch (anche su errore)
- [ ] Test di concorrenza passa con 0 violazioni
- [ ] Metodi deprecati (`canDispatch`, `incrementInFlight`, `decrementInFlight`) rimossi o marcati @Deprecated
- [ ] Nessuna regressione nei test esistenti

## Note di Sviluppo

_Spazio per appunti durante l'implementazione_

### Attenzione: dove viene chiamato decrementInFlight()?

Cercare tutti i punti dove `decrementInFlight()` è chiamato e assicurarsi che diventino `releaseSlot()`:

1. `InvocationService.completeExecution()`?
2. `QueueManager`?
3. Altri punti?

```bash
grep -r "decrementInFlight" control-plane/src/
```

---
