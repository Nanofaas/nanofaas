# Issue 002: Race condition nel RateLimiter

**Severità**: ALTA
**Componente**: control-plane/core/RateLimiter.java
**Linee**: 16-27

## Descrizione

Il `RateLimiter` ha una classica race condition TOCTOU (Time-of-Check-Time-of-Use). Il controllo della finestra temporale avviene fuori dal blocco synchronized, permettendo a più thread di resettare il contatore contemporaneamente.

```java
// RateLimiter.java - CODICE ATTUALE (BUGGATO)
public boolean allow() {
    long now = Instant.now().getEpochSecond();
    if (now != windowStartSecond) {           // CHECK fuori dal synchronized!
        synchronized (this) {
            if (now != windowStartSecond) {   // Double-check, ma troppo tardi
                windowStartSecond = now;
                windowCount.set(0);
            }
        }
    }
    return windowCount.incrementAndGet() <= maxPerSecond;  // INCREMENT non atomico con check
}
```

## Scenario di Race Condition

```
Thread A                          Thread B
────────                          ────────
read now = 100
read windowStartSecond = 99
now != windowStartSecond → true   read now = 100
                                  read windowStartSecond = 99
                                  now != windowStartSecond → true
enter synchronized
windowStartSecond = 100           wait for lock...
windowCount.set(0)
exit synchronized
                                  enter synchronized
                                  now != windowStartSecond → false (già 100)
                                  exit synchronized
windowCount.incrementAndGet()=1   windowCount.incrementAndGet()=2
return true                       return true

// FIN QUI OK, ma il problema è più sottile...
```

## Il Vero Problema

Il problema principale è che `windowCount.incrementAndGet()` non è atomico rispetto al check `<= maxPerSecond`:

```
Scenario con maxPerSecond = 1000:

Thread 1-1000: tutti chiamano allow() nello stesso millisecondo
- Tutti vedono windowCount < 1000
- Tutti chiamano incrementAndGet()
- windowCount diventa 1001, 1002, ... 1100
- Ma molti hanno già ritornato true!
```

Inoltre, se due thread entrano nel reset quasi contemporaneamente:
1. Thread A: windowCount = 999, incrementa a 1000, ritorna true
2. Thread B: vede cambio di secondo, resetta a 0
3. Thread A (altra richiesta): incrementa a 1, ritorna true
4. Risultato: 1001 richieste nel secondo precedente!

## Impatto

1. Il rate limit può essere bypassato sotto carico elevato
2. Più richieste del limite configurato possono passare
3. Potenziale DoS se il rate limit è critico per protezione
4. Metriche di rate limiting imprecise

## Piano di Risoluzione

### Step 1: Analisi del codice esistente
- [ ] Leggere RateLimiter.java completamente
- [ ] Identificare tutti i chiamanti di `allow()`
- [ ] Verificare se ci sono test esistenti

### Step 2: Implementare la fix

**Opzione A: Synchronized completo (semplice, meno performante)**
```java
public synchronized boolean allow() {
    long now = Instant.now().getEpochSecond();
    if (now != windowStartSecond) {
        windowStartSecond = now;
        windowCount.set(0);
    }
    return windowCount.incrementAndGet() <= maxPerSecond;
}
```

**Opzione B: AtomicReference con CAS (più performante)**
```java
private final AtomicReference<WindowState> state = new AtomicReference<>(new WindowState(0, 0));

private record WindowState(long second, int count) {}

public boolean allow() {
    long now = Instant.now().getEpochSecond();
    while (true) {
        WindowState current = state.get();
        WindowState next;
        if (current.second() != now) {
            next = new WindowState(now, 1);
        } else if (current.count() >= maxPerSecond) {
            return false;
        } else {
            next = new WindowState(now, current.count() + 1);
        }
        if (state.compareAndSet(current, next)) {
            return next.count() <= maxPerSecond;
        }
        // CAS failed, retry
    }
}
```

**Opzione C: LongAdder per conteggio (bilanciato)**
```java
public boolean allow() {
    long now = Instant.now().getEpochSecond();
    synchronized (this) {
        if (now != windowStartSecond) {
            windowStartSecond = now;
            windowCount.set(0);
        }
    }
    // Il conteggio può ancora sforare leggermente, ma il reset è atomico
    return windowCount.incrementAndGet() <= maxPerSecond;
}
```

### Step 3: Scelta dell'implementazione
Raccomando **Opzione A** per semplicità e correttezza. La performance non è critica dato che:
- Il synchronized è molto breve
- JVM ottimizza bene i lock non contesi
- La correttezza è più importante della performance per un rate limiter

### Step 4: Aggiungere test di concorrenza

## Test da Creare

### Test 1: RateLimiterBasicTest
```java
@Test
void allow_underLimit_returnsTrue() {
    RateLimiter limiter = new RateLimiter(10);
    for (int i = 0; i < 10; i++) {
        assertThat(limiter.allow()).isTrue();
    }
}

@Test
void allow_atLimit_returnsFalse() {
    RateLimiter limiter = new RateLimiter(10);
    for (int i = 0; i < 10; i++) {
        limiter.allow();
    }
    assertThat(limiter.allow()).isFalse();
}
```

### Test 2: RateLimiterWindowResetTest
```java
@Test
void allow_afterWindowReset_allowsAgain() {
    RateLimiter limiter = new RateLimiter(10);
    // Esaurisce il limite
    for (int i = 0; i < 10; i++) {
        limiter.allow();
    }
    assertThat(limiter.allow()).isFalse();

    // Simula passaggio di tempo (richiede clock iniettabile o sleep)
    Thread.sleep(1001);

    assertThat(limiter.allow()).isTrue();
}
```

### Test 3: RateLimiterConcurrencyTest (CRITICO)
```java
@Test
void allow_underConcurrentLoad_neverExceedsLimit() throws Exception {
    int maxPerSecond = 100;
    RateLimiter limiter = new RateLimiter(maxPerSecond);
    int numThreads = 50;
    int requestsPerThread = 10;

    AtomicInteger allowedCount = new AtomicInteger(0);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch endLatch = new CountDownLatch(numThreads);

    for (int i = 0; i < numThreads; i++) {
        new Thread(() -> {
            try {
                startLatch.await();
                for (int j = 0; j < requestsPerThread; j++) {
                    if (limiter.allow()) {
                        allowedCount.incrementAndGet();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                endLatch.countDown();
            }
        }).start();
    }

    startLatch.countDown();  // Start all threads simultaneously
    endLatch.await();

    // Con 50 thread x 10 richieste = 500 richieste
    // Ma limite è 100/secondo, quindi max 100 dovrebbero passare
    assertThat(allowedCount.get()).isLessThanOrEqualTo(maxPerSecond);
}
```

### Test 4: RateLimiterStressTest
```java
@Test
void allow_underHeavyLoad_maintainsCorrectness() throws Exception {
    int maxPerSecond = 1000;
    RateLimiter limiter = new RateLimiter(maxPerSecond);
    int numThreads = 100;
    int durationSeconds = 3;

    AtomicInteger totalAllowed = new AtomicInteger(0);
    AtomicBoolean running = new AtomicBoolean(true);
    List<Thread> threads = new ArrayList<>();

    for (int i = 0; i < numThreads; i++) {
        Thread t = new Thread(() -> {
            while (running.get()) {
                if (limiter.allow()) {
                    totalAllowed.incrementAndGet();
                }
            }
        });
        threads.add(t);
        t.start();
    }

    Thread.sleep(durationSeconds * 1000L);
    running.set(false);

    for (Thread t : threads) {
        t.join();
    }

    // Dovrebbe essere circa maxPerSecond * durationSeconds
    // Con margine per il primo/ultimo secondo parziale
    int expectedMax = maxPerSecond * (durationSeconds + 1);
    assertThat(totalAllowed.get()).isLessThanOrEqualTo(expectedMax);
}
```

## File da Modificare

1. `control-plane/src/main/java/com/nanofaas/controlplane/core/RateLimiter.java`
2. `control-plane/src/test/java/com/nanofaas/controlplane/core/RateLimiterTest.java` (nuovo)

## Criteri di Accettazione

- [ ] Tutti i test unitari passano
- [ ] Test di concorrenza passa con 0 violazioni del limite
- [ ] Test di stress passa per 10 secondi senza sforare
- [ ] Code review conferma assenza di race condition
- [ ] Performance benchmark mostra throughput accettabile (>100k ops/sec)

## Note di Sviluppo

_Spazio per appunti durante l'implementazione_

### Considerazione: Clock iniettabile
Per testare il reset della finestra temporale senza `Thread.sleep()`, considerare:
```java
public class RateLimiter {
    private final Clock clock;

    public RateLimiter(int maxPerSecond) {
        this(maxPerSecond, Clock.systemUTC());
    }

    public RateLimiter(int maxPerSecond, Clock clock) {
        this.maxPerSecond = maxPerSecond;
        this.clock = clock;
    }

    public boolean allow() {
        long now = clock.instant().getEpochSecond();
        // ...
    }
}
```

---
