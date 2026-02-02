# Issue 007: Data race tra campi volatile in ExecutionRecord

**Severità**: MEDIA
**Componente**: control-plane/core/ExecutionRecord.java
**Linee**: Tutta la classe

## Descrizione

`ExecutionRecord` usa campi `volatile` multipli che vengono aggiornati in sequenza senza sincronizzazione. Questo causa potenziali "torn reads" dove un lettore può vedere uno stato inconsistente.

```java
// ExecutionRecord.java - CODICE ATTUALE
public class ExecutionRecord {
    private volatile ExecutionState state;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;
    private volatile ErrorInfo lastError;
    private volatile Object output;

    // Setter individuali - non atomici tra loro!
    public void state(ExecutionState state) { this.state = state; }
    public void output(Object output) { this.output = output; }
    public void finishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    // ...
}
```

## Scenario di Data Race

```
Writer Thread (completeExecution)     Reader Thread (getStatus)
───────────────────────────────────   ─────────────────────────
finishedAt = now                      read state → SUCCESS
state = SUCCESS                       read finishedAt → null  // VECCHIO VALORE!
output = result

// Il reader vede: state=SUCCESS ma finishedAt=null
// Stato inconsistente!
```

## Impatto

1. Client vede ExecutionStatus inconsistente
2. `status=SUCCESS` ma `finishedAt=null`
3. `status=ERROR` ma `lastError=null`
4. Difficile da riprodurre e debuggare

## Piano di Risoluzione

### Opzione A: Synchronized (semplice)

```java
public class ExecutionRecord {
    private ExecutionState state;
    private Instant finishedAt;
    private Object output;
    private ErrorInfo lastError;

    public synchronized void complete(Object output) {
        this.finishedAt = Instant.now();
        this.output = output;
        this.state = ExecutionState.SUCCESS;
    }

    public synchronized void fail(ErrorInfo error) {
        this.finishedAt = Instant.now();
        this.lastError = error;
        this.state = ExecutionState.ERROR;
    }

    public synchronized ExecutionSnapshot snapshot() {
        return new ExecutionSnapshot(state, startedAt, finishedAt, output, lastError);
    }
}
```

### Opzione B: Immutable state object (preferita)

```java
public class ExecutionRecord {
    private volatile ExecutionSnapshot snapshot;

    public record ExecutionSnapshot(
        ExecutionState state,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        Object output,
        ErrorInfo lastError
    ) {}

    public ExecutionRecord(InvocationTask task) {
        this.task = task;
        this.snapshot = new ExecutionSnapshot(
            ExecutionState.QUEUED,
            Instant.now(),
            null, null, null, null
        );
    }

    public void markRunning() {
        ExecutionSnapshot current = this.snapshot;
        this.snapshot = new ExecutionSnapshot(
            ExecutionState.RUNNING,
            current.createdAt(),
            Instant.now(),
            null, null, null
        );
    }

    public void complete(Object output) {
        ExecutionSnapshot current = this.snapshot;
        this.snapshot = new ExecutionSnapshot(
            ExecutionState.SUCCESS,
            current.createdAt(),
            current.startedAt(),
            Instant.now(),
            output,
            null
        );
    }

    public void fail(ErrorInfo error) {
        ExecutionSnapshot current = this.snapshot;
        this.snapshot = new ExecutionSnapshot(
            ExecutionState.ERROR,
            current.createdAt(),
            current.startedAt(),
            Instant.now(),
            null,
            error
        );
    }

    // Getter restituisce snapshot immutabile - sempre consistente
    public ExecutionSnapshot snapshot() {
        return this.snapshot;
    }
}
```

### Opzione C: AtomicReference (bilanciata)

```java
public class ExecutionRecord {
    private final AtomicReference<ExecutionSnapshot> snapshotRef;

    public void complete(Object output) {
        snapshotRef.updateAndGet(current -> new ExecutionSnapshot(
            ExecutionState.SUCCESS,
            current.createdAt(),
            current.startedAt(),
            Instant.now(),
            output,
            null
        ));
    }
}
```

### Scelta Raccomandata

**Opzione B** è la migliore perché:
1. Letture sempre consistenti (snapshot immutabile)
2. Nessun lock necessario per letture
3. Pattern idiomatico Java per stato mutabile thread-safe
4. Facile da ragionare

## Test da Creare

### Test 1: ExecutionRecordBasicTest
```java
@Test
void complete_setsAllFieldsConsistently() {
    ExecutionRecord record = new ExecutionRecord(task);
    record.markRunning();

    record.complete("result");

    ExecutionSnapshot snapshot = record.snapshot();
    assertThat(snapshot.state()).isEqualTo(ExecutionState.SUCCESS);
    assertThat(snapshot.finishedAt()).isNotNull();
    assertThat(snapshot.output()).isEqualTo("result");
    assertThat(snapshot.lastError()).isNull();
}

@Test
void fail_setsAllFieldsConsistently() {
    ExecutionRecord record = new ExecutionRecord(task);
    record.markRunning();

    ErrorInfo error = new ErrorInfo("CODE", "message");
    record.fail(error);

    ExecutionSnapshot snapshot = record.snapshot();
    assertThat(snapshot.state()).isEqualTo(ExecutionState.ERROR);
    assertThat(snapshot.finishedAt()).isNotNull();
    assertThat(snapshot.output()).isNull();
    assertThat(snapshot.lastError()).isEqualTo(error);
}
```

### Test 2: ExecutionRecordConcurrencyTest (CRITICO)
```java
@Test
void snapshot_underConcurrentWrites_alwaysConsistent() throws Exception {
    ExecutionRecord record = new ExecutionRecord(task);
    AtomicBoolean inconsistencyFound = new AtomicBoolean(false);
    AtomicBoolean running = new AtomicBoolean(true);

    // Writer thread
    Thread writer = new Thread(() -> {
        while (running.get()) {
            record.markRunning();
            Thread.yield();
            record.complete("result-" + System.nanoTime());
        }
    });

    // Reader threads
    List<Thread> readers = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
        Thread reader = new Thread(() -> {
            while (running.get()) {
                ExecutionSnapshot snapshot = record.snapshot();

                // Verifica consistenza
                if (snapshot.state() == ExecutionState.SUCCESS) {
                    if (snapshot.finishedAt() == null) {
                        inconsistencyFound.set(true);
                        System.err.println("INCONSISTENT: SUCCESS but finishedAt=null");
                    }
                    if (snapshot.output() == null) {
                        inconsistencyFound.set(true);
                        System.err.println("INCONSISTENT: SUCCESS but output=null");
                    }
                }
                if (snapshot.state() == ExecutionState.RUNNING) {
                    if (snapshot.startedAt() == null) {
                        inconsistencyFound.set(true);
                        System.err.println("INCONSISTENT: RUNNING but startedAt=null");
                    }
                }
            }
        });
        readers.add(reader);
    }

    writer.start();
    readers.forEach(Thread::start);

    Thread.sleep(2000);

    running.set(false);
    writer.join();
    for (Thread reader : readers) {
        reader.join();
    }

    assertThat(inconsistencyFound.get())
        .as("No inconsistent snapshots should be observed")
        .isFalse();
}
```

### Test 3: ExecutionRecordStateTransitionsTest
```java
@Test
void stateTransitions_areValid() {
    ExecutionRecord record = new ExecutionRecord(task);

    assertThat(record.snapshot().state()).isEqualTo(ExecutionState.QUEUED);

    record.markRunning();
    assertThat(record.snapshot().state()).isEqualTo(ExecutionState.RUNNING);
    assertThat(record.snapshot().startedAt()).isNotNull();

    record.complete("result");
    assertThat(record.snapshot().state()).isEqualTo(ExecutionState.SUCCESS);
    assertThat(record.snapshot().finishedAt()).isNotNull();
    assertThat(record.snapshot().finishedAt()).isAfterOrEqualTo(record.snapshot().startedAt());
}
```

## File da Modificare

1. `control-plane/src/main/java/com/nanofaas/controlplane/core/ExecutionRecord.java`
2. `control-plane/src/main/java/com/nanofaas/controlplane/core/InvocationService.java` (adattare chiamate)
3. `control-plane/src/main/java/com/nanofaas/controlplane/core/Scheduler.java` (adattare chiamate)
4. `control-plane/src/main/java/com/nanofaas/controlplane/api/InvocationController.java` (adattare letture)
5. `control-plane/src/test/java/com/nanofaas/controlplane/core/ExecutionRecordTest.java` (nuovo)

## Criteri di Accettazione

- [ ] Snapshot sempre consistente (state, finishedAt, output/error)
- [ ] Test di concorrenza passa 100 volte senza inconsistenze
- [ ] Nessun lock necessario per letture
- [ ] API chiara: `markRunning()`, `complete()`, `fail()`
- [ ] Nessuna regressione nei test esistenti

## Note di Sviluppo

_Spazio per appunti durante l'implementazione_

### Attenzione: backward compatibility

Se altri componenti usano i vecchi setter (`record.state(...)`, `record.output(...)`), dovranno essere aggiornati per usare i nuovi metodi.

```bash
grep -r "\.state\(" control-plane/src/main/java/
grep -r "\.output\(" control-plane/src/main/java/
grep -r "\.finishedAt\(" control-plane/src/main/java/
```

---
