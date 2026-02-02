# Issue 010: Scheduler non ha shutdown graceful

**Severità**: BASSA
**Componente**: control-plane/core/Scheduler.java
**Linee**: 44-47

## Descrizione

Il metodo `stop()` dello Scheduler chiama `shutdownNow()` senza aspettare che il thread termini. Inoltre, se ci sono task in-flight, questi vengono abbandonati.

```java
// Scheduler.java - CODICE ATTUALE
@Override
public void stop() {
    running.set(false);
    executor.shutdownNow();  // Non aspetta!
}
```

## Problemi

1. **Nessuna attesa**: `shutdownNow()` ritorna immediatamente senza aspettare che il thread termini
2. **Task in-flight**: I task attualmente in dispatch vengono abbandonati
3. **InterruptedException**: Il thread in sleep viene interrotto bruscamente
4. **Log incompleti**: Shutdown potrebbe avvenire prima che i log siano scritti
5. **Metriche perse**: Counter/gauge potrebbero non essere aggiornati

## Impatto

1. Durante shutdown dell'applicazione, alcune esecuzioni potrebbero rimanere in stato inconsistente
2. Metriche finali potrebbero essere imprecise
3. Log potrebbero essere troncati
4. In-flight requests potrebbero non essere completati

## Piano di Risoluzione

### Step 1: Implementare graceful shutdown

```java
// Scheduler.java - CODICE CORRETTO
@Component
public class Scheduler implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nanofaas-scheduler");
        t.setDaemon(false);  // Non daemon per permettere graceful shutdown
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Duration shutdownTimeout;

    public Scheduler(QueueManager queueManager, InvocationService invocationService,
                     DispatcherRouter dispatcherRouter, Metrics metrics) {
        // ... existing ...
        this.shutdownTimeout = Duration.ofSeconds(30);  // Configurabile
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("Scheduler starting");
            executor.execute(this::loop);
        }
    }

    @Override
    public void stop() {
        stop(null);
    }

    @Override
    public void stop(Runnable callback) {
        log.info("Scheduler stopping, waiting for in-flight tasks...");
        running.set(false);

        try {
            // Prima, aspetta che il loop termini normalmente
            executor.shutdown();

            if (!executor.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("Scheduler did not terminate in time, forcing shutdown");
                executor.shutdownNow();

                // Aspetta ancora un po'
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.error("Scheduler thread did not terminate");
                }
            }
        } catch (InterruptedException ex) {
            log.warn("Shutdown interrupted, forcing...");
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        log.info("Scheduler stopped");

        if (callback != null) {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        // High phase = shutdown later (after controllers, before data stores)
        return Integer.MAX_VALUE - 100;
    }

    private void loop() {
        log.debug("Scheduler loop started");
        while (running.get()) {
            try {
                boolean didWork = processQueues();
                if (!didWork) {
                    sleep(tickMs);
                }
            } catch (Exception ex) {
                log.error("Error in scheduler loop", ex);
                sleep(tickMs);  // Evita tight loop su errori
            }
        }
        log.debug("Scheduler loop exited");
    }

    private boolean processQueues() {
        boolean didWork = false;
        for (FunctionQueueState state : queueManager.states()) {
            if (!running.get()) {
                break;  // Esci presto se in shutdown
            }
            // ... rest of dispatch logic ...
        }
        return didWork;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            // Non loggareo, è normale durante shutdown
        }
    }
}
```

### Step 2: Gestire in-flight tasks durante shutdown

```java
// Opzione: drain mode
private void loop() {
    while (running.get() || hasInFlightTasks()) {
        // Continua a processare finché ci sono task in-flight
        // Ma non accettare nuovi task dopo running=false
        boolean didWork = processQueues();
        if (!didWork) {
            if (!running.get()) {
                break;  // Shutdown e nessun lavoro, esci
            }
            sleep(tickMs);
        }
    }
}

private boolean hasInFlightTasks() {
    return queueManager.states().stream()
        .anyMatch(state -> state.inFlight() > 0);
}
```

### Step 3: Aggiungere configurazione timeout

```yaml
# application.yml
nanofaas:
  scheduler:
    shutdownTimeoutSeconds: 30
```

## Test da Creare

### Test 1: SchedulerGracefulShutdownTest
```java
@Test
void stop_waitsForLoopToTerminate() throws Exception {
    Scheduler scheduler = new Scheduler(...);
    scheduler.start();

    // Verifica che sia running
    assertThat(scheduler.isRunning()).isTrue();

    // Stop
    scheduler.stop();

    // Verifica che sia terminato
    assertThat(scheduler.isRunning()).isFalse();
}
```

### Test 2: SchedulerShutdownWithInFlightTest
```java
@Test
void stop_completesInFlightTasks() throws Exception {
    // Setup: dispatcher che prende 500ms
    AtomicInteger completedTasks = new AtomicInteger(0);
    doAnswer(inv -> {
        Thread.sleep(500);
        completedTasks.incrementAndGet();
        return CompletableFuture.completedFuture(InvocationResult.success("ok"));
    }).when(dispatcher).dispatch(any());

    // Enqueue task
    invocationService.invokeAsync("fn", request);

    // Aspetta che task sia dispatchato
    await().atMost(Duration.ofSeconds(1)).until(() -> completedTasks.get() > 0 || scheduler.isRunning());

    // Stop mentre task è in flight
    scheduler.stop();

    // Task dovrebbe essere completato
    assertThat(completedTasks.get()).isGreaterThanOrEqualTo(1);
}
```

### Test 3: SchedulerShutdownTimeoutTest
```java
@Test
void stop_forcesShutdownAfterTimeout() throws Exception {
    // Setup: dispatcher che blocca per sempre
    doAnswer(inv -> {
        Thread.sleep(Long.MAX_VALUE);
        return CompletableFuture.completedFuture(InvocationResult.success("ok"));
    }).when(dispatcher).dispatch(any());

    // Enqueue task
    invocationService.invokeAsync("fn", request);

    // Stop con timeout corto
    Scheduler scheduler = new Scheduler(..., Duration.ofMillis(100));
    scheduler.start();
    Thread.sleep(50);  // Lascia che il task venga preso

    long startTime = System.currentTimeMillis();
    scheduler.stop();
    long elapsed = System.currentTimeMillis() - startTime;

    // Dovrebbe uscire dopo il timeout, non bloccarsi per sempre
    assertThat(elapsed).isLessThan(5000);
}
```

### Test 4: SchedulerNamedThreadTest
```java
@Test
void scheduler_usesNamedThread() throws Exception {
    Scheduler scheduler = new Scheduler(...);
    scheduler.start();

    // Trova il thread
    Thread schedulerThread = Thread.getAllStackTraces().keySet().stream()
        .filter(t -> t.getName().equals("nanofaas-scheduler"))
        .findFirst()
        .orElse(null);

    assertThat(schedulerThread).isNotNull();

    scheduler.stop();
}
```

## File da Modificare

1. `control-plane/src/main/java/com/nanofaas/controlplane/core/Scheduler.java`
2. `control-plane/src/main/resources/application.yml` (opzionale)
3. `control-plane/src/test/java/com/nanofaas/controlplane/core/SchedulerShutdownTest.java` (nuovo)

## Criteri di Accettazione

- [ ] Thread ha nome "nanofaas-scheduler"
- [ ] `stop()` aspetta che il loop termini (con timeout)
- [ ] In-flight tasks vengono completati durante shutdown (best effort)
- [ ] Timeout configurabile (default 30s)
- [ ] Log informativi durante shutdown
- [ ] Nessun thread appeso dopo stop
- [ ] Test passano

## Note di Sviluppo

_Spazio per appunti durante l'implementazione_

### Verifica: altri componenti con shutdown simili?

```bash
grep -r "shutdownNow\|shutdown()" control-plane/src/main/java/
```

Potrebbero esserci altri ExecutorService che necessitano graceful shutdown:
- ExecutionStore janitor
- Pool dispatcher (se ha thread pool)

---
