# Issue #238 — Preparazione alla pubblicazione: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rendere riproducibili i controlli di rilascio e migliorare la manutenibilità preservando comportamento, prestazioni e invarianti di concorrenza.

**Architecture:** Procedere per cinque incrementi revisionabili: gate CI, corpus runtime, contratto errori del control plane, profili, documentazione delle responsabilità del motore. Riutilizzare i confini esistenti e mantenere un solo proprietario dello stato; un ADR motivato che escluda ulteriori estrazioni è un esito valido.

**Tech Stack:** Java 25, Gradle, Spring, JUnit/ArchUnit; Python >=3.12 e pytest; SDK Java/Java-lite/Python/Go/JavaScript/Rust; GitHub Actions, SpotBugs, Helm.

**Spec:** [Issue #238](https://github.com/miciav/nanofaas/issues/238), riconvalidata su `ef856960e99a6c56b93be6a978965535f7a3eba7`; coordinamento con [#240](https://github.com/miciav/nanofaas/issues/240) e [#216](https://github.com/miciav/nanofaas/issues/216). Piano redatto il 4 ottobre 2026 sulla stessa revisione locale; le decisioni proposte qui richiedono revisione prima dell'implementazione.

## Global Constraints

- Preservare comportamento, prestazioni e invarianti di concorrenza; la normalizzazione dei body di errore è la modifica di contratto esplicitamente richiesta e va documentata.
- Un solo costruttore di sequenze custom con `peak_vus=20` predefinito; target dei preset derivati dalla rappresentazione esistente.
- Non aggiungere astrazioni superflue, riscrivere il motore o introdurre più proprietari dello stesso stato.
- Non riaprire le ottimizzazioni misurate e scartate in #216 senza nuove evidenze; nessun aggiornamento di dipendenze incluso implicitamente.
- La migrazione del tooling di misura appartiene a #240 e ha destinazione NanoLab. NanoFaaS non deve dipendere in produzione dal framework di benchmark.
- Preservare modifiche locali e file non tracciati; prima di implementare scegliere un checkout pulito, senza ripristinare il lavoro altrui.
- Applicare le istruzioni AGENTS.md della revisione di lavoro: analisi GitNexus prima delle modifiche ai simboli e controllo delle modifiche del grafo prima dei commit. Il launcher locale `.gitnexus/run.cjs` non era disponibile durante questa pianificazione: nessuna analisi d'impatto è stata dichiarata completata.

## Review Focus

1. Gate formalmente presenti ma non eseguiti, suite con zero test o skip ambientali: il task 1 deve rendere questi casi visibili e impedire falsi verdi.
2. Serializzazione fallita dopo l'avvio dell'handler: il task 2 deve verificare risposta, callback di errore e rilascio effettivo delle prenotazioni.
3. Callback rifiutata o bloccata in I/O: il task 2 deve distinguere admission e delivery, preservando identità del tentativo e timeout finiti.
4. Errori restituiti direttamente dai controller, body vuoti e serializzazione native: il task 3 deve coprire queste vie, oltre all'exception handler.
5. Arrotondamenti e durate minime dei profili; gare fra timeout, completamento e rimozione nel motore: caratterizzazione nei task 4 e 5.

## Sequenza e confini

Ordine consigliato: **1 → 2 → 3 → 4 → 5**, con un commit coerente per task e PR separate per i primi quattro incrementi. Il task 5 chiude insieme commenti operativi e decisione sulle estrazioni. Nessuna migrazione verso NanoLab è eseguita da questo piano: se #240 ha già trasferito un file, il task 4 si applica alla sua unica implementazione di destinazione e il task 1 adegua i gate alla nuova titolarità.

Prima di iniziare ogni task, riconvalidare i percorsi sulla revisione corrente e annotare le differenze rispetto a `ef856960`. Non assumere che il README del corpus descriva lo stato attuale: contiene inventari storici P16a, mentre nel checkout sono presenti anche harness eseguibili.

### Task 1 — Rendere effettivi e riproducibili i gate di rilascio

**Files:** modificare `.github/workflows/gitops.yml`, `build.gradle`, `config/spotbugs/exclude.xml`, `docs/testing.md`; creare `scripts/tests/test_release_gates.py`; aggiungere `config/spotbugs/baseline.xml` soltanto se il censimento identifica debito reale da mantenere temporaneamente.

**Interfaces:** conservare `verifyHelmVersionSync`; introdurre il task Gradle radice `releaseChecks`, invocabile sia localmente sia dalla CI, che dipende dal controllo versioni e da `spotbugsMain` nei progetti selezionati. Le suite Python hanno comandi espliciti, senza nascondere requisiti di sistema in Gradle.

- [ ] Censire i comandi già presenti in CI e prerequisiti delle suite escluse. Produrre in `docs/testing.md` una tabella comando/toolchain/dipendenze/artefatti. Conservare le composizioni P2P, core-only, sync-queue, i test SDK e la verifica dell'eseguibile native.
- [ ] Aggiungere `test_release_gates.py` con asserzioni sui comandi obbligatori del workflow e sul collegamento del controllo Helm al gate. Eseguire il test prima delle modifiche: deve fallire per i gate mancanti.
- [ ] Aggiungere le suite `scripts/tests`, `experiments/tests` e `tools/fn-init/tests`, con Python 3.12, pytest, PyYAML, pacchetto fn-init installato e tool esterni effettivamente richiesti (in particolare Helm/Node). Comandi base da validare su ambiente pulito:

  ```bash
  uv run --python 3.12 --with pytest --with pyyaml python -m pytest scripts/tests experiments/tests -ra
  uv run --project tools/fn-init --group dev python -m pytest tools/fn-init/tests -ra
  ./gradlew releaseChecks -PcontrolPlaneModules=all --continue
  ```

- [ ] Eseguire SpotBugs per censire i risultati. Conservare le esclusioni motivate dei falsi positivi; separare l'eventuale baseline del debito con pattern/classe/metodo, motivazione e follow-up. Impostare `ignoreFailures=false` per il gate e bloccare ogni nuova violazione non esclusa. Non allargare filtri a interi package. Il passaggio graduale consiste nel ridurre la baseline, non nel mantenere il gate non bloccante. Error Prone resta nel suo assetto corrente in questo incremento.
- [ ] Verificare in una copia temporanea che un mismatch Helm/Gradle produca exit nonzero e che un nuovo finding SpotBugs fuori baseline faccia fallire il gate; ripristinare le sole perturbazioni di prova. Verificare inoltre che il task non diventi NO-SOURCE per errore di selezione dei moduli.
- [ ] Eseguire le suite da checkout pulito, rendere espliciti gli skip e archiviare report anche in caso di errore. Configurare un esito aggregato stabile, per esempio `release-gate`, che non accetti job falliti o saltati inaspettatamente. Documentare separatamente l'attivazione di questo status nelle branch rules: il solo YAML non rende obbligatorio un check per il merge.
- [ ] Commit proposto: `Make release checks reproducible and blocking`.

**Accettazione:** gli stessi comandi passano localmente e in CI; le perturbazioni negative falliscono; nessuna suite viene persa con la migrazione #240. Eventuali failure preesistenti sono classificate e risolte o coordinate con le issue pertinenti prima di dichiarare il gate verde, senza disabilitare test.

### Task 2 — Estendere il corpus runtime e provarlo sui runtime reali

**Files:** modificare `sdks/runtime-contract/{saturation-wire-corpus.json,validate_saturation_wire_corpus.py,test_validate_saturation_wire_corpus.py,README.md}` e gli adattatori esistenti:

- Java e Java-lite: rispettivi `SaturationRuntimeHarness.java`, `SaturationCorpusContractAssertions.java`, `SharedSaturationWireCorpusTest.java` e test di mutazione sotto `sdks/{java,java-lite}/src/test/java/it/unimib/datai/nanofaas/sdk/`.
- Python: `sdks/python/tests/runtime_corpus_adapter.py` e `test_saturation_wire_corpus.py`.
- Go: `sdks/go/nanofaas/saturation_runtime_adapter_test.go` e `saturation_wire_corpus_test.go`.
- JavaScript: `sdks/javascript/test/runtime-saturation-corpus.test.ts` e `saturation-wire-corpus.test.ts`.
- Rust: `sdks/rust/src/corpus_tests.rs`, per i comportamenti applicabili e rappresentabili dal runtime.

**Interfaces:** mantenere `contractDefinitions` come unica autorità delle attese. Ogni nuovo scenario definisce outcome HTTP, envelope callback, identità, osservazioni, deadline e contatori finali; gli adattatori eseguono tali dati senza copie locali della policy.

- [ ] Costruire una matrice caso/runtime/stato esistente. Caratterizzare gli esiti attuali prima di fissare codici non specificati dalla issue: un disaccordo fra runtime è una decisione di contratto o un bug da tracciare, non un valore atteso diverso nascosto nell'adattatore.
- [ ] Aggiungere casi nominati `envelope-serialization-failure`, `callback-http-rejected`, `ingress-io-timeout` e `callback-io-timeout`. Riutilizzare i casi già presenti per callback saturation e delivery exhaustion. Per errori di serializzazione non rappresentabili da un tipo pubblico, documentare l'applicabilità e usare un fault nel serializer solo se percorre un ramo reale; non modificare un'API di produzione soltanto per il test.
- [ ] Fissare per ciascun caso la tripla HTTP/callback/risorse: nessun successo dopo serialization failure; errore callback serializzabile quando previsto; nessuna seconda risposta HTTP se la delivery fallisce dopo la risposta; timeout finiti; contatori a zero dopo rilascio fisico. Il numero dei tentativi e la policy sui non-2xx devono provenire dal contratto caratterizzato, non da assunzioni nuove.
- [ ] Aggiungere test negativi al validatore: body incoerente con l'outcome, callback mancante, attempt alterato, deadline non finita o contatore finale non drenato devono essere rifiutati. Eseguire prima degli adattamenti e verificare il fallimento previsto.
- [ ] Estendere gli harness con server callback locali e barriere/eventi. Verificare l'identità stabile dell'esecuzione e del dispatch attempt durante i retry della callback e l'assenza di redispatch autonomo. Per I/O reale usare endpoint che rifiutano o trattengono la risposta; gli sleep non costituiscono prova dell'ordine degli eventi.
- [ ] Eseguire `python3 -m pytest sdks/runtime-contract/test_validate_saturation_wire_corpus.py`, `./gradlew :sdks:java:test :sdks:java-lite:test`; dai rispettivi SDK, i comandi CI `uv run --extra test --with-editable . python -m pytest tests/`, `go test ./...`, `npm ci && npm test`, `cargo test`. Registrare la copertura dei singoli scenari, distinguendo parsing della fixture e conformità runtime eseguita.
- [ ] Aggiornare il README separando policy vigente, matrice di conformità e inventario storico. Commit: `Extend shared runtime failure contracts`.

**Accettazione:** ogni nuovo caso è eseguito nei runtime applicabili; esclusioni motivate, nessuno skip silenzioso e nessuna pretesa di conformità basata sul solo parsing del JSON.

### Task 3 — Uniformare gli errori HTTP del control plane

**Files:** modificare `platform/control-plane/src/main/java/it/unimib/datai/nanofaas/controlplane/api/{FunctionController,GlobalExceptionHandler}.java`, creare `ApiErrorResponses.java` nello stesso package; modificare i test `FunctionControllerTest`, `FunctionControllerReplicaTest`, `GlobalExceptionHandlerTest`, `openapi/core.yaml` e `docs/control-plane.md`. Estendere agli altri controller/frammenti OpenAPI solo per i rami HTTP di errore individuati dall'inventario.

**Interfaces / decisione proposta:** riusare la forma già emessa dal GlobalExceptionHandler: `{"error":"CODE","message":"..."}` con `details` per gli errori di validazione. Il protocollo runtime `/invoke` e gli envelope di esecuzione/callback conservano la struttura annidata `error.code/error.message`: sono contratti distinti, da documentare esplicitamente. Nessuna conversione dei payload applicativi.

Introdurre in `ApiErrorResponses` i factory statici `body(String code, String message): Map<String,Object>` e `validationBody(List<String> details): Map<String,Object>`, con mappe ordinate e immutabili. Evitare un nuovo DTO con costo di configurazione AOT se non necessario.

- [ ] Inventariare risposte dirette, eccezioni e consumer tramite GitNexus, quindi conferma testuale per accessi dinamici o esiti UNKNOWN. Salvare nel test una matrice endpoint/status/body/header; includere registrazione, get, update, replicas e delete.
- [ ] Aggiungere test che richiedano JSON strutturato per gli attuali body stringa e i 404/409 vuoti; eseguire e osservare il fallimento. Proposta di codici: `BAD_REQUEST` per argomento non valido, `SERVICE_UNAVAILABLE` per indisponibilità già mappata a 503, `FUNCTION_NOT_FOUND` e `FUNCTION_ALREADY_EXISTS` per quei rami vuoti. Preservare codici specifici esistenti, status, header e details di validazione.
- [ ] Centralizzare solo la costruzione del body. Preservare la classificazione contestuale delle eccezioni: non mappare globalmente ogni `IllegalStateException` a 503. Aggiornare le firme dei controller quando il body prima assente rende necessario un tipo di ritorno più ampio; mantenere 204 senza body.
- [ ] Documentare uno schema `ApiError` in `openapi/core.yaml` e referenziarlo per gli esiti coperti, inclusi i frammenti opzionali pertinenti. Aggiornare i consumer effettivi che analizzano stringhe o body vuoti, ciascuno con test del parser, e una nota di compatibilità sul cambio di formato. Gli SDK di esecuzione non vanno modificati se non consumano questi endpoint.
- [ ] Eseguire `./gradlew :control-plane:test --tests '*FunctionController*Test' --tests '*GlobalExceptionHandlerTest' --tests '*FunctionResponseContractTest'`; ripetere le composizioni core-only/all tramite i comandi CI del task 1. Verificare in un artefatto native registrazione invalida e funzione assente tramite NanoLab, oltre alla compilazione: il successo di nativeCompile da solo non prova la serializzazione HTTP.
- [ ] Commit: `Standardize control-plane API error responses`.

**Accettazione:** nessuna risposta d'errore control-plane in ambito rimane testo libero o body vuoto; OpenAPI e consumer concordano sul formato. Contratti di esecuzione/callback e risposte di successo invariati.

### Task 4 — Semplificare i profili senza cambiarne la semantica

**Files:** `experiments/lib/loadtest_registry_config.py`, `experiments/tests/test_loadtest_registry_config.py`; se già migrati nella #240, i corrispondenti file proprietari in NanoLab identificati dalla mappa di migrazione.

**Interfaces:** conservare `build_stage_sequence(profile: str, custom_total_seconds: int | None = None, max_vus: int | None = None) -> str`; unificare gli helper in `_build_custom_sequence(custom_total_seconds: int, peak_vus: int = 20) -> str`. Conservare `_PRESET_STAGE_SEQUENCES` come unica fonte e `_scale_targets` per gli arrotondamenti.

- [ ] Aggiungere test di caratterizzazione con queste asserzioni, oltre ai preset già coperti:

  ```python
  assert build_stage_sequence("custom", 30) == "5s:5,10s:10,12s:20,5s:20,5s:0"
  assert build_stage_sequence("custom", 120) == build_stage_sequence("custom", 120, 20)
  assert build_stage_sequence("custom", 30, 1) == "5s:1,10s:1,12s:1,5s:1,5s:0"
  assert build_stage_sequence("standard", max_vus=30) == "10s:8,30s:15,30s:30,30s:30,10s:0"
  ```

  Coprire inoltre durata 29/30/31, picchi 0/-1/1/20, profilo ignoto e normalizzazione spazi/maiuscole. I 37 secondi effettivi del custom da 30 sono comportamento preesistente: non correggerli in questo refactor.
- [ ] Eseguire `python3 -m pytest experiments/tests/test_loadtest_registry_config.py`: i nuovi test di caratterizzazione devono passare già prima del refactor.
- [ ] Rimuovere `_build_custom_sequence_with_peak` e `_PRESET_STAGE_TARGETS`, dopo impact analysis. Derivare durate e target mediante split delle sequenze canoniche; preservare `round`, minimi e validazione pubblica senza introdurre un registry o nuove classi.
- [ ] Ripetere la suite specifica e `python3 -m pytest experiments/tests`; confrontare output prima/dopo su una matrice di durate valide 30–600, tutti i preset e picchi 1–100. Richiedere uguaglianza esatta delle stringhe e delle eccezioni per gli input invalidi caratterizzati.
- [ ] Annotare nella consegna per #240 il commit e i test da trasferire; non mantenere due implementazioni. Commit: `Deduplicate load profile construction`.

### Task 5 — Preservare gli invarianti e separare la cronologia

**Files:** commenti di `platform/execution-runtime/src/main/java/it/unimib/datai/nanofaas/execution/{SchedulerEngine,AttemptCoordinator}.java`, `.github/workflows/gitops.yml`, `build.gradle`, README runtime; creare `docs/architecture/adr-238-engine-boundaries.md` con link alle evidenze già presenti in `docs/experiments/`.

**Interfaces:** invariati `SchedulerEngine`, `AttemptCoordinator`, `AttemptTransport`, `AttemptObserver` e gli attuali proprietari di lease, pending work e terminalizzazione. In questo task la scelta predefinita è conservare la struttura; un'estrazione eventuale richiede una proposta circoscritta con test e misure prima di entrare nell'implementazione.

- [ ] Documentare responsabilità, proprietà dello stato, punti di linearizzazione e ordine dei lock leggendo le classi e i relativi test. Confrontare: mantenimento attuale; estrazione di una funzione pura senza stato; estrazione con trasferimento dello stato. Scartare quest'ultima se duplica ownership o sincronizzazione.
- [ ] Controllare che `SchedulerSwitchRaceTest`, `SchedulerEngineRemoveAllForGateDisciplineTest`, `SchedulerEngineDeadlineGuardRegressionTest`, `AttemptCoordinatorTest` e `RuntimeArchitectureTest` coprano i confini proposti. Se manca una gara rilevante, aggiungere un test con barriere per callback tardiva contro timeout/rimozione: un solo esito terminale, lease rilasciata una sola volta e nessun effetto su una nuova generazione.
- [ ] Lasciare nel codice invarianti, ordine dei lock, responsabilità di rilascio, vincoli AOT e motivazioni dei pin. Spostare nel nuovo ADR soltanto cronologie, misure e riferimenti a task conclusi; riportare sorgente, revisione e link alle evidenze originali. Non riscrivere risultati grezzi, checksum o provenienza.
- [ ] Eseguire `./gradlew :execution-runtime:test` e i test ArchUnit del control plane (`./gradlew :control-plane:test --tests '*CoreArchitecture*Test'`). Revisionare il diff per verificare che il riordino dei commenti non cambi eseguibili o configurazioni.
- [ ] Registrare la decisione nell'ADR. Se si propone un'estrazione reale, richiedere prima un confronto baseline/candidato sullo stesso hardware e configurazione, con warm-up e almeno cinque repliche alternate: throughput, p50/p95/p99, errori, allocazioni e memoria. Congelare soglie dal rumore della baseline prima di leggere i risultati del candidato; niente soglia inventata a posteriori. Eseguire le campagne tramite NanoLab e archiviare revisioni/configurazioni/output. Nessun benchmark è necessario per modifiche limitate ai commenti.
- [ ] Commit: `Document engine ownership and preserve operational comments`.

## Criteri di chiusura e verifica del piano

- [ ] Tutti e sei gli interventi della issue hanno una consegna: gate (1), corpus (2), API (3), profili (4), commenti ed estrazioni motivate (5).
- [ ] Gate riproducibili da checkout pulito; branch rules verificate separatamente se si dichiara che i controlli sono obbligatori per il merge.
- [ ] Report dei test, matrice runtime, contratto API e nota di compatibilità disponibili insieme al codice.
- [ ] Una sola implementazione dei profili, con raccordo documentato alla #240.
- [ ] ArchUnit e regressioni pertinenti verdi; ADR sul motore presente anche se nessuna estrazione viene effettuata.
- [ ] Ogni modifica effettiva a un percorso sensibile alla latenza accompagnata da misure comparabili. Nessuna modifica ai percorsi caldi introdotta sotto forma di pulizia dei commenti.

**Stato della pianificazione:** issue e collegamenti letti; revisione locale verificata; sorgenti e test pertinenti ispezionati; copertura dei sei requisiti ricontrollata. I comandi nel piano sono verifiche da eseguire durante l'implementazione, non test già eseguiti. Non sono stati modificati codice, issue o configurazione GitHub.
