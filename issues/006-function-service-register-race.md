# Issue 006: Race condition in FunctionService.register()

**Severità**: MEDIA
**Componente**: control-plane/core/FunctionService.java
**Linee**: 29-37

## Descrizione

Il metodo `register()` ha una race condition: due thread possono entrambi verificare che la funzione non esiste e poi registrarla, causando sovrascrittura silenziosa.

```java
// FunctionService.java:29-37 - CODICE ATTUALE (BUGGATO)
public Optional<FunctionSpec> register(FunctionSpec spec) {
    if (registry.get(spec.name()).isPresent()) {  // CHECK
        return Optional.empty();                   // Già esiste
    }
    FunctionSpec resolved = resolver.resolve(spec);
    registry.put(resolved);                        // PUT - RACE!
    queueManager.getOrCreate(resolved);
    return Optional.of(resolved);
}
```

## Scenario di Race Condition

```
Thread A                              Thread B
──────────                            ──────────
register("myFunc", specA)             register("myFunc", specB)
registry.get("myFunc") → empty        registry.get("myFunc") → empty
                                      resolver.resolve(specB)
resolver.resolve(specA)               registry.put(resolvedB)
registry.put(resolvedA)               // specB sovrascritta da specA!
queueManager.getOrCreate(resolvedA)   queueManager.getOrCreate(resolvedB)
return Optional.of(resolvedA)         return Optional.of(resolvedB)
                                      // Entrambi pensano di aver registrato!
```

## Impatto

1. Funzione registrata con spec sbagliata
2. Entrambi i chiamanti pensano di aver registrato con successo
3. Potenziale inconsistenza tra FunctionRegistry e QueueManager
4. Difficile da debuggare

## Piano di Risoluzione

### Step 1: Verificare l'implementazione di FunctionRegistry

```java
// FunctionRegistry.java - verificare
public class FunctionRegistry {
    private final Map<String, FunctionSpec> functions = new ConcurrentHashMap<>();

    public void put(FunctionSpec spec) {
        functions.put(spec.name(), spec);  // Sovrascrive sempre
    }

    // Esiste putIfAbsent()?
}
```

### Step 2: Implementare putIfAbsent atomico

**Opzione A: Aggiungere putIfAbsent a FunctionRegistry**

```java
// FunctionRegistry.java
public Optional<FunctionSpec> putIfAbsent(FunctionSpec spec) {
    FunctionSpec existing = functions.putIfAbsent(spec.name(), spec);
    return existing == null ? Optional.of(spec) : Optional.empty();
}
```

**Opzione B: Usare computeIfAbsent**

```java
// FunctionService.java - CODICE CORRETTO
public Optional<FunctionSpec> register(FunctionSpec spec) {
    FunctionSpec resolved = resolver.resolve(spec);

    AtomicBoolean wasCreated = new AtomicBoolean(false);
    FunctionSpec result = registry.computeIfAbsent(spec.name(), name -> {
        wasCreated.set(true);
        return resolved;
    });

    if (!wasCreated.get()) {
        return Optional.empty();  // Già esisteva
    }

    queueManager.getOrCreate(resolved);
    return Optional.of(result);
}
```

### Step 3: Gestire anche QueueManager atomicamente

Il `queueManager.getOrCreate()` potrebbe essere chiamato due volte se il registro è già thread-safe ma il check non lo è.

```java
// FunctionService.java - versione più robusta
public Optional<FunctionSpec> register(FunctionSpec spec) {
    FunctionSpec resolved = resolver.resolve(spec);

    // putIfAbsent è atomico su ConcurrentHashMap
    FunctionSpec existing = registry.putIfAbsent(resolved);
    if (existing != null) {
        return Optional.empty();  // Già esisteva
    }

    // Solo il thread che ha registrato crea la coda
    queueManager.getOrCreate(resolved);
    return Optional.of(resolved);
}
```

## Test da Creare

### Test 1: RegisterBasicTest
```java
@Test
void register_newFunction_succeeds() {
    FunctionSpec spec = FunctionSpec.builder().name("myFunc").build();

    Optional<FunctionSpec> result = functionService.register(spec);

    assertThat(result).isPresent();
    assertThat(functionService.get("myFunc")).isPresent();
}

@Test
void register_existingFunction_returnsEmpty() {
    FunctionSpec spec = FunctionSpec.builder().name("myFunc").build();
    functionService.register(spec);

    Optional<FunctionSpec> result = functionService.register(spec);

    assertThat(result).isEmpty();
}
```

### Test 2: RegisterConcurrencyTest (CRITICO)
```java
@Test
void register_concurrentSameName_onlyOneSucceeds() throws Exception {
    int numThreads = 10;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch endLatch = new CountDownLatch(numThreads);
    AtomicInteger successCount = new AtomicInteger(0);
    List<FunctionSpec> registeredSpecs = Collections.synchronizedList(new ArrayList<>());

    for (int i = 0; i < numThreads; i++) {
        final int threadId = i;
        new Thread(() -> {
            try {
                startLatch.await();
                FunctionSpec spec = FunctionSpec.builder()
                    .name("myFunc")
                    .image("image-" + threadId)
                    .build();

                Optional<FunctionSpec> result = functionService.register(spec);
                if (result.isPresent()) {
                    successCount.incrementAndGet();
                    registeredSpecs.add(result.get());
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

    // Solo uno deve aver registrato con successo
    assertThat(successCount.get()).isEqualTo(1);
    assertThat(registeredSpecs).hasSize(1);

    // Verificare che la funzione nel registry sia quella registrata
    FunctionSpec registered = functionService.get("myFunc").orElseThrow();
    assertThat(registered.image()).isEqualTo(registeredSpecs.get(0).image());
}
```

### Test 3: RegisterDifferentFunctionsConcurrently
```java
@Test
void register_concurrentDifferentNames_allSucceed() throws Exception {
    int numThreads = 10;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch endLatch = new CountDownLatch(numThreads);
    AtomicInteger successCount = new AtomicInteger(0);

    for (int i = 0; i < numThreads; i++) {
        final int threadId = i;
        new Thread(() -> {
            try {
                startLatch.await();
                FunctionSpec spec = FunctionSpec.builder()
                    .name("func-" + threadId)  // Nomi diversi
                    .build();

                Optional<FunctionSpec> result = functionService.register(spec);
                if (result.isPresent()) {
                    successCount.incrementAndGet();
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

    // Tutti devono aver registrato con successo
    assertThat(successCount.get()).isEqualTo(numThreads);
}
```

### Test 4: RegisterAndQueueCreationConsistencyTest
```java
@Test
void register_alwaysCreatesQueueForRegisteredFunction() throws Exception {
    int numThreads = 5;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch endLatch = new CountDownLatch(numThreads);

    for (int i = 0; i < numThreads; i++) {
        new Thread(() -> {
            try {
                startLatch.await();
                functionService.register(
                    FunctionSpec.builder().name("myFunc").build()
                );
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                endLatch.countDown();
            }
        }).start();
    }

    startLatch.countDown();
    endLatch.await();

    // La funzione esiste
    assertThat(functionService.get("myFunc")).isPresent();

    // La coda esiste
    assertThat(queueManager.getOrCreate(functionService.get("myFunc").get())).isNotNull();
}
```

## File da Modificare

1. `control-plane/src/main/java/com/nanofaas/controlplane/core/FunctionRegistry.java` (aggiungere putIfAbsent)
2. `control-plane/src/main/java/com/nanofaas/controlplane/core/FunctionService.java`
3. `control-plane/src/test/java/com/nanofaas/controlplane/core/FunctionServiceConcurrencyTest.java` (nuovo)

## Criteri di Accettazione

- [ ] `register()` con stesso nome da thread diversi: solo uno riesce
- [ ] `register()` con nomi diversi da thread diversi: tutti riescono
- [ ] Consistenza tra FunctionRegistry e QueueManager
- [ ] Test di concorrenza passa 100 volte senza fallimenti
- [ ] Nessuna regressione nei test esistenti

## Note di Sviluppo

_Spazio per appunti durante l'implementazione_

### Verificare: FunctionRegistry è già thread-safe?

```bash
grep -A 20 "class FunctionRegistry" control-plane/src/main/java/
```

Se usa già `ConcurrentHashMap`, basta usare `putIfAbsent` direttamente.

---
