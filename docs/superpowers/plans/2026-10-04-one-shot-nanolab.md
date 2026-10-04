# One-shot NanoLab — Phase B Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** dopo la consegna NanoFaaS, implementare in NanoLab i workflow Sonata per calibrare, qualificare e confrontare one-shot su Azure.

**Architecture:** NanoLab orchestra VM, deployment, carico e artefatti; ogni operazione appartiene a un task o Resource Sonata. Le decisioni dell'asta e l'esecuzione delle funzioni appartengono interamente a NanoFaaS. Gli esperimenti consumano una calibrazione già completata e una qualificazione temporale distinta.

**Tech Stack:** Python >=3.12, NanoLab, `sonata-engine`/`sonata-tasks` e provider Azure già presenti, HTTPX, k6 e report Plotly esistenti. Riferimento NanoLab letto: `76d45216487432e4dd0e7f50b22ae08363883af8`; verificare HEAD e API prima dell'esecuzione.

**Spec:** [specifica](../specs/2026-10-04-one-shot-offload-design.md), [gate A → B](2026-10-04-one-shot-implementation.md), [piano NanoFaaS](2026-10-04-one-shot-nanofaas.md).

## Global Constraints

- Non iniziare B1 prima che il gate A → B sia soddisfatto. Durante la fase A questo documento è soltanto pianificazione.
- Tutti i percorsi sotto sono relativi al repository **NanoLab**, anche se questo piano è conservato con la specifica NanoFaaS.
- Non implementare qui solver, protocollo, forecast o scaling. Usare le API NanoFaaS prodotte in A; nessuna modifica preventiva a Sonata.
- Calibrazione precedente e indipendente dai confronti; nessuna ricalibrazione implicita e nessun aggiornamento di `D` durante la campagna.
- Tutte le misure scientifiche sono eseguite su Azure. Fake provider e test locali NanoLab verificano l'orchestrazione, non qualificano prestazioni.
- `T_asta << T`, con soglia, quantile e margine espliciti; nessun periodo fissato a un minuto.
- Cloud terminale sufficientemente dimensionato, un edge logico per VM, concorrenza fisica uno per replica, profilo compatibile.
- Risorse e cleanup gestiti da Sonata; salvare evidenze anche su errore. Il generatore distingue carico previsto ed effettivamente emesso.

## Review Focus

1. Acquisizione della terza VM fallita: rilasciare le risorse già acquisite e conservare diagnosi (B2).
2. Stesso nome profilo ma hash o immagine differente: bloccare la campagna prima del carico (B1/B3/B5).
3. Generatore che non sostiene il rate: registrare deficit, non etichettare il run come oracle perfetto (B5).
4. Aste interrotte o quantili stimati su pochi campioni: nessuna qualificazione ingannevole (B4).
5. Run mai eseguito con contatori tutti a zero: nessuna conservazione dichiarata valida (B5/B6).

## Percorsi e interfacce comuni

`N` significa esattamente `packages/nanolab/src/nanolab`, `T` significa `packages/nanolab/tests`; le sigle nei percorsi sotto vanno espanse. File nuovi di package includono `__init__.py` e docstring secondo gli standard del repository.

Comando test mirato dalla radice NanoLab: `uv run --package nanolab --group dev pytest <file-test> --no-cov -q`. GREEN significa exit zero e assertions eseguite; usare le verifiche complete del progetto per il gate finale. Non attribuire al codice esistente supporto multi-edge: l'attuale workflow offload-loadtest gestisce una coppia edge/cloud, e B2 deve realizzare l'estensione.

Classi task: `run(self, inputs: TaskInputs) -> TaskOutcome[T]`, con input e dipendenze esplicite. Resource possiede acquire/release; un task non avvia un secondo orchestratore al suo interno. Nessun parsing di stdout come sostituto di un contratto HTTP strutturato quando disponibile.

## B1 — Configurazione, client e importazione dei contratti NanoFaaS

**File:** creare N `config/one_shot.py`, `one_shot/{models.py,client.py,contracts.py}`, `assets/one-shot/contracts/` con copie versionate degli schemi A1; modificare N `config/scenario.py`, `cli/product.py` e `packages/nanolab/pyproject.toml` per includere gli asset. Test: T `one_shot/test_contracts.py`, `config/test_scenario_one_shot.py`, `one_shot/test_client.py`.

**Interfacce:** `OneShotConfig`, `CalibrationProfile`, `TimingQualification`, `CampaignManifest`; `NanoFaasOneShotClient` espone `load_trace`, `load_profile`, `configure`, `prepare_epoch`, `status`, `epoch_events`, `update_clock_health` usando gli endpoint A4/A11. I metodi ritornano modelli validati, non dizionari senza versione. Conservare provenienza degli schemi e rifiutare versioni non supportate.

- [ ] Test: parser distingue i workflow `one-shot-calibration`, `one-shot-qualification`, `one-shot-experiment`; esperimento senza profilo o qualificazione rifiutato. Hash incoerente e profilo `synthetic=true` rifiutati. Client gestisce `409` senza sovrascrivere una revisione concorrente.
- [ ] Eseguire RED sui tre file di test.
- [ ] Implementare modelli con campi extra vietati per la configurazione locale e unità esplicite; client HTTP con timeout e paginazione eventi. I retry si limitano a letture o operazioni idempotenti con revisione/ID; non ritentare ciecamente trigger o invocazioni. Distinguere errore di trasporto da rifiuto API.
- [ ] Eseguire GREEN e test delle configurazioni legacy. Verificare fixture valide/invalidi identiche a quelle consegnate da A1, senza dipendenza Python dal checkout NanoFaaS.
- [ ] Commit NanoLab: `Add one-shot contracts and NanoFaaS client`.

## B2 — Risorse Azure per più nodi e preflight

**File:** creare N `one_shot/infrastructure.py`, `tasks/one_shot/{resources.py,preflight.py}`; riusare N `tasks/provisioning/{resources.py,providers.py,environment.py}`, `tasks/platform.py`; modificare N `config/one_shot.py`. Test: T `one_shot/test_resources.py`, `one_shot/test_preflight.py`.

**Interfacce:** `build_one_shot_resources(config: OneShotConfig, environment: EnvironmentConfig) -> OneShotResources`; `OneShotResources` contiene Resource per ogni nodo, cloud e load generator con identità univoche. `PreflightTask.run(...) -> TaskOutcome[TopologyEvidence]`; evidence = inventario VM, CPU/RAM/backend, digest immagini, endpoint P2P/HTTP, RTT/banda/skew e configurazioni delle funzioni.

- [ ] Test fake lifecycle: errore sul terzo acquire rilascia i precedenti in ordine corretto; nessuna collisione dei nomi; richiesta di due edge non produce due deployment sulla stessa VM. Preflight rifiuta cloud non pronto, HPA attivo, memoria incoerente e clock fuori soglia.
- [ ] Eseguire RED sui due test, senza credenziali Azure.
- [ ] Implementare lista esplicita di nodi nel blocco `oneShot`, senza forzare tutti gli edge nel ruolo globale `stack`. Ogni nodo usa la Resource VM esistente e un executor legato al proprio host; riusare deployment container e funzioni. Non allargare globalmente `ExecutionRole` né cambiare Sonata solo per rappresentare `edge-0`, `edge-1`. Usare namespace di run per risorse e percorsi.
- [ ] Implementare e verificare preflight con controlli CPU/memoria, c1, immagini, partecipazione P2P, raggiungibilità cloud e sincronizzazione clock. Misurare rete; eventuale emulazione deve avere teardown e verifica dell'effetto reale. Alimentare periodicamente A11 clock-health con campioni misurati, non con zeri sintetici.
- [ ] Eseguire GREEN; creare un dry-run che mostra risorse/task senza provisioning. Commit: `Build isolated Azure resources for one-shot workflows`.

## B3 — Workflow autonomo di calibrazione del servizio

**File:** creare N `plans/one_shot_calibration.py`, `tasks/one_shot/{calibration.py,statistics.py,artifacts.py}`; scenari `packages/nanolab/scenarios-v2/one-shot-calibration.yaml` e copia in N `assets/presets/scenarios-v2/`; test T `one_shot/test_calibration.py`, `plans/test_one_shot_calibration.py`, `one_shot/test_artifacts.py`.

**Interfacce:** `build_one_shot_calibration_plan(config: ScenarioConfig, environment: EnvironmentConfig, *, run_dir: Path) -> Workflow`. Task `WarmupTask`, `MeasureServiceTask`, `ValidateCapacityTask`, `PublishCalibrationTask`; output finale `TaskOutcome[CalibrationProfile]`. `write_immutable_artifact(path: Path, content: bytes) -> str` restituisce SHA-256, fallisce se esiste contenuto diverso, accetta idempotentemente contenuto identico.

- [ ] Test: campioni con timeout non diventano durate nulle; misure di gateway/rete non entrano in `D`; campioni incompleti mantengono censura. Un record runtime scaduto prima della raccolta diventa campione mancante esplicito, non viene eliminato dalla statistica senza traccia. Con fingerprint diverso il profilo non viene riutilizzato. Errore dopo misura conserva campioni e rilascia risorse.
- [ ] Eseguire RED sui tre file di test.
- [ ] Comporre workflow: risorse/preflight → deployment A7 → warmup → richieste a c1 con output/checksum validato → statistiche → prove multireplica/co-locazione → pubblicazione. Misurare separatamente cold start/readiness e RTT. Config dichiarata: numero minimo/massimo campioni, ripetizioni, seed, quantili e tolleranza relativa dell'intervallo di confidenza sulla media; se non raggiunta, artefatto non qualificato invece di proseguire all'infinito.
- [ ] Implementare statistiche riproducibili sui campioni raw e registrare metodo/seed; stimare `D` dall'occupazione fisica A7. Confrontare throughput e curve di capacità col modello `r*U/D`, entro errore massimo esplicito di scenario. Limitare l'intervallo qualificato alle configurazioni verificate; un profilo non valido non viene pubblicato come utilizzabile.
- [ ] Eseguire GREEN e verifica dello schema NanoFaaS. Commit: `Add the independent Azure service calibration workflow`.

## B4 — Qualificazione della durata dell'asta e scelta del periodo

**File:** creare N `plans/one_shot_qualification.py`, `tasks/one_shot/{qualification.py,timing.py}`; scenario `packages/nanolab/scenarios-v2/one-shot-qualification.yaml` e copia bundled; test T `one_shot/test_timing.py`, `plans/test_one_shot_qualification.py`.

**Interfacce:** `build_one_shot_qualification_plan(config, environment, *, run_dir: Path) -> Workflow`; `QualifyTimingTask.run(...) -> TaskOutcome[TimingQualification]`. Qualificazione = matrice nodi/funzioni/topologie/carichi/rete, campioni, censura, quantile, margine, epsilon, candidati `T`, anticipo e limiti di validità. Config richiede esplicitamente candidati di periodo e risoluzione massima del carico: non sceglierli implicitamente nel codice.

- [ ] Test numerico: tempo dimensionante `quantile+margin=12 s`, epsilon `0.05` → periodo minimo `240 s`; `T=60 s` rifiutato. Aste tutte troncate o campioni insufficienti → NOT_QUALIFIED. Sommare i tempi CPU di nodi paralleli non deve cambiare il tempo wall dell'asta.
- [ ] Eseguire RED sui due test.
- [ ] Eseguire aste tramite API A11 con profilo B3, sotto contesa rappresentativa, variando almeno nodi, funzioni, topologia, carico/sbilanciamento e rete. La misura include tutte le finestre di protocollo. Dimensionare preliminarmente i limiti del protocollo per osservare completamenti; un timeout resta censurato, non diventa una prova del rapporto desiderato.
- [ ] Selezionare il più piccolo `T` tra i candidati che soddisfa `quantile(T_asta)+margine <= epsilon*T`, numerosità richiesta e risoluzione della traccia. Il criterio di censura e numero minimo di campioni sono espliciti; non stimare il quantile eliminando silenziosamente gli sforamenti. Misurare anche tempo complessivo fino a ready e scegliere anticipo con margine senza sommare due volte fasi sovrapposte. Nessun candidato valido → report motivato, nessuna campagna automatica.
- [ ] Eseguire GREEN, pubblicare artefatto immutabile separato dal profilo di servizio. Commit: `Qualify distributed auction timing before selecting control periods`.

## B5 — Campagna oracle/EWMA e generatore coerente

**File:** creare N `plans/one_shot_experiment.py`, `tasks/one_shot/{experiment.py,trace.py,conservation.py}`, `assets/k6/one-shot.js`; scenario `packages/nanolab/scenarios-v2/one-shot-experiment.yaml` e copia bundled; test T `one_shot/test_trace.py`, `one_shot/test_conservation.py`, `plans/test_one_shot_experiment.py`.

**Interfacce:** `build_one_shot_experiment_plan(config, environment, *, run_dir: Path) -> Workflow`; task `FreezeManifestTask`, `UploadForecastTask`, `RunOneShotLoadTask`, `CollectEvidenceTask`, `EvaluateOneShotTask`. `TraceArtifact` contiene nodi, funzioni, finestre, rate, q, payload/seed e hash; stesso artefatto produce schedule del generatore e traccia oracle. `RunEvidence` distingue planned/emitted/received/attempts/completed/errors e deficit del generatore.

- [ ] Test: stessa trace → oracle e schedule identici per finestra; resto non rappresentabile su q rifiutato per oracle. Generatore emette 90 di 100 richieste programmate → deficit 10, mai successo perfetto. Nessun carico eseguito → verifica fallita, non `0==0` valido. Duplicati di tentativi non aumentano gli originali completati.
- [ ] Eseguire RED sui tre file di test.
- [ ] Validare B3/B4 e fissare manifest prima di acquisire carico; caricare profili e trace, attendere readiness/conferme e usare riferimento temporale comune. Il generatore assegna ID originali e disabilita retry client automatici; registra gli arrivi effettivi. Non modificare le decisioni dei nodi da NanoLab: lo stato globale è raccolto solo per verifica.
- [ ] Implementare baseline locale+cloud, one-shot oracle e one-shot EWMA con medesima trace/profilo/risorse, seed e ordine dei run registrati, ripetizioni e reset tra run. PG escluso. Verificare conservazione per origine/destinazione/funzione/epoca, one-hop, capacità, RAM, concorrenza uno e latenza; distinguere run non conforme alle assunzioni da algoritmo con risultato peggiore.
- [ ] Eseguire GREEN, incluso fallimento di un task di carico con raccolta best-effort e cleanup. Commit: `Run reproducible one-shot comparisons from shared load traces`.

## B6 — Report, distribuzione dei preset e prova Azure completa

**File:** creare N `one_shot/report.py`, `docs/one-shot-experiments.md`, test T `one_shot/test_report.py`; aggiornare N `cli/product.py`, `assets/presets/environments/azure-one-shot.yaml.example`, `packages/nanolab/environments/azure-one-shot.yaml.example`, T `test_installed_presets.py`, `test_package_layout.py` e dossier finale in `docs/experiments/one-shot/`.

**Interfacce:** `render_report(manifest: CampaignManifest, evidence: RunEvidence, output: Path) -> Path`; report leggibile e JSON numerico derivano dalle stesse evidenze. `nanolab run` riconosce tutti e tre i workflow, con run directory, eventi TUI e artifact collection esistenti.

- [ ] Test: report senza evidenze o con run fallito non mostra un confronto valido; risultati censurati visibili; preset installati fuori dal checkout trovano script e schemi. Nessuna credenziale inclusa nei manifest esportati.
- [ ] Eseguire RED per report e preset; implementare report con throughput, quote reali, cloud, welfare, latenza, code, utilizzo, `T_asta/T`, transizioni, deficit generatori e intervalli di incertezza tra ripetizioni. Riportare limiti e differenze tra modello e osservazione.
- [ ] Eseguire GREEN, poi i quality gate NanoLab esistenti su lint, tipi, test e packaging. Verificare cleanup anche quando raccolta artefatti o upload falliscono.
- [ ] Con configurazione Azure esplicita eseguire nell'ordine `one-shot-calibration`, `one-shot-qualification`, `one-shot-experiment`. Usare `nanolab.sh run <scenario> --environment <ambiente>`; salvare gli esatti percorsi e comandi usati nel dossier. Non avviare risorse semplicemente scrivendo questo piano.
- [ ] Verificare conformità dei run reali e assenza di risorse residue, registrare commit NanoFaaS/NanoLab e digest immagini. Commit: `Document and report the qualified Azure one-shot campaign`.

## Gate finale e dipendenze

```text
Gate A completo
  -> B1 contratti
  -> B2 risorse e preflight
  -> B3 calibrazione indipendente
  -> B4 qualificazione dell'asta e scelta T
  -> B5 confronti
  -> B6 report e verifica Azure
```

- [ ] Profilo di servizio riutilizzabile senza ricalibrazione implicita, con validità dimostrata sul dominio di campagna.
- [ ] Qualificazione temporale indipendente; durata d'asta molto inferiore al periodo e anticipo adeguato alla readiness.
- [ ] Confronti sulla stessa trace e risorse, senza PG e senza controllo centralizzato delle decisioni.
- [ ] Provenienza completa, generatore verificato, errori e censura visibili, conservazione e one-hop dimostrati.
- [ ] Cleanup verificato e documentazione sufficiente a ripetere tutti i workflow.

I task B verificano le sezioni 11–12 e i criteri scientifici della sezione 13 della specifica. Le funzionalità NanoFaaS devono essere già disponibili: eventuali difetti emersi tornano al repository NanoFaaS con un test di regressione, senza copiarne il comportamento nei task NanoLab.
