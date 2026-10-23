# One-shot observation readiness implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans in modalità native, task per task. Implementazione autorizzata dall’utente il 13 ottobre, in modalità native. Nessun avvio della campagna, push o merge in questo task.

**Goal:** Correggere il percorso osservazionale e i problemi dell'esperimento emersi da un audit completo, verificarne l'esecuzione come workflow Sonata e consentire conferme incomplete come dati, aumentando soltanto le soglie operative pertinenti.

**Architecture:** Riutilizzare attuatore e routing nativi, raccolta e supervisore NanoLab/Sonata. Separare piano utilizzabile da piena readiness di qualificazione; congelare una sola configurazione dei limiti operativi dell'osservazione, senza modificare le risorse dell'esperimento.

**Tech Stack:** Java 25, Spring/Reactor, Python 3.12, Pydantic, Sonata, k6 e logging/telemetria esistenti; nessuna nuova dipendenza.

**Spec:** [2026-10-13-one-shot-observation-readiness-design.md](../specs/2026-10-13-one-shot-observation-readiness-design.md).

**Revisione del 13 ottobre:** l'ambito comprende tutto il codice che prepara, esegue e analizza l'esperimento, non soltanto readiness e timeout. [Audit iniziale con evidenze e limiti](../../experiments/one-shot-review-2026-10-13/README.md). I problemi già identificati devono avere una regressione; le parti inventariate ma non ancora verificate in profondità vengono chiuse nel Task 0. Nessuna dichiarazione «tutto verificato» basata soltanto sul numero di test.

## Stato dell’implementazione

Codice implementato e verificato offline; [finding e prove finali](../../experiments/one-shot-review-2026-10-13/implementation.md), [copertura](../../experiments/one-shot-review-2026-10-13/coverage.md), [DAG Sonata](../../experiments/one-shot-review-2026-10-13/workflow.md). Freeze05 preparato/verificato; nessuna nuova campagna. L’overhead fisico resta nel precheck del prossimo run. Analisi finale del grafo completa e non parziale; chiusure registrate nel ledger. NanoLab: commit locale6353fb2, runtime NanoFaaS:f3222af4.

## Global constraints

- CPU/RAM VM, funzioni e collector, pool strict e un handler per replica invariati; RAM massima solver 64 MiB invariata.
- Numero round invariato: **8**; nessuna modifica alla matematica delle bid o ai rate delle assegnazioni.
- Warmup **720 s**, nove intervalli **360 s**, carico tarato e topologia invariati.
- Preparazione fisica **10 s**; tutti gli altri valori proposti sono nella tabella della spec e vanno riportati nel prossimo freeze.
- Reject/errori delle richieste, conferme mancanti e campioni mancanti sono osservazioni; nessun gate prestazionale o sullo skew.
- Al primo guasto operativo reale: stop, raw conservati, cleanup delle sole risorse possedute, esito e codice; SIGINT/SIGTERM inclusi; nessuna correzione/retry/relaunch automatici.
- Usare gli script esistenti e la CPU placement della configurazione; archiviare dopo la chiusura degli scrittori.
- Tutte le fasi operative usano il vero engine Sonata. Lo script di ingresso delega al builder del package; supervisione e archiviazione finale restano intorno a `Workflow.run()` per rispettare compensation e quiescenza. Nessun secondo orchestratore.
- Nessun aumento automatico del parallelismo, del numero di peer, della retention o delle code di protocollo: prima distinguere un vero limite di arresto da un parametro che cambia contesa o consumo. Le correzioni devono funzionare con l'hardware approvato.
- GitNexus impact prima di ogni modifica di simboli; UNKNOWN richiede verifica nel sorgente. detect_changes prima di eventuali commit, senza accettare risultati parziali/troncati come verifica pulita.

## Review focus

1. Il seller riceve ACK ma la risposta va persa: il buyer non può affermare che l'ACK non è arrivato né usare un grant senza il riscontro richiesto (Task 1/2).
2. Il seller mantiene un inbound ma il buyer va al cloud: capacità riservata inutilizzata, non falsa violazione di reciprocità (Task 2).
3. I cap innalzati rimangono ridotti da un timeout esterno, dalla frazione 0,20 o dai cap READY hardcoded (Task 1/3).
4. Una misura manca, ma sampler/collector è vivo: registrare il buco; un processo morto o una scrittura impossibile, anche nel supervisore stesso, deve ancora interrompere (Task 2/4).
5. Il nono intervallo termina prima del generatore o viceversa: mantenere raccolta fino al confine previsto, drain coerente, archivi dopo join e raw logging preservati (Task 3/5/6).

## Mappa dei checkout e rischio

NanoFaaS: `/home/michele/Documenti/nanofaas/.worktrees/edge-logging`.
NanoLab: `/tmp/nanolab-one-shot-host-placement`; nei task `NL/` indica questo checkout.
Preservare le modifiche preesistenti. Non spostare il lavoro sul checkout root.

Impact dell'attuatore: **CRITICAL**, 248 simboli upstream e 140 processi secondo
l'indice disponibile; callers diretti comprendono configurazione/operazioni
one-shot. Gli impact completi sono stati conservati prima delle modifiche; la lettura iniziale troncata è stata sostituita e non usata come gate. OneShotSettings è ambiguo,
con candidati CRITICAL/UNKNOWN: scegliere UID esatto e verificare fonte corrente.
Il checkout NanoLab effettivo è stato indicizzato; UNKNOWN corroborati nel sorgente. La ricerca FTS non si è costruita, mentre analisi del grafo/impact/detect_changes sono disponibili. Check finale strutturato:416 simboli,51 file,79 processi, rischio CRITICAL; nessun partial/truncated.

### Task 0: Audit completo e matrice di copertura dell'esperimento

**Files:** audit `docs/experiments/one-shot-review-2026-10-13/`; sorgenti `NL/scripts/one-shot-*.py`, `NL/packages/nanolab/src/nanolab/{one_shot,tasks/one_shot,plans/one_shot*,config/one_shot.py,assets/one-shot,assets/k6/one-shot.js}`; dipendenze effettive di CLI, provisioning, ricette, placement e logging. Contratti Java one-shot/forecast, provider container, SDK Rust e cinque workload coinvolti. Il resto di NanoFaaS è fuori ambito salvo dipendenze raggiunte.

**Interfaces:** inventario per file e hash con stato `reviewed`, `needs-review` o `not-on-observation-path` motivato; registro finding con trigger, impatto, evidenza/riproduzione, task proprietario e test di regressione. Nessuna modifica a codice sperimentale durante la sola pianificazione.

- [x] Completare l'inventario transitivo dal comando effettivo e dal builder CLI qualificato; leggere tutti i file del percorso, inclusi script preparatori/taratura, preset e asset eseguiti nella VM. Marcare esplicitamente legacy, duplicazioni e percorsi non usati; non cancellarli sulla base di zero callers nell'indice.
- [x] Verificare dati e unità fra Python di riferimento, trace, k6, API Java, SDK, sampler e report: rounding/quantum, quantità/rate/richieste, identità e generazioni, epoche, denominatori, cloud diretto, checksum, errori e righe parziali.
- [x] Verificare ogni acquisizione parziale e release Sonata, risorse preesistenti, cancellazione, subprocess/thread, I/O concorrente, deadlock, timeout annidati, scritture fallite e consumo crescente dei raw. Leggere anche gli adapter del provider e le compensazioni effettivamente raggiunte.
- [x] Aggiungere riproduzioni offline dei finding nuovi ai test del task proprietario prima del fix; correggere tutti i bug confermati nel percorso dell'esperimento, non solo quelli elencati oggi. Per dubbi non riprodotti conservare evidenza e limite senza spacciarli per bug certi.
- [x] Chiudere la matrice: nessun file raggiunto ancora `needs-review`; tutti i finding hanno disposizione esplicita. La chiusura è una verifica ingegneristica del software, non un gate prestazionale sui risultati dell'esperimento.

### Task 1: Diagnostica nativa delle assegnazioni e chiusura DEGRADED

**Files:**
- Modify: `platform/modules/offload/src/main/java/it/unimib/datai/nanofaas/modules/offload/oneshot/actuation/PlanActivation.java`.
- Modify: `platform/modules/offload/src/main/java/it/unimib/datai/nanofaas/modules/offload/oneshot/actuation/ReplicaPlanActuator.java`.
- Modify: `platform/modules/offload/openapi.yaml`, `docs/one-shot.md`, `docs/one-shot-coordination.md`.
- Test: `platform/modules/offload/src/test/java/it/unimib/datai/nanofaas/modules/offload/oneshot/actuation/ReplicaPlanActuatorTest.java`, `LogicalReadinessTest.java`, `LogicalProviderTransitionTest.java`, `LogicalAllocationTransitionTest.java` nello stesso package; nuovo `ReadinessObservationTest.java` nello stesso package.

**Interfaces:** `PlanActivation` conserva status/plan/desiredReplicas/readyReplicas/reason/preparedAt e aggiunge diagnostica strutturata delle assegnazioni, con overload del vecchio costruttore per i callers Java. Le righe diagnostiche contengono esattamente i campi e i motivi descritti nella spec. Nessuna modifica alle quantità usate dal routing. Il client NL viene aggiornato nel Task 2 per il campo API aggiuntivo.

- [x] Analizzare impact dei simboli esatti e leggere i callers; salvare il risultato non troncato e avvisare sul rischio CRITICAL prima dell'editing.
- [x] Scrivere RED: READY non ricevuto; risposta READY_ACK persa dopo ricezione seller; replica parzialmente pronta; vicino senza risposta fino alla fine della finestra di conferma. Asserire piano utilizzabile DEGRADED, nessun outbound non autorizzato, residuo cloud conservato e motivo diagnostico corretto.
- [x] Eseguire i test e verificare che riproducano la falsa classificazione FAILED o la diagnostica assente.
- [x] Implementare la diagnostica nei punti già esistenti di invio/ricezione; registrare rifiuto/errore/timeout distintamente. Il termine della finestra di conferma dopo pubblicazione locale sicura restituisce DEGRADED. Il fallimento della transizione fisica, senza piano sicuro, resta FAILED.
- [x] Sostituire i cap READY 500/200 ms nel percorso configurato con `peerTimeout=5 s`, limitato dal residuo e dalle operazioni ancora da eseguire; nessuna attesa oltre expiry, nessun aumento del limite fisico 10 s, nessun nuovo retry.
- [x] GREEN e regressione: memoria strict, readiness prima degli annunci, lease/identità, richieste in coda attraverso cambio epoca e richieste vecchie da vicini ancora eleggibili. Eseguire `./gradlew :offload:test`; il plugin `it.unimib.datai.nanofaas.control-plane-modules` in settings include i moduli.

### Task 2: Proseguire con un piano utilizzabile e registrare la mancata reciprocità

**Files:**
- Modify: `NL/packages/nanolab/src/nanolab/one_shot/client.py`, `report.py`.
- Modify: `NL/packages/nanolab/src/nanolab/tasks/one_shot/qualification.py`, `experiment.py`, `verification.py`.
- Modify: `NL/scripts/one-shot-observe.py`.
- Test: `NL/packages/nanolab/tests/one_shot/test_logical_client.py`, `test_experiment_task.py`, `test_qualification_task.py`, `test_report.py`, `test_observed_assumptions.py`, `test_heterogeneous_evidence.py`; nuovo `test_readiness_observations.py` nella stessa directory.

**Interfaces:** `prepare_parallel_epoch(..., observation_only: bool = False)` mantiene il default della qualificazione. Aggiunge alle righe `executionUsable`, `fullyPrepared`, `measurementComplete`, stato e diagnostica; in observation_only un DEGRADED utilizzabile con durate osservate non è censurato. Misure mancanti restano null. `RunOneShotLoadTask` seleziona il percorso osservazionale soltanto dal manifest `parameters.observationOnly`, non da un generico booleano per disabilitare il timing.

**Interfaces:** `verify_epoch_flows(plans, *, neighbor_graph=None, observation_only=False)` restituisce le riserve inbound inutilizzate come diagnostica nel percorso osservazionale. Non rimuove la conservazione del flusso del buyer né l'autorizzazione degli outbound realmente usati, i controlli di identità, topologia e RAM. Client Pydantic accetta e valida il nuovo campo diagnostico; archivi precedenti senza quel campo restano leggibili.

- [x] RED: task con DEGRADED, desired==ready e piano valido prosegue; ripetere con ready<desired ma piano sicuro; misure reali finite conservate; metodo qualificato resta separato.
- [x] RED: seller inbound presente/buyer outbound assente produce `unusedReservation`, non arresto; outbound usato senza autorizzazione o fuori topologia produce ancora errore.
- [x] Implementare separazione delle tre proprietà senza mascherare FAILED/null plan. Rimuovere la dipendenza osservazionale da piena readiness e censura della qualificazione.
- [x] Metriche timer/proof diagnostiche mancanti: conservare raw/errore e marcare misura incompleta, senza invalidare un piano utilizzabile. Errori di scrittura o processi morti restano guasti della supervisione.
- [x] Estendere report e summary: per epoca/nodo/funzione contare assegnazioni/quantità negoziate, confermate, senza riscontro, riserve inutilizzate; mostrare cloud previsto e richieste cloud effettive separatamente. Non contare quantità come messaggi o rate come richieste realmente eseguite.
- [x] Separare nell'esito e nel report `operational outcome`, stato del protocollo e completezza dei dati: lateness, deficit di generazione, drift e reject non devono risultare guasti operativi solo perché un validatore qualificato marca `evidence.valid=False`.
- [x] GREEN: client vecchi archivi, error/reject denominatori, misura mancante senza valori inventati, report osservazionale completo anche DEGRADED; qualificazione non dichiarata riuscita.

### Task 3: Un solo profilo congelato delle soglie di arresto

**Files:**
- Modify: `NL/packages/nanolab/src/nanolab/config/one_shot.py`.
- Modify: `NL/scripts/one-shot-observe.py`, `NL/packages/nanolab/src/nanolab/plans/one_shot_common.py`.
- Modify: `NL/packages/nanolab/src/nanolab/tasks/one_shot/qualification.py`, `experiment.py`, `logging.py`, `clock.py` e `one_shot/client.py`, `vm_resources.py`, `vm_sampler.py`.
- Modify: `platform/modules/offload/src/main/java/it/unimib/datai/nanofaas/modules/offload/oneshot/api/OneShotSettings.java`, `platform/modules/offload/src/main/java/it/unimib/datai/nanofaas/modules/offload/oneshot/coordination/EpochSettings.java`; allineare `platform/modules/offload/openapi.yaml` per le frazioni.
- La retention in `OffloadConfiguration.java` resta invariata; l'export incrementale riusa il collector NanoLab e gli endpoint eventi esistenti.
- Test: `NL/packages/nanolab/tests/one_shot/test_first_observation.py`, `test_supervision.py`, `test_resources.py`, `test_monotonic_resources.py`; test Java di settings/configurazione pertinenti.

**Interfaces:** aggiungere nello stesso `config/one_shot.py` un modello Pydantic `OperationalLimits` e il campo opzionale `OneShotConfig.operational_limits` (alias `operationalLimits`, default None). Il runner osservazionale dichiara i valori della spec; gli helper li ricevono dalla configurazione esistente. `operator-protocol.json`, scenario e manifest contengono i valori effettivi. Nessun framework di policy né configurazioni duplicate.

- [x] RED: generare un freeze con gli input conservati senza VM; controllare ogni valore della tabella, `maxRounds=8`, hardware/quote/pool/carico invariati, nessun cap esterno più basso del proprio figlio che debba completare.
- [x] RED: compilare e chiamare i record Java effettivi; accettare frazione 1, asta 60, solver/peer 5, prep 10 e lead 90. Rifiutare frazioni non finite/non positive/>1 e somme incompatibili con lead/period, preservando integrità aritmetica.
- [x] Implementare i valori da un'unica fonte, aggiornando i bound 0,20 in Java e nel validatore dello script a 1,00. Non usare la frazione o le durate misurate come gate prestazionale dell'osservazione.
- [x] Correggere il finding A01: `ConfigureOracle.run` deve usare il lead del manifest/protocollo congelato, eliminando `lead=8`. Testare il payload realmente costruito dal task e accettato dal contratto Java, non soltanto un record Java costruito con costanti nel test: oggi prep=10/lead=8 è rifiutato, mentre freeze/manifest dichiarano 14.
- [x] Propagare API 180 s e tutti i timeout startup/collection/join del profilo ai processi effettivi: niente costanti hardcoded inferiori residue. Non modificare timeout/code/burst delle richieste misurate, quote RAM/CPU, numero VU o numero handler.
- [x] Portare l'allowance del generatore a 180 s nel profilo: k6, attesa load task, telemetria, drain e validità ORACLE condividono la stessa formula. Eliminare il termine hardcoded `+35` del runner e la durata `720 + finestre + 180` del sampler (A07). Asserire ORACLE 4.230 s per questo scenario; derivare durata relativa dalla scadenza monotona effettiva di ciascun processo, così startup e setup non accorciano la raccolta.
- [x] La lateness nell'osservazione è già diagnostica (A11): conservarne valore e distribuzione senza elevarla a 86.400 s o convertirla in arresto. Peer/parallelismo/code restano quelli attuali, salvo finding concreto di un limite procedurale e motivazione documentata.
- [x] Conservare retention runtime 10.000; il collector esistente esporta incrementalmente gli eventi quando necessario e segnala eventuali buchi. La paginazione può crescere fino a 1.000 pagine × 100 con arresto su fine stream e cursore non avanzante. Non aumentare la memoria del processo per compensare un export incompleto.
- [x] Completare una tabella di disposizione per **ogni** riga del censimento del 13 ottobre: aumento; solo osservazione; disabilitato/non usato; risorsa sperimentale fissa; integrità. Non restano soglie di esclusione implicite né incrementi indiscriminati RAM/CPU.
- [x] GREEN: freeze/config reali, limiti accettati dal runtime, test startup/cleanup; proprietà legacy default None invariata. Verificare offline il precheck che registra l’overhead; la misura fisica del profilo aggiornato avviene prima del warmup del prossimo run, senza nuovi limiti e senza avviare una campagna in questo task (ruling nel ledger).

### Task 4: Composizione Sonata e supervisione di tutte le risorse

**Files:** creare `NL/packages/nanolab/src/nanolab/plans/one_shot_observation.py` e `NL/packages/nanolab/src/nanolab/tasks/one_shot/observation.py` spostando builder/task già presenti nello script; modificare `NL/scripts/one-shot-observe.py`, `one_shot/supervision.py`, `plans/one_shot_common.py`, `tasks/one_shot/logging.py`; test `test_first_observation.py`, `test_supervision.py`, `test_logging.py`, `test_owned_load_resources.py`.

**Interfaces:** `build_one_shot_observation_plan(config: ScenarioConfig, environment: EnvironmentConfig, *, freeze_dir: Path, run_dir: Path, supervision: CampaignSupervision) -> Workflow`. Il comando esistente verifica gli input e chiama questo builder; task osservazionali riusano `RunOneShotLoadTask`, preflight, clock e risorse esistenti. `CampaignSupervision.run(workflow.run)` possiede soltanto supervisione, esito e archivio finale dopo le release. Nessuna sostituzione del builder qualificato con la policy osservazionale.

- [x] RED A03: fallisce la scrittura heartbeat/status e poi anche quella dell'evento di errore; la procedura viene comunque interrotta, la prima causa è conservata, compensation eseguita e uscita non zero. Coprire errore stdout/BrokenPipe e guasto durante la registrazione iniziale; registrare su canale ancora disponibile senza dipendere dallo stesso I/O guasto per inviare l'interruzione.
- [x] RED A05: arresto di control-plane, Fluent Bit e backend Loki posseduto in bootstrap completato, warm instances, precheck, warmup e intervalli. Collegare la sorveglianza al lifetime della risorsa, non all'avvio tardivo della telemetria; endpoint esterno non posseduto resta distinto. Nessun riavvio automatico.
- [x] RED: inventario Docker completo prova assenza del control-plane mentre una funzione ha un errore di lettura cgroup; l'errore non maschera l'assenza. Se l'inventario stesso manca, registrare osservazione sconosciuta, senza dedurre processo morto (A05).
- [x] Spostare soltanto la composizione e i task già esistenti nel package; mantenere `Resource.requires`, release, compensate e ordine di dipendenza. Gli hook di salute non eseguono I/O lento sotto il lock comune del supervisore: usare gli stati acquisiti dagli adapter e timeout operativi dichiarati.
- [x] GREEN: workflow compilabile, task ed eventi reali, nessuna risorsa acquisita dal solo builder, cleanup delle sole risorse possedute una volta per ownership. Nessun test sostituisce il metodo `Workflow.run` per dimostrare la corretta esecuzione.

### Task 5: Integrità dei risultati, grafici e fingerprint completo

**Files:** `NL/packages/nanolab/src/nanolab/assets/k6/one-shot.js`, `one_shot/{returned_errors,report,vm_resources}.py`, `tasks/one_shot/{observation,logging,artifacts}.py`, `NL/scripts/one-shot-observe.py`; test `test_returned_errors.py`, `k6_harness.mjs`, `test_report.py`, `test_resources.py`, `test_logging.py`, `test_first_observation.py`.

**Interfaces:** ogni invocazione emessa conserva un evento di risultato per ogni risposta ricevuta, anche non conforme; `manifest.parameters.vmTelemetry` contiene la policy effettiva congelata. Report HTML/JSON di run completo e fallito mostrano le sei VM e repliche, i campioni mancanti e la correlazione; gli archivi raw restano la fonte. Inventario fingerprint comprende tutti gli asset realmente letti, oltre a package e ricetta.

- [x] RED A06: eseguire il JS reale nell'harness con JSON `null`, array, numero, stringa, JSON malformato, 429/500, timeout e corpo vuoto. La serializzazione del risultato non deve lanciare eccezioni su `body.executionId`; conservare corpo/status/errori di trasporto e classificazione separata, senza retry.
- [x] RED A04: il manifest prodotto dal vero `ObservationFreeze` attiva i grafici; con raw CPU/RAM e dati parziali il report contiene serie VM/container/throttling per tutti i sei nodi. Archivi vecchi senza flag: usare policy/raw presenti come evidenza, dichiarando l'assenza nel manifest senza modificarlo.
- [x] RED A08: dopo prepare, cambiare un asset logging o una definizione funzione esterna al package; il run deve usare la copia congelata o fermarsi prima di provisioning per deriva, mai usare nuovi bytes sotto il vecchio fingerprint. Includere bootstrap/recipe assets, dipendenze effettive e script di ingresso; controllare anche file non tracciati rilevanti. Conservare esclusioni esplicite per soli documenti.
- [x] RED A09: export Loki raggiunge il limite, più stream hanno timestamp identici, oppure il backend non risponde in teardown; conservare raw recuperabili prima di eliminare i volumi posseduti. Esportare dal backend arrestato dopo i collector o paginare completamente con deduplicazione e cursor progress; un export parziale va dichiarato, non scambiato per tutti i log. Testare che il cleanup non cancelli l'unica copia senza un esito esplicito di fallimento di conservazione.
- [x] GREEN: somme categorie/denominatori, eventi duplicati o parziali, cloud diretto distinto dall'offload, funzioni/generazioni/repliche corrette, nessuna scrittura parallela durante copia/verifica. Non trasformare un errore di richiesta in guasto del runner.

### Task 6: Esecuzione Sonata simulata fino alla fine, failure e archiviazione

**Files:**
- Test: `NL/packages/nanolab/tests/one_shot/test_observation_workflow.py` e `test_observation_final_regressions.py`.
- Modify only if needed: `NL/packages/nanolab/tests/one_shot/test_supervision.py`, `test_first_observation.py`; correggere solo le integrazioni necessarie emerse dai test.
- Docs: `NL/docs/one-shot-workflows.md`, `NL/docs/one-shot-logging.md`; censimento budget del 13 ottobre, piano della campagna e ledger esistenti.

**Interfaces:** invocare il builder del Task 4 e davvero `Workflow.run()` con risorse Sonata simulate e clock monotono simulato. Eseguire task reali `WarmInstances`, `ObserverPrecheck`, `ObservationFreeze`, `ConfigureOracle`, `RunOneShotLoadTask`, report; simulare solo I/O VM/Docker/API, processi e tempo. Non mockare interi task o `prepare_parallel_epoch`; verificare i payload effettivi. Test separati esercitano JS e sampler reali. Nessuna VM.

- [x] RED: tutti i nove intervalli, almeno due nodi DEGRADED e una risposta ACK persa; stdout/status/summary registrano progresso e risultato, nessuna interruzione per mancate conferme. Campioni VM/container/throttling e error/reject rimangono correlati.
- [x] RED: sampler o collector termina durante una wait; prima causa conservata, stop generatore, raw parziale salvato, cleanup una volta delle sole risorse possedute; niente retry.
- [x] RED: SIGINT/SIGTERM durante warmup, intervallo e archiviazione; exit 130/143, scrittori chiusi prima della copia, SHA archive verificabili. Coprire nono intervallo/generatore completato in anticipo e drain tardivo.
- [x] Verificare `Resource.requires` reali nel workflow compilato, non solo che il test termini senza VM. GREEN dei nuovi scenari e delle regressioni di supervisione.
- [x] Test negativo: rimuovere deliberatamente una dipendenza clock/telemetria dal resource che la usa e asserire l'errore Sonata; il wrapper di supervisione non la reinserisce implicitamente. Salvare un inventario leggibile task → dipendenze → release per tutte le fasi, distinguendo la preparazione offline dall'esecuzione.
- [x] Eseguire `.venv/bin/python -m pytest --no-cov packages/nanolab/tests/one_shot`, poi suite NanoLab richiesta dal repository con reference/source effettivi abilitati. Eseguire Ruff/basedpyright con la configurazione del package e verifiche Java appropriate ai simboli modificati. Nessuna affermazione di successo senza output fresco.
- [x] Aggiornare documentazione: comando unico supervisionato, DEGRADED come esito, qualità delle misure separata, soglie operative effettive e risorse invarianti. Non chiamare questi valori «tempi misurati».
- [x] Graph change analysis non parziale; ricostruire distribuzione tramite script esistente, verificare source/distribution, preparare **nuovo** freeze e verificarne tutti gli hash. Preservare freeze04 e raw. Non lanciare la campagna come effetto dei test o della preparazione del freeze.

## Done del lavoro pianificato

Matrice audit completa, tutti i finding confermati risolti e coperti da regressioni;
campagna simulata eseguita dal builder reale Sonata completa nonostante conferme mancanti, capacità sicura
rispettata, diagnostica utile a posteriori, soglie della procedura esplicite
e coerenti, veri guasti ancora gestiti automaticamente, risorse CPU/RAM e
8 round invariati, nuovo fingerprint verificato. L'avvio della campagna reale
sarà il passo successivo esplicito, con lo stesso comando supervisionato.


Chiusura offline:859 test one-shot senza skip,4808 NanoLab con3 skip non applicabili,158 Java,110 SDK Rust seriali e4 workload; Ruff/basedpyright verdi. Freeze05 verificato, fingerprint `f766a97752bfbcacb6a0924c8a77b9293e06e2f0e183808f510644c1974aff81`. Nessuna qualificazione fisica o campagna reale dichiarata.
