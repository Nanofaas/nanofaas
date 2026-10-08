# Audit remediation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans for native execution or superpowers:subagent-driven-development if explicitly selected. Steps use checkbox (`- [ ]`) syntax. Questo documento pianifica il lavoro; non registra un'implementazione o un'autorizzazione al push.

**Goal:** Risolvere i sette bug confermati nell'audit del 2026-10-08 e rendere opzionale il bootstrap locale containerd nei percorsi di build, conservando recupero dei deployment, contratto delle invocazioni e sicurezza delle epoche.

**Architecture:** Correzioni circoscritte ai flussi esistenti, organizzate in quattro lotti: deployment, schedulazione one-shot, contratto HTTP/SDK, build. Ogni task produce una correzione verificabile e un commit distinto. Nessuna modifica al formato wire dell'asta, all'aggregazione delle bid o al solver.

**Tech Stack:** Java 25, Spring Boot, Reactor, JUnit 5/AssertJ/Mockito, Python >=3.12, pytest, Gradle, Docker/Podman e GraalVM. HTTPX, già usato nei test Python, diventa dipendenza runtime per il trasporto callback cancellabile.

**Spec:** Requisiti dell'audit accettati nella conversazione e decisioni esplicite in questo documento. Contratti di riferimento: `docs/architecture/0001-execution-lifecycle-contract.md`, `sdks/runtime-contract/README.md`, `docs/one-shot.md`, `docs/one-shot-coordination.md`, `docs/deployment-containerd.md`. Non occorre riprogettare i sottosistemi: servono regressioni riproducibili e correzioni dei loro confini.

## Baseline e copertura

Baseline: `fed1be9c28985ba3e0ee7f067b390e1dc8aac4b3`, branch `main`.

| ID | Problema verificato | Evidenza | Task |
| --- | --- | --- | --- |
| A1 | Collisione fra nomi di container di funzioni distinte | `Echo` e `echo`: seconda creazione sostituisce la prima, che restituisce 502 | 1 |
| A2 | Proxy obsoleto dopo scale-down parziale | Da 3 a 1: r3 rimossa, r2 fallisce; risposte 200, 200, 502 | 2 |
| A3 | Cambio anchor/period blocca le epoche automatiche | Vecchio indice 5971000, nuovo indice 1, nessuna nuova preparazione | 3 |
| A4 | Perdita di precisione nelle finestre automatiche | `PT5M0.0005S`: overlap di 500000 ns, seconda preparazione rifiutata | 4 |
| A5 | Timeout del chiamante pubblicato come HTTP 200 | Risposta `status=timeout`, `statusCode=null`, controller risponde 200 | 5 |
| A6 | Envelope SDK text/plain decodificato come testo JSON grezzo | Output `hello` diventa una stringa di 7 caratteri, incluse le virgolette | 6 |
| A7 | Timeout callback Python non è una scadenza totale | Budget 80 ms, risposta a piccoli frammenti ancora attiva dopo 307 ms | 7 |
| A8 | Bootstrap containerd obbligatorio anche per librerie pubblicate | Wrapper native termina con exit 2 senza `CONTAINERD_MAVEN_REPO` | 8 |

A3 e A4 appartengono al nuovo one-shot; A1, A2, A5, A6 e A7 sono preesistenti. A8 è debito di tooling: il bootstrap dai sorgenti in CI/NanoLab è intenzionale e va mantenuto come selezione esplicita.

Riproduzioni esplorative disponibili in `/tmp/nanofaas-audit-scale/`, `/tmp/oneshot-audit/`, `/tmp/nanofaas-runtime-audit/` e `/tmp/nanofaas-audit-python-repro.py`. Non sono dipendenze del piano: trasferire i casi nei test versionati, senza dipendere dai classpath o dai percorsi personali di quelle prove. Il controllo esplorativo sul wrapper scalare Python non è un ottavo bug approvato: il limite è descritto come limite dell'output del handler; chiarirne la semantica in un intervento separato prima di cambiarla.

Verifiche già disponibili sulla baseline: suite Java completa riuscita; 89 test Python dei percorsi esaminati riusciti; 35 report PMD senza violazioni e report pubblico di codice inutilizzato vuoto. Non usare questi risultati come prova delle correzioni future.

## Global Constraints

- Java 25, indentazione Java di 4 spazi, test `*Test.java`; Python >=3.12.
- Un worktree dedicato per l'esecuzione. Conservare i file locali non tracciati e gli altri worktree; niente cancellazioni massive, rinomina dei container esistenti o reset del repository.
- Eseguire GitNexus `impact` sul simbolo preciso prima delle modifiche di codice; segnalare HIGH/CRITICAL. `UNKNOWN`, risultati incoerenti e insiemi vuoti non equivalgono a basso rischio: confermare nei sorgenti. Prima di ogni commit, `detect_changes(scope=all)` completo, senza `partial` o `truncated`.
- Nell'audit l'indice risultava aggiornato, ma alcune query restituivano riferimenti errati. All'esecuzione verificarlo e, se necessario, ricostruirlo; non riportare come valida un'analisi d'impatto corrotta. Nessuna modifica runtime è stata autorizzata implicitamente da quel grafo.
- Test RED/GREEN sui comportamenti errati; nessun test che si limiti a replicare la nuova implementazione. Usare latch, eventi, clock controllabili e trasporti locali per le race; i timeout dei test sono limiti, non prove di ordinamento.
- Conservare retry configurabili, identità `X-Execution-Id`/`X-Dispatch-Attempt`, idempotenza, limiti di memoria e ownership delle risorse fino alla loro chiusura fisica.
- Il timeout del chiamante non conclude l'esecuzione condivisa. Non usare il codice HTTP deciso dalla funzione per rappresentare un timeout del control plane.
- Conservare schema/topic one-shot, barriere, commitment già emessi, fencing per incarnazione ed epoca e requisito dei 20 campioni di qualificazione. Non azzerare `lastEpoch` per rendere accettabile una configurazione.
- Nessuna infrastruttura Kubernetes provisionata dai test del repository. NanoLab resta proprietario della validazione Kubernetes.
- Nessun aggiornamento opportunistico delle versioni delle dipendenze, refactoring generale o cancellazione automatica basata sui report di dead code.

## Decisioni e compatibilità

1. **Container:** preferire nomi deterministici con digest del nome originale e controlli di ownership alla restrizione dei nomi pubblici delle funzioni. Le funzioni già registrate devono poter essere recuperate con il prefisso precedente, verificando le label esatte. Una collisione deve fallire senza distruggere la risorsa esistente, anche durante il cleanup dopo un errore.
2. **Schedulazione:** rendere immutabile la coppia `(anchor, period)` dopo la prima epoca automatica rivendicata dal processo. Le modifiche successive incompatibili restituiscono 409 e non consumano la revisione. È più circoscritto che introdurre una nuova generazione del protocollo o numeri d'epoca locali divergenti. Il cambio di griglia richiede drain e riavvio coordinato dei peer, con nuova qualificazione; documentarlo esplicitamente. Prima dell'avvio automatico la griglia resta modificabile, purché non violi il fencing delle epoche manuali già eseguite.
3. **Precisione:** mantenere la precisione di `Duration` nel calcolo di entrambe le estremità. Non introdurre il nuovo vincolo pubblico «solo millisecondi interi».
4. **Callback:** passare dal trasporto Requests bloccante a HTTPX asincrono, con scadenza assoluta per tentativo e chiusura del trasporto su cancellazione. Un `wait_for` attorno al lavoro di un thread non corregge A7, perché lascia il lavoro fisico attivo. Il corpo della risposta al callback non viene consumato: bastano stato e header per decidere l'esito.
5. **Build:** Maven Central è il default; sorgenti locali sono un opt-in. Una selezione locale esplicita ma incompleta deve fallire, senza fallback silenzioso alle librerie pubblicate.

## Review Focus

1. Recupero di deployment legacy e collisione rilevata durante una creazione fallita: mai rimuovere container di un'altra funzione o namespace (task 1).
2. Errore a metà scale-down e successivo retry, inclusa replica one-shot ancora occupata: aggiornare il proxy senza perdere le risorse da ripulire (task 2).
3. Configurazione concorrente con il tick, disable/enable e passaggio manuale→automatico: nessun reset o bypass del fencing (task 3 e 4).
4. Timeout del waiter seguito da callback/replay e output text/plain ricevuto prima o dopo il callback: stato condiviso e output devono restare corretti (task 5 e 6).
5. Header HTTP a piccoli frammenti, redirect, stop e saturazione callback: scadenza totale e rilascio fisico verificati; Central e opt-in locale provati separatamente (task 7 e 8).

## Lotto A — Deployment

### Task 1: Rendere sicura l'identità dei container e il recupero legacy

**Files:**
- Modify: `platform/libs/container-deployment-runtime/src/main/java/it/unimib/datai/nanofaas/containerdeployment/LocalManagedDeploymentProvider.java`
- Modify: `platform/modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/ContainerLocalDeploymentProvider.java`
- Modify: `platform/modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/DockerJavaContainerRuntimeAdapter.java`
- Modify: `platform/modules/container-deployment-provider/src/main/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/CliContainerRuntimeAdapter.java`
- Test: `platform/libs/container-deployment-runtime/src/test/java/it/unimib/datai/nanofaas/containerdeployment/LocalManagedDeploymentProviderTest.java`
- Test: `platform/modules/container-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/ContainerLocalDeploymentProviderTest.java`
- Test: `platform/modules/container-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/DockerJavaContainerRuntimeAdapterTest.java`
- Test: `platform/modules/container-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/containerdeploymentprovider/CliContainerRuntimeAdapterTest.java`
- Modify: `docs/control-plane.md`

**Interfaces:** preservare `ContainerRuntimeAdapter.runContainer(ContainerInstanceSpec)` e le label `MANAGED_LABEL`, `FUNCTION_LABEL`, `REPLICA_LABEL`. `containerNamePrefix(String)` resta il punto di personalizzazione del backend. Il prefisso effettivo recuperato deve essere conservato nello stato della funzione e ripubblicato in `ProvisionResult.CONTAINER_NAME_PREFIX`, non ricalcolato come se il deployment fosse nuovo.

- [x] Aggiungere regressioni `distinctFunctionNamesDoNotReplaceEachOther` per `Echo`/`echo`, `foo_bar`/`foo-bar`, Unicode e nomi lunghi. Asserire prefissi distinti, sei invocazioni per funzione tutte 200 e nessuna rimozione della prima funzione quando si registra la seconda; includere namespace distinti.
- [x] Aggiungere `nameConflictNeverDeletesForeignContainer`, anche con collisione avvenuta fra controllo iniziale e creazione: simulare container non gestito o label di un'altra funzione. Asserire zero chiamate di rimozione, compreso il catch di `addReplica`. Coprire entrambi gli adapter: non basta verificare il digest nel provider.
- [x] Aggiungere `reconcileAdoptsOwnedLegacyPrefix`: catalogo con vecchio prefisso, label originali corrette, replica attiva. Dopo reconcile, scale-up e riavvio simulato, mantenere il prefisso e le repliche; rifiutare prefisso/label estranei senza rimuoverli.
- [x] Eseguire i test e registrare i fallimenti attesi A1 e del cleanup. Implementare nomi nuovi `nanofaas-<slug fino a 44 caratteri>-<16 cifre hex SHA-256 del nome originale UTF-8>`, più namespace e `-r<indice>` secondo la composizione esistente. Conservare il naming containerd già distinto; nessuna migrazione automatica delle sue risorse.
- [x] Limitare il recupero ai due prefissi consentiti per quel backend/namespace: attuale e legacy derivato dalla medesima identità. Validare sempre label e indice. Eliminare la sostituzione cieca in `runContainer`; verificare ownership prima di un'eventuale sostituzione e propagare i conflitti senza cleanup distruttivo. Se serve un'eccezione specifica di collisione, collocarla nella libreria comune container-deployment-runtime e usarla anche nel cleanup del provider.
- [x] Verificare con `./gradlew :container-deployment-runtime:test :control-plane-modules:container-deployment-provider:test :control-plane-modules:containerd-deployment-provider:test`. Eseguire il test Docker reale di isolamento su risorse di test con nomi unici; nessun container preesistente deve essere toccato. Documentare recupero legacy e collisioni esplicite.
- [x] Analisi graph completa e commit: `Prevent container ownership collisions`.

### Task 2: Pubblicare nel proxy anche gli esiti parziali dello scaling

**Files:** modificare `LocalManagedDeploymentProvider.java` e `LocalManagedDeploymentProviderTest.java` ai percorsi del task 1; aggiornare `docs/control-plane.md`.

**Interfaces:** conservare `scaleTo(FunctionState, int)`, `removeReplica(FunctionState, int)` e `pushProxyConfig(FunctionState)`. Lo stato conserva le repliche la cui rimozione non è confermata; il proxy riceve l'insieme aggiornato dopo ogni operazione di scaling, anche fallita.

- [x] Aggiungere `partialScaleDownRefreshesProxy`: tre backend locali reali; rimuovere r3, far fallire r2, invocare sei volte; attesi due backend rimasti e tutte le risposte 200. Asserire che l'errore di scaling rimane visibile.
- [x] Aggiungere casi per fallimento della prima rimozione, scale-up parziale, retry dello stesso target e fallimento di `pushProxyConfig` insieme all'errore primario. Nel caso one-shot, replica occupata/quarantinata resta tracciata e non è rimessa in servizio da un refresh del proxy.
- [x] Eseguire RED. Garantire il refresh sul percorso di errore usando lo stato effettivo; conservare l'eccezione primaria e aggiungere quella del refresh come suppressed. Non anticipare la rimozione dalla mappa rispetto alla conferma del runtime, né simulare un rollback di container già eliminati.
- [x] Eseguire `./gradlew :container-deployment-runtime:test :control-plane-modules:container-deployment-provider:test :control-plane-modules:containerd-deployment-provider:test` e documentare il comportamento di retry dopo scaling parziale.
- [x] Analisi graph completa e commit: `Refresh proxy after partial scaling failures`.

## Lotto B — Schedulazione one-shot

### Task 3: Impedire riconfigurazioni che invalidano la progressione delle epoche

**Files:**
- Modify: `platform/modules/offload/src/main/java/it/unimib/datai/nanofaas/modules/offload/oneshot/api/OneShotOperations.java`
- Modify: `platform/modules/offload/src/main/java/it/unimib/datai/nanofaas/modules/offload/oneshot/coordination/EpochCoordinator.java` soltanto per esporre il limite monotono in sola lettura, se necessario
- Test: `platform/modules/offload/src/test/java/it/unimib/datai/nanofaas/modules/offload/oneshot/api/OneShotTimingQualificationTest.java`
- Test: `platform/modules/offload/src/test/java/it/unimib/datai/nanofaas/modules/offload/oneshot/api/OneShotConfigurationTest.java`
- Modify: `platform/modules/offload/openapi.yaml`, `docs/one-shot.md`, `docs/one-shot-coordination.md`

**Interfaces:** preservare `configure(long, OneShotSettings)` e `prepare(long, Window, boolean)`. Aggiungere un clock iniettabile tramite costruttore di test mantenendo il costruttore produttivo con UTC. Conservare una griglia congelata `(Instant anchor, Duration period)` dopo la prima rivendicazione automatica. Eventuale `EpochCoordinator.lastPreparedEpoch(): long` espone il fencing esistente senza reset.

- [x] Aggiungere `rejectsGridChangeAfterFirstScheduledEpoch`: produrre realmente il primo tick con clock controllato, senza impostare `lastScheduled` via reflection; cambiare anchor e poi period. Attesi 409/`ONE_SHOT_CONFLICT`, revisione invariata e prosecuzione sulla vecchia griglia.
- [x] Aggiungere casi: modifica prima del primo tick ammessa; stessa griglia e nuovo profilo ammessi se qualificati; disable→modifica→enable e stop→start non aggirano il vincolo; tentativo automatico fallito non sblocca la griglia; revisione obsoleta conserva il conflitto previsto.
- [x] Aggiungere race configurazione/primo tick con barrier: o la nuova griglia viene accettata prima del claim, oppure viene rifiutata dopo; nessuno stato misto. Coprire manuale→scheduled con candidato successivo sopra/sotto l'ultimo epoch del coordinator: il caso sotto viene rifiutato esplicitamente, mai accettato per restare silenziosamente inattivo.
- [x] Eseguire RED. Rendere atomici la verifica/configurazione e il claim della griglia sotto lo stesso lock; non tenere il lock durante solver, rete o attuazione. Validare prima di `configs.replace`, congelare la griglia prima della sottoscrizione. Mantenere entrambi i fencing; non rinumerare epoche diversamente sui peer.
- [x] Eseguire `./gradlew :control-plane-modules:offload:test --tests '*OneShotTimingQualificationTest' --tests '*OneShotConfigurationTest'`. Aggiornare OpenAPI e guida con 409, momento del congelamento e procedura drain/riavvio coordinato per cambiare griglia.
- [x] Analisi graph completa e commit: `Reject incompatible one-shot schedule changes`.

### Task 4: Conservare la precisione temporale delle finestre

**Files:** `OneShotOperations.java` e `OneShotTimingQualificationTest.java` del task 3; `platform/modules/offload/src/test/java/it/unimib/datai/nanofaas/modules/offload/oneshot/actuation/ReplicaPlanActuatorTest.java`; `docs/one-shot-coordination.md`.

**Interfaces:** introdurre nel package API una funzione pura di calcolo, nel medesimo file se sufficiente: `static Optional<ScheduledWindow> nextWindow(OneShotSettings settings, Instant now)`, con `ScheduledWindow(long epoch, Window window)`. Questo task aggiorna anche la validazione implementata nel task 3 per usare lo stesso risultato del tick. Nessun nuovo bean o formato wire.

- [x] Aggiungere `fractionalPeriodCreatesContiguousWindows`: periodo `Duration.ofSeconds(300).plusNanos(500_000)`, anchor anche non allineata al millisecondo. Asserire `end(epoch).equals(start(epoch+1))` e durata esatta; i due piani consecutivi vengono preparati senza overlap.
- [x] Coprire istante prima dell'anchor, bordo esatto, lead time, indice grande e overflow di `Instant`/moltiplicazione. Il bordo di inizio già raggiunto non prepara retroattivamente quel piano. Per input fuori dal range supportato produrre errore esplicito, senza wrap numerico o indice negativo.
- [x] Eseguire RED. Calcolare `delta = Duration.between(anchor, now.plus(leadTime))`, indice con `delta.dividedBy(period)`, inizio con `anchor.plus(period.multipliedBy(epoch))`, fine con `start.plus(period)`. Evitare la conversione di tutto il delta in nanosecondi o millisecondi. Riutilizzare il calcolo nel controllo manuale→scheduled del task 3.
- [x] Eseguire `./gradlew :control-plane-modules:offload:test --tests '*OneShotTimingQualificationTest' --tests '*ReplicaPlanActuatorTest'` e poi la suite `--tests 'it.unimib.datai.nanofaas.modules.offload.oneshot.*'`. Documentare la precisione supportata.
- [x] Analisi graph completa e commit: `Preserve precision in one-shot epoch windows`.

## Lotto C — Contratto HTTP e SDK

### Task 5: Restituire 408 soltanto per il timeout del chiamante

**Files:**
- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/SyncInvocation.java`
- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/service/ReactiveInvocationCoordinator.java`
- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/api/InvocationController.java`
- Test: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/api/InvocationControllerTest.java`
- Test: creare `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/service/WaiterTimeoutHttpContractTest.java`
- Modify: `openapi/core.yaml`, `docs/architecture/0001-execution-lifecycle-contract.md` solo per chiarimenti necessari; il 408 è già richiesto

**Interfaces:** estendere `SyncInvocation` con `boolean waiterTimedOut`, mantenere i costruttori a 2 e 3 argomenti con default false e aggiungere `static SyncInvocation waiterTimeout(InvocationResponse response, String target, String executionNode)`. Il flag è interno al control plane, non un nuovo campo JSON pubblico. Preservarlo in eventuali ricostruzioni della risposta individuate con impact.

- [x] Aggiungere `waiterTimeoutReturns408WithoutFunctionStatusMarker`: vero percorso coordinator→controller con esecuzione bloccata da latch; scade il solo waiter. Attesi HTTP 408, `status=timeout`, execution ID stabile, assenza del marker di status deciso dalla funzione.
- [x] Sullo stesso execution ID verificare stato ancora running, secondo waiter ancora attivo, successivo callback e replay/poll con risultato reale; coprire local ed offloaded. Un timeout già terminale dell'esecuzione non viene riclassificato come timeout del chiamante; un handler che decide 408 conserva il proprio marker.
- [x] Eseguire RED. Usare la factory soltanto nel ramo TimeoutException relativo all'attesa del chiamante; `toResponse` sceglie 408 tramite il flag, senza inferirlo da `response.status()` e senza valorizzare `InvocationResponse.statusCode` come se fosse una scelta del handler.
- [x] Eseguire `./gradlew :control-plane:test --tests '*InvocationControllerTest' --tests '*WaiterTimeoutHttpContractTest'` e le regressioni esistenti di timeout/idempotenza individuate nei caller. Aggiungere il caso al gate API dell'artefatto native nel task 9.
- [x] Analisi graph completa e commit: `Return HTTP 408 for caller wait timeouts`.

### Task 6: Decodificare gli envelope marcati prima del Content-Type

**Files:**
- Modify: `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/dispatch/ExternalDispatcher.java`
- Test: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/dispatch/ExternalDispatcherTest.java`
- Test: `sdks/java/src/test/java/it/unimib/datai/nanofaas/sdk/runtime/InvokeControllerTest.java`
- Modify: `docs/architecture/0001-execution-lifecycle-contract.md` se necessario per esplicitare il wire degli envelope

**Interfaces:** conservare `decodeBody(ClientResponse, boolean)`. Il marker valido di risposta decisa dalla funzione impone decodifica JSON indipendentemente da Content-Type; `text/plain` senza marker mantiene il percorso legacy raw.

- [x] Aggiungere test parametrico con envelope marcato text/plain e output stringa, oggetto, numero, booleano e null. Assert: `"hello"` sul wire → `hello` di 5 caratteri; oggetto → oggetto; un solo abbonamento al body. Costruire almeno un caso usando l'output effettivo del controller SDK, non una fixture raw inventata.
- [x] Verificare JSON marcato malformato → errore di trasporto, text/plain non marcato → testo originale, application/json invariato e metadati status/header/encoding conservati. Verificare equivalenza HTTP/callback con entrambi gli ordini di completamento, usando le regressioni di lifecycle esistenti.
- [x] Eseguire RED. Applicare il ramo JSON per il marker prima del ramo text/plain, mantenendo una sola lettura e la gestione esistente di body vuoto/null. Non cambiare ciò che gli SDK serializzano.
- [x] Eseguire `./gradlew :control-plane:test --tests '*ExternalDispatcherTest' :sdks:java:test --tests '*InvokeControllerTest'` e i test di completamento callback modificati.
- [x] Analisi graph completa e commit: `Decode marked function responses as JSON`.

### Task 7: Rendere cancellabile e temporalmente limitato il trasporto callback Python

**Files:**
- Create: `sdks/python/src/nanofaas/runtime/callback_transport.py`
- Modify: `sdks/python/src/nanofaas/runtime/app.py`, `sdks/python/pyproject.toml`, `sdks/python/uv.lock`
- Test: `sdks/python/tests/test_callback_transport.py`, `sdks/python/tests/test_runtime.py`, `sdks/python/tests/test_failure_wire_corpus.py`, `sdks/python/tests/test_p16b_regressions.py`
- Modify: `sdks/python/README.md`, `sdks/runtime-contract/README.md`

**Interfaces:** nuovo `async def post_callback(url: str, body: bytes, headers: dict[str, str], timeout_seconds: float) -> int`, che restituisce lo status HTTP senza consumare il body. Conservare `_post_callback_with_retries(...)` come proprietario della policy di retry. Adattare `RuntimeWorkManager.run_callback_call(...)` al callable asincrono e alla contabilizzazione dei task fisici; nessun executor bloccante per il trasporto callback, handler sincroni ancora nel loro executor.

- [ ] Aggiungere prove con server TCP/HTTP locale: headers inviati a piccoli frammenti più frequenti del timeout per-read; risposta 200 con body che non finisce; connessione che non invia headers; upload bloccato. Deadline per tentativo 100 ms, limite esterno del test 1 s. Assert: gli headers lenti scadono; dopo headers 200 il body inutile non trattiene il worker; socket chiuso, contatori e byte rilasciati, invocazione successiva ammessa.
- [ ] Aggiungere regressioni per 2 worker, coda limitata, stop e cancellazione mentre un tentativo è in attesa del worker e mentre è su rete. Conservare `NANOFAAS_CALLBACK_WORKERS`, `NANOFAAS_MAX_PENDING_CALLBACKS`, budget byte e metriche; verificare che non vi siano due tentativi fisici sovrapposti dopo il timeout dello stesso callback.
- [ ] Congelare nei test la policy esistente: 204 riuscito; 400 permanente; 408/429/5xx ritentabili; 3 tentativi di default; identità invariata nei retry; redirect 301/302/303 e 307/308 con metodi e payload attuali. Una catena di redirect condivide il budget del tentativo. Aggiornare i mock di Requests verso il nuovo confine di trasporto; mantenere prove reali, non soltanto mock.
- [ ] Eseguire RED sul difetto originale. Usare `httpx.AsyncClient` e `asyncio.timeout` sull'intero tentativo, inclusi attesa dello slot, connect, scrittura, header e redirect. Usare la modalità streaming e chiudere response/client in tutti i percorsi; la cancellazione deve chiudere la connessione prima del rilascio della reservation. La chiusura ha un limite finito e non deve mascherare l'errore originale.
- [ ] Conservare il numero massimo di worker con un limite asincrono, senza polling o nuove code illimitate. Collegare contatori/drain alla conclusione dei task di trasporto. I test ASGI creano più event loop: non conservare primitive legate a un vecchio loop oltre il ciclo di vita del manager; aggiungere una regressione dedicata.
- [ ] Promuovere HTTPX già bloccato nel lockfile a dipendenza runtime. Rimuovere Requests dalle dipendenze runtime solo dopo verifica dei suoi altri consumatori; nessun upgrade generale. Eseguire `uv run --project sdks/python --extra test python -m pytest sdks/python/tests -q` e `python3 -m pytest sdks/runtime-contract -q` nell'ambiente test configurato.
- [ ] Documentare timeout totale per tentativo, policy di redirect e conteggio worker. Verificare l'immagine Python di esempio con l'installazione delle sole dipendenze runtime e un vero callback, così il risultato non dipende dagli extra di test.
- [ ] Analisi graph completa e commit: `Enforce total Python callback deadlines`.

## Lotto D — Build e tooling

### Task 8: Rendere esplicita la scelta Central/sorgenti locali

**Files:**
- Modify: `scripts/native-java-image.sh`, `scripts/sonar.sh`
- Modify: `tools/gradle-plugin/src/main/java/it/unimib/datai/nanofaas/gradle/RecipeContainerBuild.java`
- Modify: `tools/gradle-plugin/src/main/java/it/unimib/datai/nanofaas/gradle/RecipeArtifacts.java`
- Test: `tools/gradle-plugin/src/test/java/it/unimib/datai/nanofaas/gradle/RecipeContainerBuildTest.java`
- Test: `tools/gradle-plugin/src/test/java/it/unimib/datai/nanofaas/gradle/RecipePluginTest.java`
- Test: `scripts/tests/test_java_container_images.py`, `scripts/tests/test_sonar_script.py`
- Modify: `docs/deployment-containerd.md`, `docs/recipes.md`

**Interfaces:** Gradle mantiene `-PcontainerdMavenLocal=true -Dmaven.repo.local=<directory>`; gli script interpretano `CONTAINERD_MAVEN_REPO` non vuoto come opt-in locale. Nessuna variabile → Central. Estendere `RecipeContainerBuild.gradleArgs(...)` con `boolean useLocalContainerdRepository`, calcolato una sola volta in `RecipeArtifacts` e propagato sia all'export binario sia alla build immagine multiarch.

- [ ] Aggiungere matrice di test: modulo containerd + default Central → nessun flag Maven locale; opt-in con directory valida → flag e build context locali; opt-in senza directory o directory inesistente → errore prima del build; sola `maven.repo.local` senza opt-in non abilita implicitamente i sorgenti locali.
- [ ] Negli script usare comandi stub per verificare gli argomenti realmente ricevuti da Docker/Gradle: niente avvio di SonarQube o chiamate esterne nei test. Coprire selezione esplicita, `all`, modulo assente, namespace di percorsi con spazi e directory locale non valida. Mantenere i test shell esistenti.
- [ ] Eseguire RED. Rimuovere la precondizione locale quando non selezionata e aggiungere i flag soltanto quando serve; mantenere il contesto Maven vuoto previsto dal Dockerfile per il percorso Central. Non montare il repository dell'host solo perché è impostata una proprietà Maven generica.
- [ ] Conservare pin, receipt e verifica SHA-256 di `scripts/bootstrap-containerd-dependencies.sh`; verificare le chiamate CI/NanoLab documentate, senza trasformarle in build Central. Aggiornare esempi per entrambe le modalità.
- [ ] Eseguire `./gradlew -p tools/gradle-plugin test --tests '*RecipeContainerBuildTest' --tests '*RecipePluginTest'` e `python3 -m pytest scripts/tests/test_java_container_images.py scripts/tests/test_sonar_script.py scripts/tests/test_native_build_wrapper.py -q` nell'ambiente pytest configurato. Verificare un `bootJar` containerd con Central e una build native in container del task 9.
- [ ] Analisi graph completa e commit: `Make local containerd dependencies opt-in`.

## Task 9: Verifica integrata e revisione finale

Dipendenze: task 1→2, task 3→4; task 5 e 6 condividono la verifica HTTP ma non richiedono nuove interfacce reciproche; task 7 e 8 sono indipendenti. Ordine native raccomandato: 1, 2, 3, 4, 5, 6, 7, 8, 9. Se si parallelizza, lotti in worktree distinti e integrazione prima dei gate globali.

- [ ] Convertire tutte le prove essenziali dell'audit in test versionati. Nessun test finale dipende da `/tmp`, da un Mockito agent con percorso personale o da un classpath estratto manualmente.
- [ ] Eseguire `BUILDX_BUILDER=default ./gradlew test --continue` e `./gradlew deadCode deadCodePublic`; controllare report dei moduli correnti, fallimenti e test saltati. Usare un builder funzionante scelto per quel comando, senza modificare la configurazione Docker globale.
- [ ] Eseguire la suite Python completa e i test script interessati; verificare che HTTPX venga installato anche nell'immagine Python senza extra test. Ripetere i gate soltanto se nuove modifiche o risultati dubbi lo richiedono.
- [ ] Eseguire `./gradlew :control-plane-modules:offload:oneShotE2e -Precipe=recipes/one-shot-local-jvm.yaml`. Integrare un caso che attraversi almeno due finestre automatiche contigue e la riconfigurazione rifiutata; completare prima la qualificazione prevista, senza aggiungere un bypass produttivo.
- [ ] Eseguire il gate native già documentato: `./gradlew :control-plane:nativeCompile -Precipe=recipes/one-shot-local-native.yaml -PnativeParallelism=2 -PnativeBuildMemory=4g`, quindi `./gradlew :control-plane-modules:offload:oneShotE2e -Precipe=recipes/one-shot-local-native.yaml -DoneShot.controlPlaneBinary="$PWD/platform/control-plane/build/native/nativeCompile/control-plane"`. Aggiungere alla verifica HTTP del binario i casi 408 waiter e text/plain marcato. Le modifiche toccano runtime, quindi il gate native non è facoltativo come nell'intervento precedente solo test/docs.
- [ ] Provare packaging containerd Central da ambiente senza repository staged e la variante locale con repository/receipt validi. Non basta che gli stub accettino gli argomenti. Usare risorse isolate e le ricette native/container esistenti; non provisionare Kubernetes.
- [ ] Verificare documentazione, OpenAPI composta, eventuali runtime hint per le sole nuove classi effettivamente serializzate e compatibilità del recupero legacy. `waiterTimedOut` non deve apparire nello schema JSON pubblico.
- [ ] Revisione indipendente dell'intero diff, concentrata su ownership/cleanup, fencing, rilascio fisico dei callback e perdita di compatibilità. Risolvere rilievi bloccanti con regressioni; registrare eventuali limiti ambientali invece di dichiarare i gate superati.
- [ ] Aggiornare questo piano con evidenze, comandi, esiti e decisioni effettive; analisi graph completa prima dell'ultimo commit. Merge e push restano azioni di integrazione da eseguire solo quando richieste per questo intervento.

## Criterio di completamento

Tutti gli ID A1–A8 hanno test di regressione e comportamento corretto; nessun container estraneo può essere eliminato durante creazione o cleanup; il proxy rispecchia gli esiti parziali; la schedulazione avanza su finestre contigue oppure rifiuta esplicitamente una griglia incompatibile; il waiter scade con 408 senza terminare l'esecuzione; HTTP e callback conservano lo stesso output; i callback non trattengono risorse oltre una scadenza non rispettata; Central e sorgenti locali sono entrambi utilizzabili. Gate globali, JVM/native e revisione finale superati, oppure limiti residui dichiarati come lavoro non completato.

## Autorevisione del piano

- Copertura: A1–A8 mappati ai task 1–8; verifica trasversale nel task 9.
- Compatibilità: prefissi legacy, namespace, epoch fencing, status scelti dal handler, identità dei retry e build source-pin esplicitamente coperti.
- Decisioni deliberate: griglia immutabile dopo il primo claim; precisione `Duration` conservata; HTTPX cancellabile al posto di timeout applicato a thread; Central come default.
- Nessuna correzione applicata durante la pianificazione. L'implementazione deve validare le assunzioni nei test prima di modificare il runtime.
