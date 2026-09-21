# Manual Scheduler Switching Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Conservare gli algoritmi delle due queue esistenti, selezionabili all'avvio e sostituibili manualmente via API senza riavvio, perdita del pending o interruzione delle invocazioni avviate.

**Architecture:** Un `execution-runtime` obbligatorio conserva lifecycle, risorse e pending work; un solo loop usa una delle due strategie, che possiedono esclusivamente indici di ticket. La sostituzione ricostruisce l'indice della destinazione dal pending posseduto dal motore e lo pubblica atomicamente, lasciando invariati i tentativi già impegnati. L'API amministrativa riusa `runtime-config` con controllo della revisione e un percorso di commit che non può annullare retroattivamente dispatch già avviati.

**Tech Stack:** Java 25, Gradle, Spring Boot nella composizione HTTP, JUnit Jupiter e AssertJ/Mockito già presenti, ArchUnit, Micrometer, Caffeine esistente, GraalVM; NanoLab per E2E infrastrutturali. Nessuna nuova dipendenza di produzione.

**Spec:** [Snapshot della specifica](2026-09-16-manual-scheduler-switching-spec.md), [issue #208](https://github.com/miciav/nanofaas/issues/208), `docs/architecture/0001-execution-lifecycle-contract.md` e consuntivo finale della campagna lifecycle/memory. Leggere entrambi i documenti del 16 settembre: il piano precisa scelte operative della specifica.

## Global Constraints

- «Entrambe le strategie esistenti restano supportate.»
- «La scelta è sempre manuale.» Nessun algoritmo nuovo, selettore adattivo, scheduler per funzione o fallback autonomo.
- «Una sola strategia attiva per istanza di control plane»; singolo pod, code in memoria, un thread dedicato alla selezione.
- «senza riavvio e senza richiedere lo svuotamento delle code o la conclusione di tutte le invocazioni»; cambio in entrambe le direzioni.
- «Il cambio non altera capability SYNC/ASYNC, limiti, policy di ammissione o contratti HTTP».
- Java 25; indentazione Java 4 spazi; usare il package reale `it.unimib.datai.nanofaas` del checkout, senza migrare a `com.nanofaas` durante questo lavoro.
- Default retry 3, configurabile; preservare timeout waiter/esecuzione/tentativo, tombstone, deduplicazione e rilascio dopo drain fisico.
- Nessun I/O remoto, handler LOCAL, serializzazione di payload, listener di lifecycle o registrazione di meter sotto il lock degli indici.
- Nessuna implementazione pull, classe offload, autenticazione, storage durevole o nuova infrastruttura E2E.
- Prima di modificare simboli: GitNexus `context`/`impact` sul checkout corrente; avvisare per HIGH/CRITICAL; UNKNOWN resta irrisolto e richiede ricerca testuale. Prima di ciascun commit: `detect-changes --scope all`, senza esiti partial/truncated.
- Ogni task termina con verifica mirata e commit separato, quando i commit sono autorizzati nella sessione esecutiva. Non accorpare refactoring dei record e cambio degli algoritmi.

---

## Stato, prerequisiti e decisioni del piano

Piano scritto sul checkout `8213ae81c0d98de8325a355af6112822e0b21b3f`. Non è stato eseguito. Il registro `docs/experiments/lifecycle-memory-2026-09/STATO.md` consultato arriva a P24 e non prova la chiusura P25: il task 0 deve accertare la conclusione della campagna prima dei cambi di produzione. Eventuali file spostati dalla sua revisione finale vanno rimappati nel piano prima di procedere.

GitNexus è stato interrogato sul repository `/home/michele/Documenti/nanofaas`: segnala un commit di ritardo e risultati ambigui/UNKNOWN. Non è una verifica d'impatto pulita. I riferimenti di questo piano sono confermati leggendo i sorgenti; l'esecutore deve rigenerare l'indice e ripetere le analisi, non riutilizzare un presunto LOW sul costruttore di `ExecutionLifecycle` come analisi della classe.

Decisioni implementative adottate per rendere i task eseguibili:

1. ID pubblici `per-function` e `shared-queue`; nessuna rinomina automatica dei vecchi ID di modulo.
2. Configurazione `nanofaas.scheduler.strategy`. Se manca, profilo legacy async → `per-function`, sync → `shared-queue`; entrambi presenti → `per-function`. Nessun modulo queue → percorso diretto limitato, senza namespace scheduler modificabile.
3. Il profilo ordinario include entrambe le strategie; un artefatto minimale può includerne una sola, e pubblica soltanto gli ID realmente disponibili. Un ID non incluso è rifiutato, senza download di codice a runtime.
4. Override via API valido fino al riavvio. Al riavvio prevale la configurazione di avvio; nessuna persistenza aggiunta. Il GET espone questa semantica.
5. PATCH sincrona: 200 soltanto dopo commit; revisione stale → 409, valori non validi → 422, transizione rifiutata prima del commit → 503. Namespace assente → 404. Riutilizzare envelope e `expectedRevision` esistenti.
6. Ripetere la strategia già attiva è un no-op per indici e worker; una PATCH valida mantiene la convenzione esistente di incremento della revisione runtime-config. Con revisione vecchia resta 409.
7. Due indici al massimo durante il cambio: attivo e candidato. Nessuna copia di payload, future, record o lease; nessuna coda illimitata per eventi della transizione.
8. Ricostruzione iniziale sotto il gate breve del motore: semplice e verificabile. Inserimento, removal e commit di selezione usano lo stesso gate. Niente chiamate a store/record/capacità/listener dal gate. Il task 12 misura la pausa; se supera i limiti prefissati, questa implementazione non supera il gate e va corretta prima della consegna.
9. Un tentativo con dispatch già impegnato al punto di linearizzazione appartiene al lavoro avviato, anche se il trasporto sta ancora pubblicando il proprio handle. Tutte le selezioni impegnate dopo il cambio usano la strategia nuova. Una claim provvisoria non è un dispatch impegnato.
10. La politica di ammissione è una configurazione distinta. Il mapping legacy conserva `:enqueue` disabilitato nel profilo sync, soglie/estimator sync e cap del profilo async, anche quando cambia soltanto la strategia.

## Mappa dei file e responsabilità

I percorsi nuovi seguenti sono proposti da questo piano. Le classi esistenti conservano il package quando vengono spostate di modulo; non cambiare i nomi con sostituzioni testuali.

| Area | File / directory | Responsabilità |
|---|---|---|
| Contratti | `platform/control-plane-spi/src/main/java/it/unimib/datai/nanofaas/controlplane/scheduler/` | Ticket immutabili, factory di indice, controllo amministrativo ristretto. Nessun payload nell'indice. |
| Motore | `platform/execution-runtime/src/main/java/it/unimib/datai/nanofaas/execution/` | Pending autorevole, claim, loop, cambio di indice, wake-up e budget temporanei. |
| Lifecycle estratto | `platform/execution-runtime/src/main/java/it/unimib/datai/nanofaas/controlplane/{execution,capacity,input,service}/` | Classi esistenti trasferite con stessa identità/package; costruzione esplicita e trasporto tramite port. |
| Per funzione | `platform/modules/async-queue/src/main/java/it/unimib/datai/nanofaas/modules/asyncqueue/` | Strategia `PerFunctionSchedulingStrategy`; conservare FIFO per funzione, turni e batch 2. |
| Condivisa | `platform/modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/modules/syncqueue/` | Strategia `SharedQueueSchedulingStrategy`; conservare scansione/rotazione con finestra 64. |
| Composizione | `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/config/SchedulerConfiguration.java` | Un motore, un lifecycle Spring, adapter per ammissione/retry/readiness/trasporto. |
| Configurazione dinamica | `platform/modules/runtime-config/src/main/java/it/unimib/datai/nanofaas/modules/runtimeconfig/` | Revisione e transazione API; nessuna dipendenza dall'implementazione del motore. |
| Osservazioni | `platform/workload-metrics/` e adapter nel control plane | Contatori comuni e rimozione dei meter indipendenti dallo scheduler scelto. |
| Conformità | `platform/execution-runtime/src/test/java/it/unimib/datai/nanofaas/execution/` | Clock e dispatcher controllati, nessun server/container richiesto. |
| Evidenze | `docs/experiments/scheduler-switching-2026-09/` | Baseline, risultati, matrice, avanzamento e comandi effettivamente eseguiti. |

## Contratti condivisi dei task

Creare questi tipi nel task 1, un tipo pubblico per file nello SPI `controlplane.scheduler`:

```java
public record TicketId(String executionId, int attempt) {}

public record SchedulingTicket(
        TicketId id, FunctionGeneration generation, long sequence,
        Instant enqueuedAt, Instant notBefore, Instant queueDeadline) {}

public interface SchedulingStrategy {
    String id();
    SchedulingIndex newIndex();
}

public interface SchedulingIndex {
    void add(SchedulingTicket ticket);
    void remove(TicketId id);
    SchedulingTicket select(Instant now, Predicate<FunctionGeneration> runnable);
    void defer(TicketId id);
    int size();
    void clear();
}

public record SchedulerSelection(String strategy, List<String> available,
                                 String persistence) {}

public interface SchedulerControl {
    SchedulerSelection snapshot();
    void switchTo(String strategy);
}
```

`FunctionGeneration` esiste già nello SPI. `queueDeadline` può essere null se il contratto del profilo non prevede scadenza in coda; `notBefore` è sempre non-null. Validare ID non vuoto, attempt positivo, generazione e timestamp non-null, sequence non negativa. `select` non rimuove il ticket; `defer` applica la rotazione della politica e `remove` è idempotente. Gli indici sono confinati al gate del motore, senza thread, meter o callback proprie. La predicate `runnable` legge soltanto osservazioni in memoria già preparate; non chiama provider, store o registry mutabili.

## Task 0: baseline finale e contratto di transizione

**Files:**
- Read: `docs/experiments/lifecycle-memory-2026-09/STATO.md`, `docs/architecture/0001-execution-lifecycle-contract.md`.
- Create: `docs/architecture/0002-manual-scheduler-switching.md`.
- Create: `docs/experiments/scheduler-switching-2026-09/BASELINE.md`, `STATO.md`, `budgets.json` nella stessa directory.
- Test esistenti: `platform/modules/async-queue/src/test/java/it/unimib/datai/nanofaas/modules/asyncqueue/AsyncQueueInvokeEnqueueContractRegressionTest.java`; `platform/modules/sync-queue/src/test/java/it/unimib/datai/nanofaas/modules/syncqueue/scheduler/SyncSchedulerTest.java`.

**Interfaces:** Consuma specifica e revisione finale della campagna; produce revisione congelata, mapping dei profili e budget usati da tutti i task. Nessun nuovo contratto Java.

- [ ] Accertare P24/P25 e registrare SHA, stato Git e limitazioni reali. Se la campagna non è chiusa, registrare il prerequisito mancante e non iniziare modifiche di produzione. Non trasformare l'esistenza del piano in prova della chiusura.
- [ ] Rigenerare l'indice, poi analizzare simboli e metodi di `ExecutionLifecycle`, `ExecutionStore`, `FunctionCapacityRegistry`, `ExecutionCompletionHandler`, `RuntimeConfigService.update`, `Scheduler` e `SyncScheduler`. Disambiguare con UID/file. Registrare chiamanti, processi e rischio; risolvere UNKNOWN con sorgenti e test.

```bash
git status --short
git rev-parse HEAD
node .gitnexus/run.cjs analyze --index-only
node .gitnexus/run.cjs context ExecutionCompletionHandler --repo .
node .gitnexus/run.cjs impact ExecutionLifecycle --direction upstream --repo .
```

- [ ] Eseguire e archiviare la baseline dei tre profili, separatamente per evitare artefatti del classpath:

```bash
./gradlew :control-plane:test :control-plane-modules:async-queue:test -PcontrolPlaneModules=async-queue,runtime-config --no-parallel --console=plain
./gradlew :control-plane:test :control-plane-modules:sync-queue:test -PcontrolPlaneModules=sync-queue,runtime-config --no-parallel --console=plain
./gradlew :control-plane:test -PcontrolPlaneModules=runtime-config --no-parallel --console=plain
```

Atteso: test verdi, capability documentate. Registrare separatamente test saltati e gate che richiedono container; non chiamarli passati.
- [ ] Scrivere ADR e budget iniziali prima di qualunque confronto. Soglie proposte da questo piano, da congelare sullo stesso host prima delle misure:

```json
{
  "repetitions": 5,
  "maxSteadyP99RegressionPercent": 5,
  "maxUsefulThroughputRegressionPercent": 5,
  "maxCpuPerCompletionRegressionPercent": 10,
  "maxPostGcHeapRegressionPercent": 10,
  "switchBacklogSizes": [0, 100, 1000, 10000],
  "maxSwitchPauseP99Ms": 100,
  "maxSwitchPauseMs": 250,
  "maxSwitchPreparationMs": 2000,
  "maxLiveStrategyIndexes": 2,
  "switchesInSoak": 1000
}
```

I cap count/byte dell'ammissione sono quelli della baseline e non aumentano per far passare il confronto. Le soglie di tempo sono gate di benchmark, non assert wall-clock nei test di concorrenza. Una revisione motivata dei budget va registrata prima di eseguire l'esperimento interessato.
- [ ] Registrare mapping e lock order nell'ADR: il gate degli indici non acquisisce monitor di `ExecutionRecord`, né chiama `DispatchCapacity`, né attende completion. Il retry attuale può entrare dalla completion mentre il record è bloccato: prevedere l'estrazione della pubblicazione fuori da quel monitor nel task 4.
- [ ] Verificare il diff documentale e committare `Record scheduler switching baseline and contract`, dopo il controllo GitNexus richiesto.

## Task 1: modulo del motore e contratti di ticket

**Files:**
- Modify: `settings.gradle`, `platform/control-plane/build.gradle`.
- Create: `platform/execution-runtime/build.gradle`.
- Create nello SPI `platform/control-plane-spi/src/main/java/it/unimib/datai/nanofaas/controlplane/scheduler/`: `TicketId.java`, `SchedulingTicket.java`, `SchedulingStrategy.java`, `SchedulingIndex.java`, `SchedulerSelection.java`, `SchedulerControl.java` con le firme sopra.
- Create: `platform/execution-runtime/src/test/java/it/unimib/datai/nanofaas/execution/SchedulingTicketTest.java`.

**Interfaces:** Consuma `FunctionGeneration`; produce i sei contratti sopra. Il modulo espone il solo SPI come API di compilazione.

- [ ] Scrivere il test prima dei tipi:

```java
@Test void ticketHasNoPayloadAndKeepsAttemptIdentity() {
    var generation = new FunctionGeneration("echo", 1);
    var now = Instant.parse("2026-09-16T10:00:00Z");
    var ticket = new SchedulingTicket(new TicketId("e1", 1), generation,
            0, now, now.plusSeconds(2), now.plusSeconds(30));
    assertThat(ticket.id()).isNotEqualTo(new TicketId("e1", 2));
    assertThat(ticket.notBefore()).isEqualTo(now.plusSeconds(2));
    assertThat(Arrays.stream(SchedulingTicket.class.getRecordComponents())
            .map(RecordComponent::getType)).doesNotContain(
                    InvocationTask.class, InvocationRequest.class, CompletableFuture.class);
}
```

- [ ] Aggiungere il modulo e lanciare il test: deve fallire in compilazione per i tipi nuovi mancanti.

```groovy
// settings.gradle
include('execution-runtime')
project(':execution-runtime').projectDir = file('platform/execution-runtime')
// platform/execution-runtime/build.gradle
plugins { id 'java-library'; id 'io.spring.dependency-management' }
dependencyManagement {
    imports { mavenBom "org.springframework.boot:spring-boot-dependencies:${springBootVersion}" }
}
dependencies {
    api project(':control-plane-spi')
    testImplementation 'org.junit.jupiter:junit-jupiter:6.0.3'
    testImplementation 'org.assertj:assertj-core'
    testImplementation 'org.mockito:mockito-core'
    testImplementation 'com.tngtech.archunit:archunit-junit5:1.5.0'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}
tasks.named('test') { useJUnitPlatform() }
```

```bash
./gradlew :execution-runtime:test --tests '*SchedulingTicketTest' --console=plain
```

- [ ] Implementare i record e le interfacce; costruttori compatti con `Objects.requireNonNull`, ID/attempt/sequence validi; `SchedulerSelection` usa `List.copyOf(available)`. Aggiungere `implementation project(':execution-runtime')` al control plane.
- [ ] Ripetere il test, aggiungere casi null/attempt zero e verificare `:control-plane:compileJava`.
- [ ] GitNexus e commit `Add scheduling contracts and execution runtime module`.

## Task 2: preservare i due algoritmi come indici di ticket

**Files:**
- Create: `platform/modules/async-queue/src/main/java/it/unimib/datai/nanofaas/modules/asyncqueue/PerFunctionSchedulingStrategy.java`.
- Create: `platform/modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/modules/syncqueue/SharedQueueSchedulingStrategy.java`.
- Test: `platform/modules/async-queue/src/test/java/it/unimib/datai/nanofaas/modules/asyncqueue/PerFunctionSchedulingStrategyTest.java`.
- Test: `platform/modules/sync-queue/src/test/java/it/unimib/datai/nanofaas/modules/syncqueue/SharedQueueSchedulingStrategyTest.java`.
- Read: `Scheduler.java`, `FunctionQueueState.java` nel modulo async; `scheduler/SyncScheduler.java`, `sync/SyncQueueService.java` nel modulo sync.

**Interfaces:** Consuma `SchedulingStrategy`/`SchedulingIndex`; produce factory senza worker. Costruttori pubblici senza argomenti; ID esattamente `per-function` e `shared-queue`.

- [ ] Caratterizzare l'ordine attuale con i test esistenti, poi scrivere due test nuovi con tre funzioni, FIFO, batch e funzioni bloccate. Helper locale completo da usare in ciascuna classe:

```java
private static SchedulingTicket ticket(String id, String function, long sequence) {
    Instant now = Instant.parse("2026-09-16T10:00:00Z");
    return new SchedulingTicket(new TicketId(id, 1), new FunctionGeneration(function, 1),
            sequence, now, now, now.plusSeconds(60));
}
@Test void blockedFunctionDoesNotHideReadyWork() {
    SchedulingIndex index = new SharedQueueSchedulingStrategy().newIndex();
    index.add(ticket("a1", "blocked", 0));
    index.add(ticket("b1", "ready", 1));
    var selected = index.select(Instant.parse("2026-09-16T10:00:01Z"),
            generation -> generation.functionName().equals("ready"));
    assertThat(selected.id()).isEqualTo(new TicketId("b1", 1));
    assertThat(index.size()).isEqualTo(2);
    index.remove(selected.id());
    assertThat(index.size()).isEqualTo(1);
}
```

Nel test per-function costruire la sua factory e verificare, con tre ticket A e uno B, il turno dopo due dispatch A. Nel test shared aggiungere 65 ticket bloccati seguiti da uno pronto: una visita scansiona al massimo 64, la rotazione rende raggiungibile quello pronto nelle visite successive.
- [ ] Eseguire i due test: RED per le factory mancanti.

```bash
./gradlew :control-plane-modules:async-queue:test --tests '*PerFunctionSchedulingStrategyTest' --console=plain
./gradlew :control-plane-modules:sync-queue:test --tests '*SharedQueueSchedulingStrategyTest' -PcontrolPlaneModules=sync-queue,runtime-config --console=plain
```

- [ ] Trasferire solo la selezione: per-function usa deque FIFO per `FunctionGeneration` e deque delle generazioni attive con coalescing, batch 2; shared usa deque dei ticket e scansione/rotazione 64. `select` lascia il nodo posseduto dall'indice; `remove` aggiorna la struttura; `defer` conserva le regole di rotazione. Scartare la presenza di un ticket già rimosso senza far ricrescere indici storici.

```java
@Override public String id() { return "shared-queue"; }
// Dentro l'indice shared, nel gate del chiamante:
int visited = 0;
for (SchedulingTicket ticket : queue) {
    if (++visited > 64) break;
    if (!ticket.notBefore().isAfter(now) && runnable.test(ticket.generation())) {
        return ticket;
    }
}
// Applicare qui la stessa rotazione limitata caratterizzata dalla queue attuale.
```

La rotazione concreta sposta in coda al massimo `Math.min(64, queue.size())` nodi mediante `removeFirst`/`addLast`; non acquisisce capacità. `add` rifiuta ID duplicati prima di mutare e `clear` svuota anche mappe di generazioni/ID. Le scadenze definitive e il payload rimangono al motore.
- [ ] Portare i test GREEN e confrontare tracce di selezione vecchia/nuova su un corpus deterministico di publish, block, unblock, dispatch e removal. Una differenza richiede spiegazione e correzione prima della migrazione, senza tuning di batch/finestra.
- [ ] GitNexus e commit `Extract both existing scheduling policies as ticket indexes`.

## Task 3: pending autorevole e prenotazioni di coda

**Files:**
- Create: `platform/execution-runtime/src/main/java/it/unimib/datai/nanofaas/execution/PendingWorkStore.java`.
- Create: `platform/execution-runtime/src/main/java/it/unimib/datai/nanofaas/execution/PendingEntry.java`.
- Create: `platform/execution-runtime/src/test/java/it/unimib/datai/nanofaas/execution/PendingWorkStoreTest.java`.

**Interfaces:** Consuma ticket, `InvocationTask` e lease già acquisiti. Produce `PendingEntry(SchedulingTicket ticket, InvocationTask task)` e `PendingWorkStore(int maxPending)` con `boolean offer(PendingEntry)`, `PendingEntry get(TicketId)`, `PendingEntry claim(TicketId)`, `void abort(TicketId)`, `PendingEntry commit(TicketId)`, `void finishSubmit(TicketId)`, `void requeueSubmit(TicketId)`, `PendingEntry remove(TicketId)`, `List<PendingEntry> snapshotPending()`, `int pendingCount()`, `int claimedCount()`, `int submittingCount()`. Il motore serializza le operazioni; lo store non chiama listener né chiude lease.

- [ ] Scrivere la regressione prenotazione:

```java
@Test void claimStillConsumesItsQueueReservation() {
    var store = new PendingWorkStore(1);
    Instant now = Instant.parse("2026-09-16T10:00:00Z");
    var ticket = new SchedulingTicket(new TicketId("e1", 1),
            new FunctionGeneration("echo", 1), 0, now, now, null);
    assertThat(store.offer(new PendingEntry(ticket, mock(InvocationTask.class)))).isTrue();
    assertThat(store.claim(ticket.id())).isNotNull();
    var second = new SchedulingTicket(new TicketId("e2", 1), ticket.generation(),
            1, now, now, null);
    assertThat(store.offer(new PendingEntry(second, mock(InvocationTask.class)))).isFalse();
    store.abort(ticket.id());
    assertThat(store.pendingCount()).isEqualTo(1);
    assertThat(store.claimedCount()).isZero();
}
```

- [ ] `./gradlew :execution-runtime:test --tests '*PendingWorkStoreTest'`: RED.
- [ ] Implementare `LinkedHashMap<TicketId, PendingEntry>` e due set limitati `claimed` e `submitting`. `offer` controlla il cap sulla mappa completa; `claim` aggiunge soltanto al primo set, `abort` lo rimuove. `commit` sposta la claim in `submitting` mantenendo la entry e la prenotazione. `finishSubmit` elimina entry e prenotazione, `requeueSubmit` toglie lo stato submitting lasciando la entry pending. `snapshotPending` restituisce solo pending, esclusi claimed/submitting, in ordine di sequence. La transizione risolve le claim provvisorie prima dello snapshot e lascia i submitting al motore. Nessun secondo cap payload in questo store: restano autorevoli i budget `InvocationCapacity` e `queuedInputLease`.

```java
public PendingEntry claim(TicketId id) {
    PendingEntry entry = entries.get(id);
    return entry != null && claimed.add(id) ? entry : null;
}
public void abort(TicketId id) { claimed.remove(id); }
public PendingEntry commit(TicketId id) {
    if (!claimed.remove(id)) throw new IllegalStateException("ticket not claimed");
    submitting.add(id);
    return entries.get(id);
}
```

- [ ] Verificare duplicate offer, remove durante claim, abort doppio, commit senza claim, rimozione di generazione vecchia e assenza di release durante snapshot. `remove` elimina pending/claimed e torna la entry per cleanup fuori dal gate; per un submitting ritorna null e il lifecycle gestisce la cancellazione dell'invocazione già impegnata. Un commit dopo removal di una claim non può avviare il trasporto. Un TicketId non può essere ri-offerto finché occupa una prenotazione submitting.
- [ ] GitNexus e commit `Keep pending work and queue reservations in the engine`.

## Task 4: loop unico e protocollo di dispatch

**Files:**
- Create: `platform/execution-runtime/src/main/java/it/unimib/datai/nanofaas/execution/SchedulerEngine.java`, `EngineDispatch.java`, `EngineReadiness.java`, `StrategyRegistry.java`.
- Create: `platform/execution-runtime/src/test/java/it/unimib/datai/nanofaas/execution/SchedulerEngineDispatchTest.java`.
- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/ExecutionCompletionHandler.java` (dispatch e pubblicazione retry).
- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/InvocationEnqueueSupport.java`.
- Test regressioni esistenti: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/InvocationServiceCoreRetryTest.java`, `InvocationServiceRetryQueueFullTest.java` nella stessa directory.

**Interfaces:**

```java
// execution-runtime, package it.unimib.datai.nanofaas.execution
public interface EngineReadiness {
    boolean runnable(FunctionGeneration generation); // sola osservazione locale
}
public interface EngineDispatch {
    DispatchOwnership tryAcquire(SchedulingTicket ticket);
    boolean isCurrent(SchedulingTicket ticket);
    void submit(InvocationTask task); // submit non bloccante; lifecycle comune
    void expired(InvocationTask task);
    void removed(InvocationTask task);
    void rejected(InvocationTask task, Throwable failure);
}
```

`StrategyRegistry(List<SchedulingStrategy>)` espone `SchedulingStrategy require(String id)` e `List<String> ids()` ordinata; rifiuta ID duplicati/vuoti. `SchedulerEngine(PendingWorkStore, StrategyRegistry, String initialStrategy, EngineDispatch, EngineReadiness, Clock, LongSupplier nanoTime)` espone `boolean enqueue(PendingEntry)`, `void remove(TicketId)`, `void signal()`, `void tick()`, `void start()`, `void close()` e implementa `SchedulerControl` nel task 5. Clock e nanoTime hanno ruoli distinti. Nei test si usa `tick()` senza avviare thread.

- [ ] Scrivere test con `SchedulingStrategy`/indice mock, `EngineDispatch` mock e lease mock: selezione senza capacità mantiene il ticket, una submit che lancia restituisce la lease, una quota input esaurita mantiene la prenotazione e reaccoda. Esempio dell'aspettativa sullo stesso ID dopo fallimento capacità:

```java
when(dispatch.tryAcquire(ticket)).thenReturn(null);
engine.enqueue(new PendingEntry(ticket, task));
engine.tick();
assertThat(store.get(ticket.id())).isNotNull();
assertThat(store.claimedCount()).isZero();
verify(dispatch, never()).submit(any());
```

Qui `ticket` è un `SchedulingTicket` costruito come nel task 3, `task` un mock `InvocationTask`, `store` un `PendingWorkStore(4)` e `engine` usa il costruttore sopra con `Clock.fixed(ticket.enqueuedAt(), ZoneOffset.UTC)` e `() -> 0L`; definire queste variabili nel setup del test. L'indice mock ritorna `ticket` da `select`.
- [ ] `./gradlew :execution-runtime:test --tests '*SchedulerEngineDispatchTest'`: RED.
- [ ] Implementare il ciclo in passaggi limitati: osservazioni locali fuori dal gate → selezione/claim sotto gate → verifica record/generazione e acquisizione lease fuori gate → rivalidazione claim/epoch sotto gate → impegno del dispatch → submit fuori gate. Se la claim è stata rimossa, chiudere la lease fuori gate. Se manca capacità, abort e `defer`; se input backpressure, reinserire preservando il posto riservato fino alla decisione finale.

```java
DispatchOwnership lease = dispatch.tryAcquire(ticket); // fuori dal gate
if (lease == null) {
    // Nel gate: store.abort(ticket.id()); activeIndex.defer(ticket.id());
    return;
}
// Nel gate, validare stessa claim, epoch e presenza del ticket.
// Se valida: segnare il tentativo impegnato e rimuovere dall'indice attivo.
// Fuori dal gate:
dispatch.submit(task.withDispatchLease(lease));
```

Il corpo dei tre blocchi usa esattamente `claim/abort/commit` del task 3; per input backpressure il cap di coda rimane riservato fino alla risposta sincrona di submit. Usare la fase submitting e `finishSubmit/requeueSubmit` del task 3; i test devono provare che enqueue concorrenti non sottraggano il posto durante submit. `snapshotPending()` per la migrazione esclude submitting; un eventuale requeue dopo switch entra nell'indice allora attivo, con gli stessi deadline e sequence.
- [ ] Spostare la chiamata a `InvocationEnqueueSupport.enqueueOrThrow` del retry fuori dal monitor del record: preparare il nuovo tentativo sotto monitor, pubblicarlo dopo il rilascio, rivalidare attempt/generazione e concludere la stessa esecuzione se la pubblicazione fallisce. Non introdurre una seconda ammissione utente. Test con latch: completion tiene il record mentre un cambio è pronto; nessun ordine gate→record/record→gate deve formare un ciclo.
- [ ] Un thread dedicato con wake sequence monotona; capacità, enqueue e deadline segnalano l'unico motore. Per-function conserva turni/batch; shared conserva le attese notificabili e la rotazione limitata. Un retry futuro resta in un indice delayed limitato del motore e non nasconde altri ticket; nel profilo attuale senza backoff usare `notBefore=enqueuedAt`, senza introdurre nuove politiche retry.
- [ ] Eseguire suite mirata del motore e regressioni retry; verificare completion immediata, cancel prima dell'handle, callback doppia, remove/re-register, shutdown e avvio parziale fallito. Poi GitNexus e commit `Route scheduling through one bounded engine loop`.

## Task 5: sostituzione atomica dell'indice sotto carico

**Files:**
- Modify: `platform/execution-runtime/src/main/java/it/unimib/datai/nanofaas/execution/SchedulerEngine.java`, `PendingWorkStore.java`.
- Create: `platform/execution-runtime/src/main/java/it/unimib/datai/nanofaas/execution/SchedulerSwitchException.java`.
- Create: `platform/execution-runtime/src/test/java/it/unimib/datai/nanofaas/execution/SchedulerEngineSwitchTest.java`.

**Interfaces:** Produce `SchedulerControl.snapshot()` e `switchTo(String)`. `SchedulerSwitchException` estende `RuntimeException` e distingue preparazione/cap temporaneo/timeout prima del commit. Il motore non possiede la revisione globale runtime-config.

- [ ] Scrivere test con vecchio indice mock, nuovo indice reale o mock e pending noto: prima selezione vecchia, switch con ticket pending, selezione successiva nuova. Una invocazione già in `SUBMITTING` può restare incompleta senza impedire il cambio. Preparare inoltre una factory che fallisce prima dell'attivazione:

```java
when(target.newIndex()).thenThrow(new IllegalStateException("injected build failure"));
var before = engine.snapshot();
assertThatThrownBy(() -> engine.switchTo("shared-queue"))
        .isInstanceOf(SchedulerSwitchException.class);
assertThat(engine.snapshot()).isEqualTo(before);
assertThat(store.get(ticket.id())).isNotNull();
engine.tick();
verify(oldIndex, atLeastOnce()).select(any(), any());
```

Il registry contiene `oldStrategy.id()="per-function"` e `target.id()="shared-queue"`; tutte le factory sono registrate prima della costruzione del motore.
- [ ] `./gradlew :execution-runtime:test --tests '*SchedulerEngineSwitchTest'`: RED.
- [ ] Implementare `switchTo`: validare target prima di toccare il vecchio indice; stessa strategia ritorna subito; impedire nuove claim provvisorie; risolvere al massimo la claim del singolo selector con abort/lease restituita fuori gate; ricostruire il candidato dai ticket pending in ordine di sequence. Recheck timeout monotono e cap temporaneo durante la costruzione. Nessun dispatch dal candidato prima del commit.

```java
SchedulingIndex candidate = target.newIndex();
try {
    for (PendingEntry entry : store.snapshotPending()) {
        candidate.add(entry.ticket());
    }
} catch (RuntimeException failure) {
    try { candidate.clear(); }
    catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
    throw new SchedulerSwitchException("Scheduler preparation failed", failure);
}
// Nel gate; tutte le validazioni precedono questa pubblicazione.
ActiveScheduler next = new ActiveScheduler(target.id(), candidate, active.epoch() + 1);
active = next;
```

Definire nel motore `private record ActiveScheduler(String id, SchedulingIndex index, long epoch) {}` e pubblicarlo in un campo `volatile ActiveScheduler active`. `target` proviene da `registry.require(strategy)` e `store` è il PendingWorkStore del costruttore. Il codice mostrato è nel gate dopo aver risolto le claim; aggiungere il controllo dei budget prima di ciascuna `add`. Il `catch` copre soltanto la preparazione. Dopo il commit non eseguire operazioni fallibili che promettono rollback: vecchio `clear`, meter e notifiche sono cleanup isolato. Ogni uscita precommit rimuove il flag di transizione e risveglia il selector in `finally`; il vecchio indice non è stato mutato.
- [ ] Inserimenti e rimozioni concorrenti prendono il gate e quindi appartengono interamente a prima o dopo il cambio; nessun buffer eventi della transizione. I segnali coalescono per generazione nel motore e non conservano riferimenti alla vecchia strategia. Il motore non prende monitor dei record mentre ricostruisce l'indice.
- [ ] Verificare entrambi i versi, pending delayed, deadline identica, claims, `SUBMITTING` con backpressure dopo switch, enqueue concorrente, errori durante la k-esima add, timeout con clock controllato e switch ripetuti. Snapshot conserva ID e reservation count; massimo due indici vivi, uno dopo commit/abort. Stress misurato nel task 12.
- [ ] GitNexus e commit `Switch scheduling indexes atomically without draining executions`.

## Task 6: commit runtime-config coerente con un cambio irreversibile nel tempo

**Files:**
- Create: `platform/control-plane-spi/src/main/java/it/unimib/datai/nanofaas/controlplane/config/PreparedRuntimeConfigExtension.java`, `PreparedRuntimeConfigChange.java`.
- Modify: `platform/modules/runtime-config/src/main/java/it/unimib/datai/nanofaas/modules/runtimeconfig/RuntimeConfigService.java`.
- Modify: `platform/modules/runtime-config/src/main/java/it/unimib/datai/nanofaas/modules/runtimeconfig/RuntimeConfigRegistry.java`.
- Modify: `platform/modules/runtime-config/src/test/java/it/unimib/datai/nanofaas/modules/runtimeconfig/RuntimeConfigServiceTest.java`.
- Create: `platform/modules/runtime-config/src/main/java/it/unimib/datai/nanofaas/modules/runtimeconfig/SchedulerRuntimeConfigExtension.java`.

**Interfaces:** Consuma `SchedulerControl`; produce le estensioni SPI seguenti. Il percorso legacy `apply/restore` resta valido per le estensioni già presenti.

```java
public interface PreparedRuntimeConfigExtension extends RuntimeConfigExtension {
    PreparedRuntimeConfigChange prepare(Map<String, Object> patch);
}
public interface PreparedRuntimeConfigChange extends AutoCloseable {
    Map<String, Object> snapshotAfterCommit();
    void commit(); // fallimento consentito soltanto prima dell'attivazione
    @Override void close(); // abort/cleanup non lancia e non annulla un commit
}
```

`SchedulerRuntimeConfigExtension(SchedulerControl control)` pubblica namespace `scheduler`, snapshot `{strategy, available, persistence:"restart"}`. Accetta soltanto `{strategy: <ID disponibile>}`. `prepare` valida/precalcola uno snapshot immutabile; `commit` chiama `control.switchTo(target)`; `close` non cambia la selezione. L'estensione legacy `apply` può delegare a prepare/commit; `restore` non viene usata dal servizio per questo tipo di estensione.

- [ ] Scrivere una regressione: un meter che lancia dopo l'attivazione non deve ripristinare l'ID precedente né lasciare revisione vecchia. Altra regressione: se lo snapshot di un altro namespace lancia, `commit()` non viene invocato.

```java
@Test void snapshotFailureOccursBeforeSchedulerCommit() {
    var extension = mock(PreparedRuntimeConfigExtension.class);
    var change = mock(PreparedRuntimeConfigChange.class);
    var other = mock(RuntimeConfigExtension.class);
    when(extension.namespace()).thenReturn("scheduler");
    when(extension.validate(any())).thenReturn(List.of());
    when(extension.prepare(any())).thenReturn(change);
    when(change.snapshotAfterCommit()).thenReturn(Map.of("strategy", "shared-queue"));
    when(other.namespace()).thenReturn("other");
    when(other.snapshot()).thenThrow(new IllegalStateException("snapshot failure"));
    var service = new RuntimeConfigService(new RuntimeConfigRegistry(List.of(extension, other)),
            new SimpleMeterRegistry());
    assertThatThrownBy(() -> service.update(0, "scheduler", Map.of("strategy", "shared-queue")))
            .isInstanceOf(RuntimeConfigApplyException.class);
    verify(change, never()).commit();
    verify(change).close();
}
```

- [ ] `./gradlew :control-plane-modules:runtime-config:test --tests '*RuntimeConfigServiceTest'`: RED; conservare anche i test legacy su restore e snapshot fallito.
- [ ] Aggiungere `RuntimeConfigRegistry.snapshotReplacing(String namespace, Map<String,Object> replacement)` che legge tutti gli altri namespace e usa la mappa preparata per quello in aggiornamento. Nel ramo preparato di `update`, fare tutte le allocazioni/snapshot prima del commit:

```java
try (PreparedRuntimeConfigChange change = extension.prepare(Map.copyOf(patch))) {
    RuntimeConfigSnapshot next = new RuntimeConfigSnapshot(currentRevision + 1,
            registry.snapshotReplacing(namespace, change.snapshotAfterCommit()));
    change.commit();
    revision.set(next.revision());
    return next;
}
```

Il tipo di `extension` in questo ramo è `PreparedRuntimeConfigExtension`. Costruire Timer.Sample e contatori con isolamento degli errori: nessuna eccezione metrica cambia successo/revisione. Il `catch` traduce solo fallimenti prima del commit in `RuntimeConfigApplyException`, senza chiamare `restore` per lo scheduler. Non rifare `registry.snapshot()` dopo il commit. Il lock del servizio serializza update e GET; l'engine non lo acquisisce e non chiama il servizio.
- [ ] Testare: revisione stale senza prepare, target mancante/duplicato, no-op con revisione avanzata una volta, failure nella k-esima add con snapshot/revisione invariati, failure di cleanup non converte il successo in errore. La serializzazione HTTP può fallire dopo un commit: il GET successivo deve mostrare lo stato effettivo; non promettere rollback per una connessione client persa.
- [ ] GitNexus e commit `Make scheduler runtime configuration commit atomic`.

## Task 7: API amministrativa e contratto OpenAPI

**Files:**
- Modify: `platform/modules/runtime-config/src/main/java/it/unimib/datai/nanofaas/modules/runtimeconfig/RuntimeConfigConfiguration.java`.
- Modify: `platform/modules/runtime-config/src/main/java/it/unimib/datai/nanofaas/modules/runtimeconfig/AdminRuntimeConfigController.java`.
- Modify: `platform/modules/runtime-config/openapi.yaml`.
- Create: `platform/modules/runtime-config/src/test/java/it/unimib/datai/nanofaas/modules/runtimeconfig/SchedulerRuntimeConfigIntegrationTest.java`.
- Modify: `docs/control-plane.md`.

**Interfaces:** Produce GET/PATCH `/v1/admin/runtime-config/scheduler` e validate nello schema già esistente. PATCH continua a usare `PatchRequest(Long expectedRevision, Map<String,Object> values)`; GET globale fornisce la revisione. Nessun nuovo endpoint pubblico di esecuzione.

- [ ] Test HTTP con un `SchedulerControl` controllato da latch: PATCH non termina prima dell'attivazione; GET dopo risposta legge target e nuova revisione.

```java
client.patch().uri("/v1/admin/runtime-config/scheduler")
        .bodyValue(Map.of("expectedRevision", 0,
                "values", Map.of("strategy", "shared-queue")))
        .exchange().expectStatus().isOk()
        .expectBody().jsonPath("$.revision").isEqualTo(1)
        .jsonPath("$.effectiveConfig.namespaces.scheduler.strategy").isEqualTo("shared-queue");
```

`client` è un `WebTestClient` con lo stesso setup di `AdminRuntimeConfigIntegrationTest`; registrare il bean `SchedulerRuntimeConfigExtension` e un controllo fake il cui `switchTo` attende un latch prima di aggiornare l'AtomicReference. Nel test che usa latch eseguire PATCH in un executor del test e chiuderlo in `finally`.
- [ ] Eseguire il test RED; coprire anche 400 request incompleta, 404 modulo/capability assenti, 409 revisione stale, 422 ID sconosciuto, 503 preparazione fallita. I test del servizio distinguono assenza di commit da errore di trasporto dopo commit.
- [ ] Registrare l'estensione con `@ConditionalOnBean(SchedulerControl.class)`. Eseguire la PATCH su un executor amministrativo limitato, non sul thread Netty: 1 worker, 1 richiesta in attesa, rifiuto ulteriore 503, shutdown nel contesto Spring. Non aprire una coda illimitata in `boundedElastic`. L'ammissione al worker precede l'applicazione e non promette successo finché la future non termina.

```java
ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(1), Thread.ofPlatform().name("nanofaas-admin-config-", 0).factory(),
        new ThreadPoolExecutor.AbortPolicy());
```

Adattare il controller con `Mono.fromFuture` e una `CompletableFuture` completata dal task eseguito su questo executor. Gestire `RejectedExecutionException` come 503 prima di invocare il servizio. La cancellazione HTTP non avvia un rollback: dopo una risposta persa il client legge revisione e strategia via GET. Non spostare i loop di funzione su questo worker.
- [ ] In OpenAPI aggiungere schema/esempi del namespace, enum `per-function/shared-queue`, `available`, `persistence: restart` e status descritti. Documentare i comandi reali:

```bash
curl -fsS http://localhost:8080/v1/admin/runtime-config
curl -fsS -X PATCH http://localhost:8080/v1/admin/runtime-config/scheduler \
  -H 'Content-Type: application/json' \
  -d '{"expectedRevision":0,"values":{"strategy":"shared-queue"}}'
curl -fsS http://localhost:8080/v1/admin/runtime-config/scheduler
```

Il valore `0` dell'esempio va sostituito con la revisione appena letta. L'endpoint richiede modulo runtime-config e `nanofaas.admin.runtime-config.enabled=true`, come oggi.
- [ ] Test GREEN, verifica `composeControlPlaneOpenApi` e regressioni del controller già esistente. GitNexus e commit `Expose manual scheduler switching through runtime config`.

## Task 8: composizione unica e compatibilità dei profili

**Files:**
- Create: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/config/SchedulerConfiguration.java`, `SchedulerProperties.java`, `SchedulerLifecycleAdapter.java`.
- Create: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/EngineInvocationEnqueuer.java`, `EngineSyncQueueGateway.java`.
- Modify: `platform/modules/async-queue/src/main/java/it/unimib/datai/nanofaas/modules/asyncqueue/AsyncQueueConfiguration.java`.
- Modify: `platform/modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/modules/syncqueue/SyncQueueConfiguration.java`.
- Modify: `platform/modules/async-queue/module.properties`, `platform/modules/sync-queue/module.properties` e `platform/modules/sync-queue/build.gradle`.
- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/InvocationEnqueuerAutoConfiguration.java`, `ReactiveInvocationCoordinator.java`.
- Modify: `platform/gradle-plugin/src/test/java/it/unimib/datai/nanofaas/gradle/RepositoryModuleDescriptorsTest.java`.
- Create: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/SchedulerCompositionTest.java`.

**Interfaces:** Consuma lista delle factory, `SchedulerEngine`, `InvocationEnqueuer`, `RetryScheduler`, `SyncQueueGateway`; produce un solo bean motore, controllo e lifecycle, indipendentemente dal numero di strategie incluse. `EngineInvocationEnqueuer` implementa sia `InvocationEnqueuer` sia `RetryScheduler`; `EngineSyncQueueGateway` mantiene il contratto sync di ammissione e delega allo stesso motore.

- [ ] Scrivere test di contesto con entrambe le factory: un solo `SchedulerEngine`, un solo `RetryScheduler`, nessun bean dei vecchi `Scheduler`/`SyncScheduler`, un unico `WorkloadCapacityController`. Per il caso privo di runtime-config, startup e invocazioni funzionano e l'endpoint non esiste.

```java
assertThat(context.getBeansOfType(SchedulerEngine.class)).hasSize(1);
assertThat(context.getBeansOfType(RetryScheduler.class)).hasSize(1);
assertThat(context.getBeansOfType(SchedulerControl.class)).hasSize(1);
assertThat(context.getBeansOfType(Scheduler.class)).isEmpty();
assertThat(context.getBeansOfType(SyncScheduler.class)).isEmpty();
```

I due vecchi tipi appartengono a package diversi: importarli esplicitamente. Nel test finale dopo la loro rimozione usare l'assenza dei vecchi nomi bean e il contatore dei worker.
- [ ] Eseguire RED con entrambi i moduli: la baseline rifiuta la combinazione o espone bean duplicati.

```bash
./gradlew :control-plane:test --tests '*SchedulerCompositionTest' -PcontrolPlaneModules=async-queue,sync-queue,runtime-config --console=plain
```

- [ ] Le auto-configurazioni dei due moduli registrano soltanto le factory di strategia e gli adapter legacy di configurazione necessari. Spostare worker, subscriber capacità, binder metriche e listener generazioni in `SchedulerConfiguration`. Rimuovere `conflicts` reciproco nei descrittori, impostare entrambi `defaultEnabled=true`; aggiornare il test dei default del plugin. Non toccare i conflitti fra provider Kubernetes/container.

```java
@Bean SchedulingStrategy perFunctionStrategy() { return new PerFunctionSchedulingStrategy(); }
@Bean SchedulingStrategy sharedQueueStrategy() { return new SharedQueueSchedulingStrategy(); }
// SchedulerConfiguration: costruire una sola StrategyRegistry dalla lista delle factory.
```

- [ ] Definire proprietà `strategy`, `max-switch-preparation=PT2S`, `max-switch-pause=PT0.25S`; validazione ID in startup e errore esplicito se strategia assente. **AMENDMENT (Task 13b):** `max-switch-preparation`/`max-switch-pause` non esistono nel codice finale — erano lette da nessuno mentre il budget del motore è la costante compilata `SchedulerEngine.SWITCH_BUDGET_MS`, e cablarle avrebbe reso impostabile a runtime una soglia di misura congelata in `docs/experiments/scheduler-switching-2026-09/budgets.json`; vedere §6 dell'ADR 0002. La compatibilità di prodotto vive in proprietà separate `nanofaas.invocation.async-enabled` e `nanofaas.admission.profile` (`function-queue`, `sync-queue`, `direct`), risolte una volta dai vecchi selettori quando non esplicite. La PATCH scheduler non le modifica.

| Moduli queue inclusi | Default strategia | Default ammissione | Default ASYNC |
|---|---|---|---|
| async soltanto | per-function | function-queue | abilitato |
| sync soltanto | shared-queue | sync-queue | disabilitato |
| entrambi | per-function | function-queue | abilitato |
| nessuno | nessuna queue | direct | disabilitato |

I vecchi flag sync enabled/admissionEnabled restano un mapping del profilo di ammissione; attivare/disattivare quel profilo non deve cambiare automaticamente lo scheduler. Preservare le richieste già accodate quando l'ammissione queued viene disabilitata.
- [ ] Il lifecycle Spring invoca solo `engine.start/close`; `start/stop/start`, contesto parzialmente fallito e shutdown con pending devono chiudere esattamente worker, timer e input posseduti. Rimuovere dal build sync il vecchio assunto che i due moduli non possano stare nello stesso classpath.
- [ ] Eseguire la matrice dei quattro profili, test plugin e suite di invocazione. GitNexus e commit `Compose both scheduler strategies around one engine`.

## Task 9: estrarre store, capacità e input nel runtime obbligatorio

**Files:**
- Move, mantenendo package e nomi, da `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/` alla stessa directory relativa in `platform/execution-runtime/src/main/java/it/unimib/datai/nanofaas/controlplane/`:
  - `execution/ExecutionLifecycle.java`, `ExecutionState.java`, `Outcome.java`, `OutcomeWeigher.java`, `IdempotencyStore.java`, `ExecutionInputResources.java`, `ExecutionStore.java`, `TimeSource.java`, `ExecutionRecord.java`.
  - `capacity/FunctionCapacityRegistry.java`, `GenerationLifecycle.java`, `ResourceQuota.java`, `ReservationBatch.java`, `FunctionCapacityState.java`, `DispatchLease.java`, `GenerationPhase.java`, `ResourceOwner.java`, `InvocationCapacity.java`, `WaiterCapacity.java`, `DispatchAttempt.java`, `RetainedInputLease.java`, `InvocationCapacityProperties.java`.
  - `input/CanonicalInvocationInput.java`, `RetainedInputEstimator.java`, `InvocationInputRejectedException.java`.
  - `config/ExecutionStoreProperties.java`.
- Keep/Modify nel control plane: `execution/ExecutionExpiryConfiguration.java`, `capacity/InvocationCapacityConfiguration.java` e `config/SchedulerConfiguration.java`.
- Modify: `platform/execution-runtime/build.gradle`, `platform/control-plane/build.gradle`.
- Create: `platform/execution-runtime/src/test/java/it/unimib/datai/nanofaas/execution/RuntimeArchitectureTest.java`.
- Move i test puri delle classi spostate, preservando package; mantenere nel control plane quelli che avviano Spring/HTTP.

**Interfaces:** Conservare le firme e i costruttori esistenti; il cambio è di modulo/proprietà della composizione. I record mutabili non entrano nello SPI. Questa è una distinta unità di review dalla selezione dello scheduler.

- [ ] Scrivere un test ArchUnit sul modulo nuovo, prima dello spostamento effettivo, e un test Gradle che vieti dipendenza da `:control-plane`, provider e moduli queue:

```java
noClasses().should().dependOnClassesThat().resideInAnyPackage(
        "org.springframework..", "io.fabric8..",
        "it.unimib.datai.nanofaas.modules..")
        .check(new ClassFileImporter().importPackages(
                "it.unimib.datai.nanofaas.execution",
                "it.unimib.datai.nanofaas.controlplane.execution",
                "it.unimib.datai.nanofaas.controlplane.capacity",
                "it.unimib.datai.nanofaas.controlplane.input"));
```

Eseguire il test con le classi trasferite prima di rimuovere annotazioni: RED deve mostrare la dipendenza Spring reale, non essere verde perché il package importato è vuoto. Assert aggiuntivo sul numero di classi importate e presenza di `ExecutionRecord`.
- [ ] Eseguire gli impact delle classi e dei costruttori prima di spostarle. Se necessario usare il refactoring GitNexus per i cambi di simbolo; per un puro cambio di modulo mantenere i fully qualified name.
- [ ] Spostare le classi elencate. Eliminare injection/`@Component`/`@PreDestroy` dal runtime e costruire esplicitamente i bean nel control plane. `ExecutionStoreProperties` diventa record puro: binding nel control plane tramite un bean di proprietà mutabile separato `ExecutionStoreBindingProperties` che produce il record con i sei campi attuali. `InvocationCapacityProperties` resta POJO puro: bean `@ConfigurationProperties` nel metodo di configurazione. Mantenere default, validazioni e chiavi pubbliche.

```java
@Bean
@ConfigurationProperties(prefix = "nanofaas.invocation-capacity")
InvocationCapacityProperties invocationCapacityProperties() {
    return new InvocationCapacityProperties();
}
```

Creare `ExecutionStoreBindingProperties.java` nello stesso package config del control plane con `Duration ttl/maxLifetime/syncTtl` e `long maxOutcomes/maxKeys/maxOutcomeBytes`, getter/setter e `ExecutionStoreProperties toRuntime()` che invoca il record esistente. Nessun default duplicato: normalizzazione nel costruttore del record.
- [ ] Rendere `InvocationInputRejectedException` un'eccezione di dominio (`RuntimeException`) e mantenere il mapping HTTP precedente nel control plane. Non introdurre Spring Web nel runtime per conservare la vecchia superclass. Migrare i test dello status/errore senza cambiarne l'aspettativa.
- [ ] Aggiungere al runtime soltanto Caffeine/Micrometer già usati dai componenti estratti, con versioni dal BOM; nessun nuovo framework. Registrazione/rimozione dei meter dello scheduler resta nell'adapter comune del task 11. Registrare `destroyMethod` per i componenti con risorse, mantenendo esattamente il contratto di shutdown precedente.
- [ ] Eseguire `:execution-runtime:test`, i test R1–R8 interessati nel control plane e i test native hints. Verificare assenza di classi duplicate fra i JAR e nessuna dipendenza inversa. GitNexus e commit `Move execution ownership into the mandatory runtime library`.

## Task 10: tentativi, retry e ammissione attraverso il motore comune

**Files:**
- Modify/Extract: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/ExecutionCompletionHandler.java`, `InvocationExecutionFactory.java`, `ReactiveInvocationCoordinator.java`.
- Create: `platform/execution-runtime/src/main/java/it/unimib/datai/nanofaas/execution/AttemptCoordinator.java`, `AttemptTransport.java`, `AttemptHandle.java`.
- Create: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/dispatch/AttemptTransportAdapter.java`.
- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/EngineInvocationEnqueuer.java`, `EngineSyncQueueGateway.java`.
- Move: `platform/modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/modules/syncqueue/sync/WaitEstimator.java` e `SyncQueueAdmissionController.java` verso `platform/execution-runtime/src/main/java/it/unimib/datai/nanofaas/execution/admission/`, usando rename/refactor GitNexus e aggiornando i consumer.
- Move/Adapt: `SyncQueueAdmissionResult.java` dallo stesso package sync; consumare l'esistente `SyncQueueConfigSource` dello SPI.
- Create: `platform/execution-runtime/src/test/java/it/unimib/datai/nanofaas/execution/AttemptCoordinatorTest.java`, `AdmissionStrategyIndependenceTest.java`.

**Interfaces:** `AttemptCoordinator(ExecutionStore store, FunctionCapacityRegistry capacity, RetryScheduler retry, AttemptTransport transport, TimeSource time, AttemptObserver observer)` espone `void dispatch(InvocationTask task)`, `void dispatchDirect(InvocationTask task)`, `void completeExecution(String executionId, DispatchResult result, Integer attempt)`, `void completeOffloadedExecution(String executionId, InvocationResult result)` e `void failOffloadedExecution(String executionId, OffloadFailedException failure)`. Conservare le facade e overload esistenti nel control plane. `AttemptTransport` è API interna del runtime per l'adapter del control plane, non un'estensione pubblica del prodotto.

```java
public interface AttemptTransport {
    AttemptHandle submit(InvocationTask task);
}
public record AttemptHandle(CompletableFuture<DispatchResult> outcome,
                            CompletableFuture<Void> drained,
                            Future<?> cancellation) {}
```

Spostare `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/dispatch/DispatchResult.java` nel runtime mantenendo package e campi: `result`, `coldStart`, `initDurationMs`. Creare `AttemptObserver` nel runtime con queste firme; l'adapter Micrometer resta nel control plane e conserva i nomi/semantica dei meter attuali:

```java
public interface AttemptObserver {
    void submitted(InvocationTask task);
    void retried(InvocationTask task);
    void completed(InvocationTask task, DispatchResult result,
                   long queueWaitNanos, long serviceNanos);
    void terminal(InvocationTask task, InvocationResult result, long endToEndNanos);
}
```

Le notifiche sono best-effort, fuori dai lock e recintate dalla generazione corrente nel motore. `terminal` parte una sola volta dalla transizione condivisa, anche per queue expiry, offload o rimozione. Waiter/replay/ammissione mantengono le proprie osservazioni nel coordinatore di ingresso. Non esporre `ExecutionRecord` all'adapter di trasporto.

- [ ] Scrivere test senza Spring: outcome scade ma raw drain non è completato; lo slot e l'input restano posseduti. Il cambio scheduler e la cancellazione del waiter non li liberano. Completion duplicata e retry non rilasciano la lease del tentativo successivo.

```java
@Test void logicalOutcomeDoesNotReleasePhysicalOwnership() {
    var store = new ExecutionStore();
    var capacity = new FunctionCapacityRegistry();
    var spec = new FunctionSpec("fn", "test-image", null, null, null,
            30000, 1, 10, 0, null, ExecutionMode.LOCAL, null, null, null);
    var task = new InvocationTask("e1", "fn", spec, new InvocationRequest("payload", null),
            null, null, Instant.now(), 1, InvocationKind.SYNC);
    store.put(new ExecutionRecord("e1", task));
    capacity.register("fn", 1);
    var lease = capacity.tryAcquireLease(capacity.activeGeneration("fn"), ignored -> {});
    var outcome = new CompletableFuture<DispatchResult>();
    var drained = new CompletableFuture<Void>();
    AttemptTransport transport = ignored -> new AttemptHandle(outcome, drained, mock(Future.class));
    var coordinator = new AttemptCoordinator(store, capacity, RetryScheduler.unavailable(),
            transport, TimeSource.system(), mock(AttemptObserver.class));
    coordinator.dispatch(task.withDispatchLease(lease));
    outcome.complete(DispatchResult.warm(InvocationResult.success("ok")));
    assertThat(lease.isReleased()).isFalse();
    assertThat(capacity.inFlight("fn")).isEqualTo(1);
    drained.complete(null);
    assertThat(lease.isReleased()).isTrue();
    assertThat(capacity.inFlight("fn")).isZero();
}
```

Il test usa coordinatore, store e capacità reali. Affiancare nel test di conformità un cambio scheduler fra outcome e drained, verificando le stesse due assert sulla lease. Mantenere inoltre i test di drain/timeout già presenti, senza cambiarne i risultati attesi; chiudere gli executor/cache della fixture con i metodi di lifecycle effettivi delle classi estratte.
- [ ] Portare RED i test di indipendenza: impostare profilo sync, cambiare solo strategia, verificare invariati 501 di `:enqueue`, soglie, Retry-After e stima; profilo async resta ASYNC anche passando a shared.
- [ ] Spostare lo stato macchina dei tentativi e i retry dal completion handler nel `AttemptCoordinator`; `ExecutionCompletionHandler` diventa facade Spring per callback e API esistenti. `AttemptTransportAdapter` contiene gli accessi a `DispatcherRouter` e `DeploymentReadiness`: nessun GET/provider nel selector. Preservare cancel-before-handle, risposta immediata e drain separato.
- [ ] Entrambe le API di ingresso pubblicano nel medesimo pending store dopo ammissione. Il retry non ripassa per le quote di nuova esecuzione, ma riserva il pending con i limiti esistenti. Un retry fallito termina la stessa esecuzione secondo il contratto attuale. La policy sync valuta la profondità nello scope documentato; un'osservazione mancante non diventa zero. Non cambiare l'algoritmo dell'estimator mentre si sposta.
- [ ] Percorso diretto: stessi record/lease/cap obbligatori, nessuna queue illimitata. Con lavoro pending eleggibile della stessa classe/capacità non permettere sorpassi sistematici; rifiutare secondo il contratto di saturazione se non si può impegnare subito il dispatch. Offload in uscita conserva budget input/future ma non acquisisce una lease locale; ingresso offloaded usa il percorso corrente senza nuove classi di traffico.
- [ ] Eseguire test di tentativo, retry, offload e quota HTTP; verificare che waiter SYNC e polling ASYNC osservino lo stesso risultato anche attraverso due cambi. GitNexus e commit `Reuse execution lifecycle across scheduler strategies`.

## Task 11: osservazioni comuni, controller e cleanup

**Files:**
- Create: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/config/EngineWorkloadMetricsSource.java`.
- Modify: `platform/modules/async-queue/src/main/java/it/unimib/datai/nanofaas/modules/asyncqueue/AsyncQueueWorkloadMetricsSource.java`, `QueueManager.java`.
- Modify: `platform/modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/modules/syncqueue/SyncQueueWorkloadMetricsSource.java`, `sync/SyncQueueMetrics.java`.
- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/config/SchedulerConfiguration.java`.
- Create: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/EngineWorkloadMetricsTest.java`.
- Modify: `docs/control-plane.md`.
- Test: `platform/modules/build-metadata/src/test/java/it/unimib/datai/nanofaas/modules/buildmetadata/BuildMetadataProviderTest.java`; verifica che la lista moduli generata includa entrambe le strategie. Il formato di `BuildMetadataProvider.java` e `BuildMetadata.java` resta sufficiente: non aggiungere uno stato attivo mutabile ai metadata di build.

**Interfaces:** Produce un solo `WorkloadMetricsSource`/`WorkloadMetricsBinder` già definiti in `platform/workload-metrics`; `SchedulerEngine` aggiunge `EngineQueueSnapshot snapshotQueues()` con record immutabile di total pending, claimed, delayed e per-generation counts. Nessun ID esecuzione/chiave/generazione diventa tag Prometheus.

- [ ] Test iniziale: 3 pending, 1 claim, 1 tentativo attivo; switch non deve duplicare popolazioni. Registrare e rimuovere 100 generazioni con stesso nome: meter e signal-set ritornano alla baseline, mentre un vecchio tentativo ancora fisicamente attivo conserva la propria lease fino al drain.

```java
var before = engine.snapshotQueues();
engine.switchTo("shared-queue");
assertThat(engine.snapshotQueues().pending()).isEqualTo(before.pending());
assertThat(registry.find("scheduler_active").gauges()).hasSize(2);
assertThat(registry.find("scheduler_active").tag("strategy", "shared-queue").gauge().value())
        .isEqualTo(1);
```

Creare `EngineQueueSnapshot.java` nel package `it.unimib.datai.nanofaas.execution` con firma unica:

```java
public record EngineQueueSnapshot(int pending, int claimed, int submitting,
                                  int delayed, Map<FunctionGeneration, Integer> perGeneration) {
    public EngineQueueSnapshot { perGeneration = Map.copyOf(perGeneration); }
}
```

`pending` include ready e delayed ma esclude claimed/submitting; `delayed` è un sottoinsieme e non si somma una seconda volta. Le prenotazioni di coda sono `pending + claimed + submitting`. I tentativi già impegnati fanno parte della popolazione execution running: non sommare `submitting` a running per dedurre il numero di esecuzioni. Esporre queste due viste con nomi distinti e documentare la sovrapposizione della fase di submit.
- [ ] Eseguire RED, poi mantenere contatori aggiornati nei punti di mutazione e snapshot immutabili; il binder legge conteggi, non scansiona il backlog ad ogni scrape. L'autoscaler/governor legge l'unica fonte attiva e modifica la capacità core comune.
- [ ] Esporre due gauge `scheduler_active{strategy=...}` e contatore esiti cambio/durata, con cardinalità limitata. Misurare pausa selezione e memoria degli indici separatamente dal payload e dalla RSS. Il listener del cambio non è parte della transazione di correttezza.
- [ ] Registrare listener capacità/lifecycle una sola volta sul motore; le strategie non ne registrano. Rimuovere i meter storici per funzione quando la relativa generazione termina il drain. Dichiarare entrambi gli ID disponibili nei metadata dell'artefatto; la strategia attiva è informazione runtime, non un dato di build immutabile.
- [ ] Eseguire suite governor/autoscaler con entrambe le strategie, cap ridotto sotto in-flight, readiness stale/unavailable e metriche di offload. GitNexus e commit `Observe the common engine across scheduler changes`.

## Task 12: suite comune, race e benchmark della transizione

**Files:**
- Create: `platform/execution-runtime/src/test/java/it/unimib/datai/nanofaas/execution/SchedulerConformanceTest.java`, `SchedulerSwitchRaceTest.java`, `SchedulerModelTest.java`.
- Modify: `platform/execution-runtime/build.gradle` (sole dipendenze test dalle due strategie).
- Create: `docs/experiments/scheduler-switching-2026-09/SchedulerSwitchBenchmark.java`, `run.sh`, `RESULTS.md`.
- Update: `docs/experiments/scheduler-switching-2026-09/STATO.md`.

**Interfaces:** Consuma le factory reali e il motore reale. Produce matrice comune parametrizzata, corpus di eventi e misure replicabili; nessuna nuova API di produzione.

- [ ] Aggiungere ai soli test del runtime `testImplementation project(':control-plane-modules:async-queue')` e `testImplementation project(':control-plane-modules:sync-queue')`. Evitare dipendenze produzione inverse; disattivare transitive test fixtures che avviino Spring.
- [ ] Parametrizzare ogni famiglia di conformità:

```java
static Stream<Arguments> strategies() {
    return Stream.of(
            Arguments.of(new PerFunctionSchedulingStrategy()),
            Arguments.of(new SharedQueueSchedulingStrategy()));
}
@ParameterizedTest @MethodSource("strategies")
void removingATicketMakesItUnselectable(SchedulingStrategy strategy) {
    var index = strategy.newIndex();
    Instant now = Instant.parse("2026-09-16T10:00:00Z");
    var ticket = new SchedulingTicket(new TicketId("one", 1),
            new FunctionGeneration("echo", 1), 1, now, now, null);
    index.add(ticket);
    index.remove(ticket.id());
    assertThat(index.select(now, generation -> true)).isNull();
}
```

- [ ] Aggiungere test engine per tutte le righe della sezione 10 della specifica: modalità API, quote/replay, claim, deadline/retry, generazioni, progresso, risorse, errori strategia, integrazioni, cambio e API. Riutilizzare R1–R8; non riscrivere test HTTP come soli mock dei nuovi metodi.
- [ ] Forzare con barrier questi interleaving: claim→remove→commit; claim→switch→lease acquired; switch build→enqueue; switch build→deadline; switch commit→completion vecchia→retry; reduce capacity→commit; snapshot runtime-config fallito→nessun cambio; client disconnect→commit→GET. Assert su multiset di TicketId, numero di submit per tentativo, risorse detenute e risultato globale, non sui soli flag interni. Timeout dei test solo come guardia anti-deadlock, senza `sleep` per dimostrare l'ordine.
- [ ] Model test con `Random(208L)` e 10.000 operazioni fra enqueue/remove/tick/advanceClock/changeCapacity/switch/complete; modello di riferimento con mappa dei tentativi e set dei terminali. Registrare seed e sequenza al fallimento. Non confrontare l'ordine fra algoritmi diversi: confrontare conservazione del lavoro, idempotenza e invarianti; FIFO/fairness si verificano nei test specifici.
- [ ] Eseguire la suite:

```bash
./gradlew :execution-runtime:test -PcontrolPlaneModules=async-queue,sync-queue,runtime-config --no-parallel --console=plain
./gradlew :control-plane:test :control-plane-modules:runtime-config:test -PcontrolPlaneModules=async-queue,sync-queue,runtime-config --no-parallel --console=plain
```

- [ ] Implementare harness standalone con clock reale per misure, dispatcher controllato e stessi workload della sezione 11 della specifica. Ogni campione scrive JSON con SHA, JVM/native, strategia iniziale/finale, pending al cambio, rate offerto/ammesso, completati utili, p50/p95/p99 per funzione, pausa cambio, CPU, allocazioni, heap post-GC e nodi degli indici. Misurare no-change e entrambe le direzioni; 5 ripetizioni alternate e warm-up identico.

```java
long started = System.nanoTime();
engine.switchTo(targetId);
long elapsed = System.nanoTime() - started;
// La pausa effettiva è registrata separatamente dal motore; elapsed include la richiesta.
System.out.printf(Locale.ROOT,
        "{\"target\":\"%s\",\"elapsedNanos\":%d,\"pending\":%d}%n",
        targetId, elapsed, engine.snapshotQueues().pending());
```

Creare task Gradle `printTestClasspath` nel runtime come quelli esistenti, compilare l'harness con quel classpath e rendere `run.sh` capace di eseguire tutti i backlog del `budgets.json`. Nessuna nuova libreria benchmark obbligatoria. Il confronto include i vecchi artefatti congelati dal task 0; evitare shadow dispatch degli effetti reali.
- [ ] Verificare i budget congelati, incluso massimo due indici e ritorno alla baseline dopo 1.000 cambi. Se le differenze sono entro la dispersione, dichiarare risultato non distinguibile e conservare entrambi gli scheduler. Se la ricostruzione sotto gate supera la pausa massima, fermare la consegna e correggere il protocollo con una nuova prova prima di alzare qualunque soglia.
- [ ] GitNexus e commit `Verify scheduler switching races and performance budgets`.

## Task 13: deployment, native, E2E e rimozione del wiring superato

**Files:**
- Modify: `platform/control-plane/src/main/resources/application.yml`.
- Modify: `deploy/helm/nanofaas/values.yaml`, `deploy/helm/nanofaas/templates/control-plane-deployment.yaml`, `deploy/compose/compose.yaml`.
- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/config/InvocationLifecycleRuntimeHints.java`.
- Modify: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/architecture/CoreArchitectureTest.java`, `CoreArchitectureSourceTest.java`.
- Create: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/SchedulerSwitchHttpTest.java`.
- Modify: `docs/control-plane.md`, `docs/architecture/0002-manual-scheduler-switching.md`.
- Create: `docs/experiments/scheduler-switching-2026-09/NANOLAB.md`, `FINAL.md`.
- Remove dopo migrazione completa: vecchi loop `platform/modules/async-queue/src/main/java/it/unimib/datai/nanofaas/modules/asyncqueue/Scheduler.java` e `platform/modules/sync-queue/src/main/java/it/unimib/datai/nanofaas/modules/syncqueue/scheduler/SyncScheduler.java`; eventuali facade ancora consumate rimangono finché migrati i consumer. Le due nuove strategie restano.

**Interfaces:** Produce configurazione Helm/Compose, artefatto JVM/native con entrambe le strategie e contratto documentato. E2E infrastrutturali eseguiti da NanoLab, senza provisioning in NanoFaaS.

- [ ] Scrivere `SchedulerSwitchHttpTest`: backend controllabile tiene una invocazione attiva e almeno due pending, PATCH cambia strategia, ID e risultato delle richieste restano gli stessi, poi nuovo cambio inverso. Ripetere con `:invoke`, `:enqueue`, polling e una chiave condivisa. Con profilo legacy sync ASYNC continua a dare 501. Non usare richieste a tempo illimitato nei test.
- [ ] Test configurazione: `NANOFAAS_SCHEDULER_STRATEGY=shared-queue` avvia quella strategia, ID sconosciuto impedisce startup, PATCH→riavvio ripristina configurazione iniziale. Documentare `available` e il requisito dei due moduli nello stesso artefatto.

```yaml
# application.yml: proprietà di selezione separate dai limiti/ammissione.
nanofaas:
  scheduler:
    strategy: ${NANOFAAS_SCHEDULER_STRATEGY:}
    max-switch-preparation: PT2S
    max-switch-pause: PT0.25S
```

**AMENDMENT (Task 13b):** nel codice finale restano solo `strategy: ${NANOFAAS_SCHEDULER_STRATEGY:}`; le due chiavi di budget sono state cancellate (nessun lettore, soglia congelata in `budgets.json`, non impostabile a runtime) — vedere §6 dell'ADR 0002.

Stringa vuota significa mapping legacy del task 8; non è un terzo scheduler. Helm espone `controlPlane.scheduler.strategy`; Compose passa la variabile. L'abilitazione dell'API amministrativa continua a essere esplicita, senza aggiungere autenticazione fuori scope.
- [ ] Aggiornare hint native per i nuovi DTO/configurazioni e i componenti spostati; strategie registrate come bean statici, nessuna scansione di plugin/reflection dinamica. Verificare architettura: strategie dipendono dallo SPI, non da store/runtime mutabili; runtime non dipende da Spring Web/provider/application JAR.
- [ ] Dopo impact completo, eliminare vecchi worker, pool e binder duplicati. I test che citano classi obsolete vanno portati sul comportamento equivalente, senza eliminare regressioni di wake-up, quote, retry, churn o cap ridotti. `detect-changes` deve coprire tutte le cancellazioni e i consumer.
- [ ] Eseguire i gate finali JVM e native con profilo entrambe le queue; usare provider separati nelle rispettive verifiche:

```bash
./gradlew test -PcontrolPlaneModules=async-queue,sync-queue,runtime-config --no-parallel --console=plain
./gradlew :control-plane:bootJar :control-plane:composeControlPlaneOpenApi -PcontrolPlaneModules=async-queue,sync-queue,runtime-config --console=plain
./gradlew :control-plane:nativeCompile -PcontrolPlaneModules=async-queue,sync-queue,runtime-config --console=plain
./gradlew -p platform/gradle-plugin test --console=plain
helm lint deploy/helm/nanofaas
helm template nanofaas deploy/helm/nanofaas --set controlPlane.scheduler.strategy=shared-queue
```

Per native usare la versione GraalVM del repository. Eseguire `SchedulerSwitchHttpTest` anche come contratto black-box contro il binario native con entrambe le strategie: un `nativeCompile` verde da solo non verifica il cambio. Registrare comandi di avvio, PID e SHA dell'artefatto, poi pulire solo i processi di questa verifica.
- [ ] In NanoLab usare `deployment-lifecycle-container` e `deployment-lifecycle-k8s` secondo AGENTS.md, impostando `NANOFAAS_ROOT` e il profilo entrambe le queue. Documentare nel file `NANOLAB.md` la procedura HTTP: carico costante, GET revisione, PATCH alternata, assert strategia attiva, conteggio richieste ammesse/completate, polling, restart e ripristino selezione iniziale. Gli scenari sono in un checkout NanoLab esterno: verificare i percorsi disponibili, aggiungere lì le assertion se autorizzato, senza inventare nuovi file già esistenti nel repository NanoFaaS.
- [ ] Soak di almeno 60 minuti con 1.000 cambi manuali inviati dall'harness, due classi di durata e churn di funzioni. Osservare durante drain fino alla massima retention configurata: record vivi, input fisico, lease, waiter, timer, indici e meter. Distinguere le cache entro TTL da leak e heap da RSS. Un harness che invia PATCH esplicite non è un selettore adattivo nel prodotto.
- [ ] Scrivere `FINAL.md`: SHA/test eseguiti e saltati, valori rispetto ai budget, compatibilità, limiti della pausa e rollback di release. Non promettere recupero del pending dopo riavvio. Collegare consuntivo e PR alla #208 quando la sessione esecutiva autorizza la pubblicazione.
- [ ] GitNexus finale, review del diff e commit `Deliver configurable schedulers with manual hot switching`.

## Ordine di esecuzione e review

Sequenza: 0 → 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10 → 11 → 12 → 13. I task 9 e 10 spostano proprietà già esistenti e richiedono review distinta; non devono cambiare gli algoritmi preservati nel task 2. Fino al task 8 le classi nuove possono essere compilate/testate senza attivarle in produzione.

Per ogni task registrare in `STATO.md`: stato implementato/verificato, SHA, impact, comando RED con causa attesa, comando GREEN, evidenza d'integrazione e limiti. I comandi di commit sono sempre preceduti dalla verifica delle modifiche:

```bash
git diff --check
node .gitnexus/run.cjs detect-changes --scope all --repo .
git diff --stat
```

Uno zero da un grafo incompleto non è un lasciapassare. Per il commit usare `git add -- <soli file del task>` con percorsi espliciti del task e il messaggio indicato; non includere file dell'utente estranei al lavoro. La creazione del worktree avviene nella sessione di implementazione con `superpowers:using-git-worktrees`, se serve isolamento.

## Copertura della specifica e criteri finali

| Sezione della specifica | Task |
|---|---|
| 1–3: confini, composizione, runtime obbligatorio e SPI | 0, 1, 8, 9, 10 |
| 4: identità, proprietà e deadline | 1, 3, 4, 9, 10 |
| 5: claim/capacità/commit/errori | 3, 4, 5, 12 |
| 6: algoritmi esistenti, configurazione e cambio manuale | 2, 5, 6, 7, 8 |
| 7: ammissione/direct/SYNC/ASYNC e compatibilità | 8, 10, 13 |
| 8: readiness, controller, offload; pull fuori scope | 4, 10, 11, 12 |
| 9: osservabilità e limiti | 3, 5, 11, 12 |
| 10: suite comune | 2–7, 10–13 |
| 11: benchmark senza selezione di un vincitore | 0, 12 |
| 12–14: migrazione, confini, documentazione, chiusura | 0, 8–13 |

- [ ] Entrambe le strategie incluse, utilizzabili e conformi; nessuna eliminata per i benchmark.
- [ ] Scelta all'avvio e cambio manuale via API nei due versi verificati anche con pending/attivi.
- [ ] Nessun cambio autonomo sotto workload variabile; nessun nuovo algoritmo.
- [ ] Nessuna perdita, duplicazione di tentativo o reset di deadline; invocazioni fisiche in corso preservate.
- [ ] Revisione API, strategia attiva e risposta PATCH coerenti, anche con errore prima del commit o risposta HTTP persa.
- [ ] Cap e capability invariati al solo cambio scheduler; cleanup vecchi indici, timer e meter dimostrato.
- [ ] Runtime verificabile senza Spring, entrambe le strategie testate in JVM e native, E2E NanoLab e soak documentati.
- [ ] Budget prestazionali congelati prima delle misure e rispettati; limiti residui dichiarati nel consuntivo.

## Autoverifica del piano

Revisione documentale: copertura delle sezioni 1–14 riportata nella matrice; firme di ticket, indici, store, controllo, transazione API, trasporto e snapshot coerenti; requisiti manuale/conservazione di entrambi gli algoritmi ripetuti nei gate. Non sono stati eseguiti test del software futuro. Il task 0 deve riconfermare i percorsi e il grafo sulla revisione finale della campagna prima di iniziare.
