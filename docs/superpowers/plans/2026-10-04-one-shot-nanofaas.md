# One-shot NanoFaaS — Phase A Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** implementare e verificare tutto one-shot in NanoFaaS prima di iniziare il lavoro NanoLab.

**Architecture:** offload orchestra asta e piani usando `p2p-api`, `forecasting-api` e il controllo repliche esistente. Solver puro, snapshot immutabili, ledger locale e attuazione hanno responsabilità separate. Runtime Rust e proxy container dimostrano occupazione fisica e concorrenza uno.

**Tech Stack:** Java 25, Spring Boot, Reactor, array primitivi Java, JUnit, GraalVM, Rust/Tokio, test container locali. Nessun solver Python o MILP nel deployment.

**Spec:** [specifica approvata](../specs/2026-10-04-one-shot-offload-design.md); [indice e gate](2026-10-04-one-shot-implementation.md).

## Global Constraints

- Tutti i file di questo piano appartengono a NanoFaaS. Nessun workflow NanoLab, provisioning Azure o modifica a Sonata.
- Concorrenza fisica per replica `1`; `STATIC_PER_POD`, `targetInFlightPerPod=1`, tetto di funzione sufficiente e `NANOFAAS_MAX_CONCURRENT_HANDLERS=1`.
- Cloud terminale; proibiti `A -> B -> C` e `A -> B -> cloud`.
- Nessun valore predefinito di un minuto; periodo, anticipo, round e deadline sono parametri espliciti.
- Zero repliche pronte equivale a zero capacità locale. Un dato mancante/scaduto non equivale a zero misurato.
- Solver e trasporto non bloccano event loop o percorso HTTP; budget di memoria, lavoro e richieste pendenti finiti.
- L'asta non comprende PG. Granularità `q` esplicita; nessun arrotondamento nascosto.
- Profili sintetici ammessi solo nel profilo di test, marcati come tali; i test non li presentano come calibrazione reale.
- Prima di editare codice eseguire GitNexus impact sui simboli; prima di ogni commit eseguire detect-changes e controllare il diff. Non includere le modifiche preesistenti ad `AGENTS.md`.

## Review Focus

1. Risultati asincroni di una funzione rimossa e ricreata: fencing e nessuna resurrezione dello stato (A3/A4/A10).
2. Timeout mentre una closure Rust continua: nessun rilascio anticipato dello slot o downscale distruttivo (A7/A8).
3. Duplicati di bid/conferme e riavvio del peer con lo stesso nome: idempotenza e fencing per incarnazione (A6/A9).
4. Input numerici non finiti, overflow degli stati e profilo sintetico caricato per errore: rifiuto esplicito (A1/A5/A11).
5. Clock fuori soglia e asta senza convergenza entro deadline: stato degradato e tempi censurati (A9/A11/A14).

## Percorsi, responsabilità e comandi

Base osservata: NanoFaaS `a62a743f`, più soli commit documentali fino a `e8cbf148`. All'esecuzione verificare il nuovo HEAD prima di applicare questi punti di integrazione.

Per rendere leggibili i percorsi, le seguenti abbreviazioni sono espansioni esatte, non directory da creare letteralmente. Ogni tipo Java nuovo elencato vive nel proprio file `<Tipo>.java` nella directory indicata; i test usano il corrispondente `src/test/java` con lo stesso package.

| Sigla | Directory |
| --- | --- |
| O | `platform/modules/offload/src/main/java/it/unimib/datai/nanofaas/modules/offload` |
| P | `platform/modules/p2p-discovery/src/main/java/it/unimib/datai/nanofaas/modules/p2pdiscovery` |
| C | `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane` |
| S | `platform/control-plane-spi/src/main/java/it/unimib/datai/nanofaas/controlplane` |
| R | `platform/container-deployment-runtime/src/main/java/it/unimib/datai/nanofaas/containerdeployment` |
| PA | `platform/p2p-api/src/main/java/it/unimib/datai/nanofaas/p2p/api` |
| FA | `platform/forecasting-api/src/main/java/it/unimib/datai/nanofaas/forecasting/api` |
| F | `platform/modules/forecasting/src/main/java/it/unimib/datai/nanofaas/modules/forecasting` |

Comando unitario dei task Java: `./gradlew :<progetto>:test -PcontrolPlaneModules=all --tests '*<NomeTest>'`. I progetti dei moduli sono `control-plane-modules:offload`, `control-plane-modules:p2p-discovery`, `control-plane-modules:forecasting`; le librerie hanno il proprio nome, per esempio `p2p-api`. I comandi specificati sotto sono da eseguire nella radice NanoFaaS. Prima RED, poi GREEN; al GREEN serve exit code zero e nessun test ignorato per mancanza involontaria di prerequisiti.

## A1 — Contratti degli artefatti e fixture del riferimento

**File:** creare `docs/contracts/one-shot/{forecast,service-profile,run-events}.schema.json`, `docs/contracts/one-shot/README.md`, `platform/modules/offload/src/test/resources/one-shot/reference/manifest.json`, fixture JSON nella stessa directory, `scripts/one-shot/export_reference.py`, `scripts/tests/test_one_shot_contracts.py`.

**Interfacce:** gli schemi v1 fissano unità, identificatori, versioni e hash. Forecast: origine, funzione, generazione locale, intervallo `[start,end)`, rate, revisione. Service profile: fingerprint di immagine/runtime/backend/input/CPU/memoria/co-locazione e ambiente host/VM, provider, finalità `workflow-validation` o `scientific-experiment`, distribuzione warm, `D`, intervallo di validità del modello, `synthetic` e provenienza. Profili locali misurati e fixture sintetiche rimangono distinti. Eventi: nodo/incarnazione, epoca/round, tipo, offset monotono locale, tempo UTC, stato, censura e ID di correlazione. Hash SHA-256 dei byte immutabili, senza includere il proprio hash nel contenuto. Le generazioni delle funzioni sono locali: peer con contatori diversi possono eseguire la stessa funzione/versione; un assignment conserva la generazione del venditore senza richiederne l'uguaglianza con quella del compratore.

- [ ] Scrivere `test_one_shot_contracts.py`: fixture completa valida; `NaN`, durata negativa, campo richiesto assente e versione sconosciuta rifiutati. L'exporter deve fallire se il checkout non corrisponde al commit richiesto.
- [ ] Eseguire `python3 -m pytest scripts/tests/test_one_shot_contracts.py -q` e verificare RED per schemi/exporter assenti.
- [ ] Implementare `export_reference(repo: Path, output: Path) -> None`: richiedere esplicitamente il checkout del branch `71899f7`, registrare SHA completo e hash dei sorgenti, esportare input/output per `LSP`, `LSPr_x` e ogni ulteriore variante realmente invocata dall'asta base. Includere trascrizioni d'asta con round e decisioni, non solo welfare finale. Usare l'ambiente Python del riferimento soltanto per rigenerare fixture; nessuna sua dipendenza entra in NanoFaaS runtime o nella normale CI Java.
- [ ] Verificare GREEN; confrontare fixture con il riferimento e, per piccoli casi, enumerazione esaustiva/Pyomo. Documentare separatamente tie-break DP e ottimi MILP equivalenti. Congelare anche la mappatura `x/omega/z/r` verso i flussi runtime.
- [ ] Commit: `Add versioned one-shot contracts and reference fixtures`.

## A2 — Contratto P2P e adattatore esistente

**File:** creare `platform/p2p-api/build.gradle`, PA `PeerTransport`, `PeerEndpoint`, `PeerReceiver`, `PeerSubscription`; modificare `settings.gradle`, `platform/modules/p2p-discovery/build.gradle`, P `P2pConfiguration`, `PeerMessaging`; creare P `DefaultPeerTransport`. Test: `PeerTransportContractTest`, `DefaultPeerTransportTest`.

**Interfacce:** `PeerTransport.activeNeighbors(): List<PeerEndpoint>`, `request(String peerId, String topic, byte[] payload, Duration timeout): Mono<byte[]>`, `subscribe(String topic, PeerReceiver receiver): PeerSubscription`; `PeerReceiver.onMessage(String senderId, byte[] payload): Mono<byte[]>`; `PeerSubscription.close(): void`. `PeerEndpoint` contiene identità, incarnazione e URI HTTP di invocazione esplicitamente annunciata, distinta dall'indirizzo del trasporto P2P. Non dedurre porte HTTP dalla porta di discovery.

- [ ] Scrivere test: esclusione peer inattivo, endpoint HTTP non annunciato non eleggibile per one-shot, payload oltre limite respinto, chiusura subscription effettiva e nessun callback dopo stop/rejoin della vecchia generazione.
- [ ] Eseguire RED con `:p2p-api:test` e `:control-plane-modules:p2p-discovery:test`, filtro dei due test.
- [ ] Estrarre solo il contratto pubblico, adattare `PeerMessaging` senza copiare discovery/trasporto. Esporre endpoint di invocazione e incarnazione nello scambio applicativo versionato; i peer precedenti continuano la discovery ma non vengono venduti come destinazioni one-shot. Limitare payload applicativi a 1 MiB e richieste concorrenti configurabili con default 4.
- [ ] Eseguire GREEN e i test P2P esistenti. Verificare assenza di dipendenze di `p2p-api` su moduli, Spring e classi interne del control plane.
- [ ] Commit: `Expose minimal P2P contracts for offload`.

## A3 — Contratto forecasting e conteggio degli arrivi esterni

**File:** creare `platform/forecasting-api/build.gradle`, FA `ForecastQuery`, `ForecastSnapshot`, `ForecastSource`, `ExternalArrival`, `ExternalArrivalObserver`; modificare `settings.gradle`, `platform/control-plane/build.gradle`, C `api/InvocationController`; creare C `config/ExternalArrivalConfiguration`. Test: `ExternalArrivalObservationTest`, `ForecastContractTest`.

**Interfacce:** `ForecastSource.forecast(ForecastQuery query): ForecastSnapshot`; query = nodo, funzione, generazione, intervallo. Snapshot immutabile = stato AVAILABLE/MISSING/STALE, rate, revisione, provider, producedAt, intervallo. `ExternalArrivalObserver.record(ExternalArrival arrival): void`, con implementazione no-op se forecasting assente. Evento = funzione/generazione, istante e ID richiesta; nessun contenuto del payload.

- [ ] Test: una richiesta HTTP esterna valida produce un evento; inoltro peer e retry interno ne producono zero; zero osservato differisce da finestra non osservata. Una nuova generazione non eredita le misure della vecchia.
- [ ] Eseguire RED in `forecasting-api` e `control-plane`.
- [ ] Implementare l'osservazione al confine HTTP dopo classificazione del traffico, senza dipendenza del core dal modulo forecasting. Definire come arrivi le richieste esterne valide ricevute: eventuali retry del client sono arrivi osservati distinti, salvo correlazione esplicita; i test sperimentali usano un ID originale e disabilitano retry client automatici. Tenere replay/idempotenza separati dai tentativi interni e documentare i contatori.
- [ ] Eseguire GREEN, incluso avvio core-only senza provider; `./gradlew :control-plane:test -PcontrolPlaneModules=none --tests '*CoreOnlyApiTest'`.
- [ ] Commit: `Add forecast contracts and external arrival observations`.

## A4 — Provider EWMA e oracle atomico

**File:** creare `platform/modules/forecasting/{build.gradle,module.properties,openapi.yaml}`, il file standard `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`; F `ForecastingConfiguration`, `EwmaForecastSource`, `OracleForecastStore`, `ForecastController`, `ForecastingProperties`. Test: `EwmaForecastSourceTest`, `OracleForecastStoreTest`, `ForecastControllerTest`, `ForecastingArchitectureTest`.

**Interfacce:** implementa A3. `PUT /v1/admin/forecasting/trace` carica un documento v1 con revisione attesa; `GET /v1/admin/forecasting/trace` legge revisione e riepilogo. Limiti proposti: 8 MiB/documento e 100.000 righe, rifiuto completo se superati; sostituzione compare-and-set, `409` per revisione concorrente. Snapshot già consegnati restano immutabili.

- [ ] Test: `alpha=0.5`, precedente 10 e osservato 20 → forecast 15; warmup non osservato → MISSING. Test confini `[start,end)`, righe duplicate/sovrapposte rifiutate, caricamento parzialmente invalido non cambia revisione, nuova funzione non usa traccia della vecchia generazione.
- [ ] Eseguire RED: `./gradlew :control-plane-modules:forecasting:test -PcontrolPlaneModules=all`.
- [ ] Implementare provider selezionabile, finestre bounded e scadenze; parametri EWMA e periodo espliciti. Modulo default-off, nessuna dipendenza da offload. Integrare documentazione e composizione OpenAPI nello stesso task.
- [ ] Eseguire GREEN e test architetturale: dipendenze su `forecasting-api`, non sulle implementazioni di altri moduli.
- [ ] Commit: `Implement EWMA and external forecast providers`.

## A5 — Porting del solver locale esatto

**File:** creare O `oneshot/solver/{LocalProblem,LocalSolution,SolveLimits,LocalReplicaSolver,ReplicaCostDp,FlowUnits}` e test `LocalReplicaSolverTest`, `ReferenceSolverParityTest`.

**Interfacce:** `solve(LocalProblem problem, SolveLimits limits): LocalSolution`. Problema = modello supportato, vettori ordinati di carico, domanda, utilità, memoria, impegni in ingresso e variabili fissate; soluzione = stato, `x/omega/z/r`, obiettivo, durata, stati visitati. Limiti = massimo prodotto stati/livelli, byte massimi e deadline monotono. Stati: OPTIMAL, INFEASIBLE, UNSUPPORTED, SIZE_LIMIT, DEADLINE.

- [ ] Test: fixture A1, brute force su piccoli problemi, pareggi deterministici, carico zero, RAM insufficiente, inbound già impegnato, overflow e `NaN`. `FlowUnits` con `q=0.5`, carico 3 e `D=0.1` produce 6 unità e `D_solver=0.05`, mantenendo la capacità fisica.
- [ ] Eseguire RED con i due test nel progetto offload.
- [ ] Portare costruzione delle curve e DP con array primitivi, GCD esatto della RAM, doppio vettore e backtracking. `LSPr_x` usa il calcolo diretto. Per obiettivi normalizzati per carico conservare i coefficienti e verificare l'invarianza; trasformare solo quantità dimensionali secondo A1. Usare decimali per conversione della griglia, evitando `floor` errati da rappresentazione binaria; EWMA separa il resto per il cloud. Come limite iniziale del lavoro usare 2.000.000 stati×livelli, come il riferimento, più un limite byte esplicito; nessun fallback MILP.
- [ ] Eseguire GREEN; confronto differenziale riproducibile senza checkout Python disponibile nella CI ordinaria. Verificare che lo stop per deadline non produca un falso OPTIMAL.
- [ ] Commit: `Port the exact local replica solver to Java`.

## A6 — Motore d'asta puro e ledger del venditore

**File:** creare O `oneshot/auction/{AuctionSnapshot,AuctionMessage,AuctionTransition,OneShotAuctionEngine,SellerLedger,Assignment}`; test `AuctionReferenceReplayTest`, `SellerLedgerTest`.

**Interfacce:** `OneShotAuctionEngine.advance(AuctionSnapshot state, AuctionMessage message): AuctionTransition`; transizione = nuovo snapshot e messaggi da emettere, senza I/O. `SellerLedger.apply(AuctionMessage message): AuctionTransition` serializza l'autorità del venditore. Messaggi tipizzati OFFER/BID/GRANT/ROUND_CLOSE/READY_CONFIRM, envelope schema1/nodo/incarnazione/epoca/round/messageId/funzione/revisione/validità. `Assignment` distingue provisional e ready-confirmed.

- [ ] Test: replay A1, `apply(bid)` due volte non modifica il secondo risultato né consuma altra capacità; due compratori non superano RAM/capacità; messaggi vecchi non mutano il ledger. Verificare l'assenza di sostituzione di assegnazioni esistenti prevista da one-shot. Offerte per funzione/versione non compatibile sono escluse; contatori di generazione diversi su due nodi non bastano invece a dichiararle incompatibili.
- [ ] Eseguire RED con `AuctionReferenceReplayTest` e `SellerLedgerTest`.
- [ ] Portare ordine, prezzi, tie-break, memory bids, ricalcolo a `x` fissato e condizioni di progresso del riferimento usando A5. Per trascrizioni deterministiche ordinare gli input chiusi del round per identità stabile; non equiparare ordine di arrivo di rete e ordine Python. Il ledger applica prima di emettere conferma; conserva deduplica fino a scadenza del round/epoca con limiti espliciti.
- [ ] Eseguire GREEN e simulazione in-memory di tre edge più cloud, verificando conservazione e vincoli a ogni transizione, non solo alla fine.
- [ ] Commit: `Implement one-shot auction transitions and seller commitments`.

## A7 — Occupazione reale Rust e funzione di prova

**File:** modificare `sdks/rust/src/{limits.rs,context.rs,invoke.rs,metrics.rs,runtime.rs}`; creare `sdks/rust/src/occupancy.rs`; creare `functions/rust/one-shot-workload/{Cargo.toml,Cargo.lock,Dockerfile,src/main.rs,README.md}`. Test Rust nel modulo `occupancy`, test di runtime in `sdks/rust/tests/occupancy.rs`, test della funzione nel suo crate.

**Interfacce:** misurare dal possesso iniziale della prenotazione handler fino al rilascio dell'ultimo possessore, inclusa `Context.spawn_blocking`. Aggiungere metriche `nanofaas_runtime_replica_occupancy_seconds` e `nanofaas_runtime_active_handlers`, separate dall'esistente timer di risposta. `GET /runtime/executions/{executionId}` restituisce stato ACTIVE/RELEASED, incarnazione runtime, durata di occupazione a rilascio e stato della risposta (success/error/timeout/cancelled); unknown/expired non costituisce prova di rilascio. I campioni sono così disponibili anche fuori dall'istogramma aggregato. Retention e numero di record terminali bounded, senza executionId come label Prometheus; nessuna espulsione di record attivi per fare spazio. Limiti configurabili con default 10.000 record terminali e 10 minuti di retention, da registrare nelle evidenze.

- [ ] Test con latch: timeout HTTP prima della fine della closure → `active_handlers==1`; dopo rilascio → `0` e un solo campione di occupazione. Panic, errore, cancellazione e clone del context non duplicano il campione. UNKNOWN/expired non viene letto come RELEASED; retention piena non elimina esecuzioni attive. La funzione con stesso seed/input produce stesso checksum e rifiuta dimensioni eccessive.
- [ ] Eseguire RED: `cargo test --locked --manifest-path sdks/rust/Cargo.toml` e, dopo il manifest minimo della funzione, il corrispondente `cargo test --locked`.
- [ ] Implementare osservazione sul proprietario della prenotazione, non sul select HTTP. Funzione con parametri bounded per iterazioni CPU, working-set in byte e seed, tocco effettivo della memoria e checksum; lavoro blocking passa dal Context e conserva la prenotazione. Nessuno sleep come lavoro principale. Stato runtime amministrativo utile al drain, senza introdurre una coda di esecuzione nuova.
- [ ] Eseguire GREEN, build release e verifica container della funzione con concorrenza configurata a 1. Documentare che il consumo reale include runtime oltre al working-set richiesto.
- [ ] Commit: `Measure physical replica occupancy and add a reproducible workload`.

## A8 — Dispatch verso repliche libere e drain sicuro

**File:** modificare R `RoundRobinFunctionProxy`, `ManagedFunctionProxy`, `LocalManagedDeploymentProvider`, `ProxySettings`; creare R `ReplicaSlots`, `ReplicaLease`, `RuntimeExecutionProbe`, `ExecutionObservation`. Test: `ReplicaSlotsTest`, `RoundRobinFunctionProxyTest`, `ReplicaDrainTest`.

**Interfacce:** `ReplicaSlots.tryAcquire(String executionId): Optional<ReplicaLease>`; lease identifica backend e incarnazione, con `markReleased()` solo su evidenza di completamento fisico. `RuntimeExecutionProbe.observe(URI backend, String executionId): ExecutionObservation` consuma A7. `beginDrain(String backendId): void` esclude nuove assegnazioni prima della rimozione.

- [ ] Test: con replica A occupata e B libera la seconda richiesta va a B; nessuna replica supera uno. Timeout/client disconnect non liberano automaticamente A. Risposta di probe UNKNOWN o di vecchia incarnazione non autorizza il riuso. Downscale attende il lavoro in corso senza ucciderlo.
- [ ] Eseguire RED nel progetto `container-deployment-runtime`.
- [ ] Implementare prenotazione atomica per backend e protezione durante aggiornamenti del pool. Nel profilo one-shot, riusare dopo completamento RELEASED verificato per la stessa invocazione/incarnazione, anche quando la risposta HTTP è arrivata prima. Incertezza → replica in quarantena, non nuova esecuzione. Se non arriva mai prova positiva, fallire/degradare e richiedere drain controllato, senza liberare lo slot per semplice timeout. Provider/SDK privi di questa capacità non sono eleggibili per il profilo one-shot iniziale; modalità ordinarie restano compatibili.
- [ ] Eseguire GREEN, inclusi test reali con la funzione A7 e verifica dei contatori runtime. Non limitarsi a contare richieste HTTP contemporanee nel proxy.
- [ ] Commit: `Dispatch one-shot work to free replicas and preserve drain safety`.

## A9 — Coordinatore di epoca e protocollo P2P reale

**File:** creare O `oneshot/coordination/{EpochCoordinator,EpochSettings,AuctionCodec,ClockHealth,EpochOutcome}`; modificare O `OffloadConfiguration`, `platform/modules/offload/build.gradle`. Test: `EpochCoordinatorTest`, `AuctionCodecTest`, `OneShotPeerIntegrationTest`.

**Interfacce:** `EpochCoordinator.prepare(long epoch, Instant startsAt, Instant endsAt): Mono<EpochOutcome>` consuma A2/A4/A6 e produce assegnazioni provvisorie; una negoziazione attiva per nodo. `ClockHealth.sample(Duration observedOffset, Instant measuredAt): void`; campione scaduto o oltre soglia impedisce nuove conferme. A11 espone l'aggiornamento amministrativo; nei test il clock è iniettato.

- [ ] Test con tempo virtuale e trasporto disturbato: duplicati, perdita grant, riordino, round chiuso, rejoin, deadline, clock fuori soglia; nessuna capacità duplicata né callback che ripopola lo stato dopo stop. Integrare tre veri servizi P2P locali per verificare serializzazione e topic.
- [ ] Eseguire RED nel progetto offload, selezionando i tre test.
- [ ] Usare topic `nanofaas.oneshot.v1`, codec bounded 1 MiB, schema e identità verificati. Congelare forecast/catalogo a inizio asta; assegnare code bounded ai messaggi e uno scheduler dedicato al controllo. ROUND_CLOSE distingue tutti-i-peer-chiusi da timeout/round-limit. Nessun algoritmo globale aggiuntivo: senza chiusure sufficienti il risultato è incompleto. Limitare parallelismo e cancellare/fence tutte le attività della vecchia incarnazione.
- [ ] Eseguire GREEN. Registrare durata solver e intera asta distintamente, includendo attese del protocollo, con esito CONVERGED/ROUND_LIMIT/DEADLINE/FAILED.
- [ ] Commit: `Run bounded one-shot epochs over peer messaging`.

## A10 — Proprietà delle repliche e attivazione dei piani

**File:** creare S `registry/ReplicaControlLease`; estendere S `registry/ManagedReplicaControl` e C `registry/ManagedDeploymentCoordinator`, C `api/FunctionController`; creare O `oneshot/actuation/{ReplicaPlanActuator,ActiveRoutingPlan,PlanActivation}`. Test: `ReplicaOwnershipTest`, `ReplicaPlanActuatorTest`, `PlanActivationTest`.

**Interfacce:** aggiungere a `ManagedReplicaControl` acquisizione di un lease esclusivo legato a `FunctionGeneration` e overload `setReplicas(ReplicaControlLease lease, ManagedDeploymentTarget target, int replicas)`. Il metodo esistente resta, ma rifiuta scritture non proprietarie mentre un lease è attivo. `ReplicaPlanActuator.prepare(EpochOutcome outcome): Mono<PlanActivation>`; `PlanActivation` include capacità pronta, impegni confermati, residuo e stato di degradazione. `ActiveRoutingPlan` è immutabile e legato a epoca/generazioni.

- [ ] Test: autoscaler/API concorrenti rifiutati, lease scaduto non riutilizzabile, remove/re-register invalida il vecchio lease; desired=3/ready=1 non conferma capacità per tre. Transizione da due funzioni che singolarmente saturano RAM non alloca entrambe al massimo.
- [ ] Eseguire RED nei progetti control-plane e offload.
- [ ] Implementare attuazione con A8, memoria di sovrapposizione esplicita, drenaggio e switch atomico locale del piano. Proteggere prima inbound già confermato, poi locale. In readiness parziale finalizzare un sottoinsieme ammissibile nell'ordine stabile dei grant; ridurre solo impegni ancora provvisori, mai revocare silenziosamente quelli già confermati. Comunicare READY_CONFIRM prima dell'uso al compratore; nessun ACK ricevuto significa nessun invio. HPA/scaler esterni rendono il profilo non eleggibile.
- [ ] Eseguire GREEN; una funzione senza lease conserva l'attuale comportamento. Fallimenti rilasciano la proprietà solo dopo drain degli impegni validi, non nel semplice `finally` della pianificazione.
- [ ] Commit: `Apply generation-fenced replica plans with exclusive ownership`.

## A11 — Configurazione, profili e API osservabili

**File:** creare O `oneshot/api/{OneShotController,OneShotSettings,ServiceProfileStore,EpochEventStore}`; modificare O `OffloadProperties`, `OffloadConfiguration`, `platform/modules/offload/openapi.yaml`; creare `docs/one-shot.md`. Test: `OneShotConfigurationTest`, `ServiceProfileStoreTest`, `OneShotApiTest`.

**Interfacce:** `PUT /v1/admin/offload/one-shot/config` sostituzione atomica con revisione attesa; `PUT .../profiles/{profileId}`, `GET .../status`, `GET .../epochs/{epoch}/events`, `PUT .../clock-health` (prefisso comune `/v1/admin/offload/one-shot`). Config contiene funzioni/generazioni, cloud URI, budget MiB, profilo, q, periodo/anticipo/deadline, limiti round/burst/skew e limiti solver. Eventi paginati e retention bounded; ID ad alta cardinalità nei log/eventi, non nelle label metriche.

- [ ] Test: forecast/P2P assente → attivazione rifiutata; profilo con digest sbagliato → rifiuto; profilo sintetico senza opzione esplicita di test → rifiuto; profilo realmente misurato su Multipass accettato per verifica locale compatibile, non per un target/finalità diversi. Aggiornamento durante asta vale dalla prossima epoca. Deadline troncata produce `censored=true`, non durata qualificata. Clock update vecchio non rinfresca la validità.
- [ ] Eseguire RED con i tre test offload.
- [ ] Implementare validazione schema A1 e fingerprint, stati diagnostici e metriche aggregate; schemaVersion e hash in ogni esportazione. Fornire endpoint di trigger manuale `POST .../epochs/{epoch}/prepare` solo quando modalità scheduled disabilitata, per prove riproducibili senza doppia asta. Nessuna API fa diventare un profilo sintetico reale. Pubblicare esempi test e produzione distinti.
- [ ] Eseguire GREEN e verifica copertura/composizione OpenAPI esistente. Verificare che disabilitare one-shot conservi l'offload ordinario e che fallback cloud abbia una ragione osservabile.
- [ ] Commit: `Expose one-shot configuration profiles and diagnostics`.

## A12 — Routing pianificato, quote e one-hop end-to-end

**File:** estendere S `offload/{OffloadGateway,OffloadContext}`; creare S `offload/PlannedInvocationRoute`; modificare C `api/InvocationController`, C `service/ReactiveInvocationCoordinator`, O `DefaultOffloadGateway`, `platform/common/src/main/java/it/unimib/datai/nanofaas/common/runtime/ResponseHeaderPolicy.java`; creare O `oneshot/routing/{PlanRouter,AssignmentAdmission}`. Test: `PlannedRoutingTest`, `OneShotHopGuardE2eTest`, `OneShotHeaderTest`.

**Interfacce:** aggiungere `OffloadGateway.planRoute(InvocationTask task, OffloadContext context): PlannedInvocationRoute`, default LEGACY. Route = LEGACY/LOCAL/REMOTE/REJECT con destinazione definitiva, epoca e assignment. Conservare la route nella vita dell'esecuzione così retry/replay non scelgano un altro peer. Riutilizzare il client HTTP esistente; non sostituire il ramo di gestione dell'offload ordinario.

- [ ] Test: pesi e quote riproducibili, burst finito, overflow va al cloud prima di inviare a un peer; A→B non diventa B→cloud; errore dopo invio non provoca A→cloud come secondo tentativo. Header riservati nel payload funzione non sovrascrivono quelli del control plane.
- [ ] Eseguire RED nel core e nel modulo offload.
- [ ] Implementare routing pesato deterministico con ammissione per assignment e riserva locale/inbound. Metadati HTTP v1: `X-NanoFaaS-Offload-Hop` esistente; `X-NanoFaaS-Offload-Origin`, `X-NanoFaaS-Offload-Epoch`, `X-NanoFaaS-Offload-Assignment`, `X-NanoFaaS-Offload-Version`; risposta `X-NanoFaaS-Execution-Node` soltanto con esecuzione attribuita. Validare nell'HTTP context, non fidarsi degli header annidati nel payload. Richiesta marcata per one-shot con metadati mancanti/incoerenti → errore; offload legacy conserva il proprio contratto. Nessun supporto DFaaS.
- [ ] Eseguire GREEN più regressioni `OffloadHopGuardE2eTest`, `OffloadHeaderLossE2eTest`, `ReactiveInvocationCoordinatorOffloadTest`. Verificare propagazione del nodo esecutore lungo la risposta, inclusi replay; assenza di destinazione inventata per errori prima dell'esecuzione.
- [ ] Commit: `Route invocations through confirmed one-shot assignments`.

## A13 — Packaging e test distribuiti locali completi

**File:** creare `recipes/one-shot-local-jvm.yaml`, `recipes/one-shot-local-native.yaml` seguendo lo schema ricette v2 esistente; creare `platform/modules/offload/src/test/java/it/unimib/datai/nanofaas/modules/offload/oneshot/OneShotLocalClusterE2eTest.java`, fixture in `src/test/resources/one-shot/cluster/`; modificare `.github/workflows/gitops.yml`, `docs/one-shot.md` e i manifest di deployment coinvolti dalle nuove impostazioni.

**Interfacce:** cluster di test = tre edge e cloud terminale, runtime Rust A7, profilo sintetico A1, clock e trace deterministici. Test locali JUnit/container o processi controllati dal test, senza NanoLab e senza provisioning VM. Esporre un task Gradle `:control-plane-modules:offload:oneShotE2e` con risorse limitate e cleanup in ogni uscita.

- [ ] Scrivere test completo: oracle caricato → asta P2P → replica ready → traffico locale/peer/cloud → metriche e conservazione per ID. Scenario di seconda epoca cambia distribuzione, uno rallenta la readiness e un peer sparisce; nessuna doppia esecuzione introdotta dal routing e nessun doppio inoltro.
- [ ] Eseguire RED con `./gradlew :control-plane-modules:offload:oneShotE2e -Precipe=recipes/one-shot-local-jvm.yaml`; indisponibilità del runtime container è un prerequisito mancante, non un PASS.
- [ ] Implementare solo harness, recipe e wiring mancanti. Il test esercita componenti reali, non sostituisce il solver/P2P/attuatore con mock. Le unità isolate dei task precedenti mantengono fake clock e fault injection.
- [ ] Eseguire GREEN con artifact JVM e nativo. Build dello scenario: `./gradlew :control-plane:nativeCompile -Precipe=recipes/one-shot-local-native.yaml` (il controllo di compatibilità dell’artefatto `all` resta separato in A14: il selettore `all` sceglie Kubernetes e non include Docker); il task E2E accetta `-DoneShot.controlPlaneBinary=<percorso-binario>` per lo stesso scenario nativo. Registrare log, versioni e esiti; nessuna soglia prestazionale Azure in CI locale.
- [ ] Commit: `Validate complete one-shot flows with local clusters`.

## A14 — Chiusura della fase NanoFaaS e consegna

**File:** creare `docs/testing/one-shot-phase-a.md`; completare esempi in `docs/contracts/one-shot/`, aggiornare `docs/one-shot.md` e questo piano con evidenze.

**Interfacce:** dossier consumabile da fase B con commit NanoFaaS, digest immagini, schemi v1, API, configurazioni eleggibili, provenienza fixture, comandi ed esiti, limiti noti. Nessuna credenziale o percorso personale obbligatorio negli artefatti.

- [ ] Verificare la matrice della specifica contro A1–A13 e gli scenari del Review Focus. Aggiungere solo regressioni che colmino lacune concrete, senza duplicare i test esistenti.
- [ ] Eseguire i controlli richiesti: `./gradlew releaseChecks -PcontrolPlaneModules=all --continue`, test core-only, suite offload/forecasting/P2P/container runtime, test Rust, scenario E2E JVM/native e controlli di architettura. Conservare eventuali limiti di ambiente come blocchi del gate, non come successo.
- [ ] Misurare a scopo diagnostico tempi solver/asta locali e verificare eventi censurati. Etichettare esplicitamente queste misure come non utilizzabili per scegliere il periodo Azure.
- [ ] Riesaminare diff e contratti; `git diff --check` e GitNexus detect-changes. Il gate è soddisfatto solo se NanoLab non serve a coprire funzionalità mancanti. Pubblicare il dossier e completare i checkbox dell'indice.
- [ ] Commit: `Document verified NanoFaaS one-shot handoff`.

## Copertura e arresto della fase

| Sezioni spec | Task |
| --- | --- |
| 1–4: modello, unità, confini | A1–A6 |
| 5: previsione | A3–A4 |
| 6: solver | A5 |
| 7–8: asta, epoche, guasti | A6, A9–A11 |
| 9: routing e one-hop | A12–A13 |
| 10: repliche/concorrenza | A7–A8, A10 |
| 11–12: strumenti necessari agli esperimenti | A1, A7, A11, A13–A14; workflow Multipass fase B, esperimenti Azure nel lavoro futuro C |
| 13: criteri di accettazione | A14 e gate; verifica dei workflow in B, criteri scientifici Azure rinviati esplicitamente a C |

Dopo A14 fermarsi al passaggio di consegne. La fase B è un lavoro separato nel repository NanoLab, con VM Multipass; nessun avvio automatico di risorse Azure, riservate al futuro lavoro sperimentale C.
