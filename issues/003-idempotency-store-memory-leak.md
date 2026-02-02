# Issue 003: Memory leak in IdempotencyStore - nessun TTL/eviction

**Severità**: ALTA
**Componente**: control-plane/core/IdempotencyStore.java
**Linee**: Tutta la classe

## Descrizione

`IdempotencyStore` memorizza le chiavi di idempotenza in un `ConcurrentHashMap` senza alcun meccanismo di eviction. A differenza di `ExecutionStore` che ha un janitor thread con TTL di 15 minuti, `IdempotencyStore` cresce indefinitamente.

```java
// IdempotencyStore.java - CODICE ATTUALE
@Component
public class IdempotencyStore {
    private final Map<String, IdempotencyEntry> keys = new ConcurrentHashMap<>();

    public record IdempotencyEntry(String executionId, Instant storedAt) {}

    public Optional<String> getExecutionId(String functionName, String idempotencyKey) {
        String key = functionName + ":" + idempotencyKey;
        IdempotencyEntry entry = keys.get(key);
        return entry == null ? Optional.empty() : Optional.of(entry.executionId());
    }

    public void put(String functionName, String idempotencyKey, String executionId) {
        String key = functionName + ":" + idempotencyKey;
        keys.put(key, new IdempotencyEntry(executionId, Instant.now()));
    }

    // MANCA: evictExpired(), shutdown(), TTL configuration
}
```

## Confronto con ExecutionStore

```java
// ExecutionStore.java - HA eviction (corretto)
@Component
public class ExecutionStore {
    private final Duration ttl = Duration.ofMinutes(15);
    private final ScheduledExecutorService janitor = Executors.newSingleThreadScheduledExecutor();

    public ExecutionStore() {
        janitor.scheduleAtFixedRate(this::evictExpired, 1, 1, TimeUnit.MINUTES);
    }

    private void evictExpired() {
        Instant cutoff = Instant.now().minus(ttl);
        records.entrySet().removeIf(entry ->
            entry.getValue().createdAt().isBefore(cutoff));
    }

    @PreDestroy
    public void shutdown() {
        janitor.shutdownNow();
    }
}
```

## Impatto

1. **Memory leak**: La mappa cresce senza limiti
2. **OOM**: In sistemi con molte invocazioni, può esaurire la memoria
3. **Performance degradation**: Lookup su mappa enorme diventa lento
4. **Inconsistenza**: Le chiavi di idempotenza dovrebbero scadere (tipicamente dopo 24h-7d)

## Calcolo dell'Impatto

Assumendo:
- 1000 invocazioni/secondo con idempotency key
- Ogni entry ~200 bytes (chiave stringa + IdempotencyEntry)
- 86400 secondi/giorno

Crescita giornaliera: 1000 * 86400 * 200 = ~17 GB/giorno

Anche con 10 invocazioni/secondo: ~170 MB/giorno = ~5 GB/mese

## Piano di Risoluzione

### Step 1: Leggere il codice esistente
- [ ] Verificare com'è usato IdempotencyStore
- [ ] Capire il ciclo di vita delle chiavi di idempotenza
- [ ] Decidere TTL appropriato (suggerimento: stesso di ExecutionStore, 15 minuti)

### Step 2: Implementare eviction

```java
// IdempotencyStore.java - CODICE CORRETTO
@Component
public class IdempotencyStore {
    private final Map<String, IdempotencyEntry> keys = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final ScheduledExecutorService janitor;

    public record IdempotencyEntry(String executionId, Instant storedAt) {}

    public IdempotencyStore() {
        this(Duration.ofMinutes(15));
    }

    public IdempotencyStore(Duration ttl) {
        this.ttl = ttl;
        this.janitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "IdempotencyStore-Janitor");
            t.setDaemon(true);
            return t;
        });
        janitor.scheduleAtFixedRate(this::evictExpired, 1, 1, TimeUnit.MINUTES);
    }

    public Optional<String> getExecutionId(String functionName, String idempotencyKey) {
        String key = functionName + ":" + idempotencyKey;
        IdempotencyEntry entry = keys.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        // Verifica anche TTL durante lookup
        if (entry.storedAt().plus(ttl).isBefore(Instant.now())) {
            keys.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.executionId());
    }

    public void put(String functionName, String idempotencyKey, String executionId) {
        String key = functionName + ":" + idempotencyKey;
        keys.put(key, new IdempotencyEntry(executionId, Instant.now()));
    }

    private void evictExpired() {
        Instant cutoff = Instant.now().minus(ttl);
        keys.entrySet().removeIf(entry -> entry.getValue().storedAt().isBefore(cutoff));
    }

    @PreDestroy
    public void shutdown() {
        janitor.shutdownNow();
    }

    // Metodo per metriche
    public int size() {
        return keys.size();
    }
}
```

### Step 3: Aggiungere configurazione TTL

```yaml
# application.yml
nanofaas:
  idempotency:
    ttlMinutes: 15
```

```java
@ConfigurationProperties(prefix = "nanofaas.idempotency")
public record IdempotencyProperties(int ttlMinutes) {
    public IdempotencyProperties {
        if (ttlMinutes <= 0) {
            ttlMinutes = 15; // default
        }
    }
}
```

### Step 4: Aggiungere metrica per monitoring

```java
// In Metrics.java
public void registerIdempotencyStoreGauge(IdempotencyStore store) {
    Gauge.builder("nanofaas_idempotency_store_size", store::size)
        .description("Number of entries in idempotency store")
        .register(registry);
}
```

## Test da Creare

### Test 1: IdempotencyStoreBasicTest
```java
@Test
void put_andGet_returnsStoredExecutionId() {
    IdempotencyStore store = new IdempotencyStore(Duration.ofMinutes(15));
    store.put("myFunction", "key123", "exec-456");

    Optional<String> result = store.getExecutionId("myFunction", "key123");

    assertThat(result).hasValue("exec-456");
}

@Test
void get_withUnknownKey_returnsEmpty() {
    IdempotencyStore store = new IdempotencyStore(Duration.ofMinutes(15));

    Optional<String> result = store.getExecutionId("myFunction", "unknown");

    assertThat(result).isEmpty();
}
```

### Test 2: IdempotencyStoreTtlTest
```java
@Test
void get_afterTtlExpired_returnsEmpty() {
    // Usa TTL molto breve per test
    IdempotencyStore store = new IdempotencyStore(Duration.ofMillis(100));
    store.put("myFunction", "key123", "exec-456");

    // Verifica che esiste
    assertThat(store.getExecutionId("myFunction", "key123")).hasValue("exec-456");

    // Aspetta che scada
    Thread.sleep(150);

    // Verifica che è scaduto
    assertThat(store.getExecutionId("myFunction", "key123")).isEmpty();
}
```

### Test 3: IdempotencyStoreEvictionTest
```java
@Test
void evictExpired_removesOldEntries() {
    IdempotencyStore store = new IdempotencyStore(Duration.ofMillis(50));

    // Inserisci entry
    store.put("fn1", "key1", "exec1");
    store.put("fn2", "key2", "exec2");
    assertThat(store.size()).isEqualTo(2);

    // Aspetta scadenza + eviction cycle
    Thread.sleep(200);

    // Forza eviction (o aspetta il janitor)
    // Il janitor gira ogni minuto, quindi per test unitario
    // è meglio chiamare evictExpired() direttamente (rendendolo package-private)

    assertThat(store.size()).isEqualTo(0);
}
```

### Test 4: IdempotencyStoreShutdownTest
```java
@Test
void shutdown_stopsJanitor() {
    IdempotencyStore store = new IdempotencyStore(Duration.ofMinutes(15));

    // Verifica che il janitor è attivo (indirettamente)
    store.put("fn", "key", "exec");

    // Shutdown
    store.shutdown();

    // Non dovrebbe lanciare eccezioni
    // Il thread janitor dovrebbe essere terminato
}
```

### Test 5: IdempotencyStoreMemoryTest (opzionale, stress test)
```java
@Test
@Timeout(value = 30, unit = TimeUnit.SECONDS)
void store_withManyEntries_evictsCorrectly() {
    IdempotencyStore store = new IdempotencyStore(Duration.ofSeconds(1));

    // Inserisci molte entry
    for (int i = 0; i < 10000; i++) {
        store.put("fn", "key" + i, "exec" + i);
    }
    assertThat(store.size()).isEqualTo(10000);

    // Aspetta eviction
    Thread.sleep(2000);

    // Dovrebbe essere vuoto o quasi
    assertThat(store.size()).isLessThan(100);
}
```

## File da Modificare

1. `control-plane/src/main/java/com/nanofaas/controlplane/core/IdempotencyStore.java`
2. `control-plane/src/main/java/com/nanofaas/controlplane/core/Metrics.java` (aggiungere gauge)
3. `control-plane/src/main/resources/application.yml` (aggiungere config)
4. `control-plane/src/test/java/com/nanofaas/controlplane/core/IdempotencyStoreTest.java` (nuovo)

## Criteri di Accettazione

- [ ] IdempotencyStore ha TTL configurabile (default 15 minuti)
- [ ] Janitor thread rimuove entry scadute ogni minuto
- [ ] Lookup restituisce empty per entry scadute
- [ ] Metrica `nanofaas_idempotency_store_size` esposta
- [ ] Shutdown pulito con @PreDestroy
- [ ] Tutti i test passano
- [ ] Nessun memory leak in test di stress

## Note di Sviluppo

_Spazio per appunti durante l'implementazione_

### Considerazione: Caffeine Cache
In alternativa all'implementazione manuale, si potrebbe usare Caffeine:

```java
private final Cache<String, IdempotencyEntry> keys = Caffeine.newBuilder()
    .expireAfterWrite(15, TimeUnit.MINUTES)
    .maximumSize(100_000)
    .recordStats()
    .build();
```

Pro: Più efficiente, testato, con statistiche
Contro: Aggiunge dipendenza

Per MVP, l'implementazione manuale è sufficiente. Caffeine può essere considerato per future ottimizzazioni.

---
