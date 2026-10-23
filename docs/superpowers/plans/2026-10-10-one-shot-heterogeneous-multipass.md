# One-shot eterogeneo su Multipass — Implementation Plan

> Revisione utente 2026-10-10: le acquisizioni dei Task 8–9 e i parametri single-function/54round sono superseded dal piano `2026-10-10-one-shot-multifunction-multipass.md` e relativa spec. Task1–7 restano infrastruttura completata; nessuna misura delle due prove interrotte qualifica il nuovo scope.


> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (native, preferenza già espressa) oppure superpowers:subagent-driven-development soltanto su nuova scelta dell'utente. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** rendere eseguibile e misurabile una campagna controllata con cinque edge eterogenei, un cloud, topologia sparsa e carico che eserciti offload orizzontale e verticale.

**Architecture:** riutilizzare solver, protocollo, contratti v1 e API NanoFaaS. NanoLab risolve limiti e calibrazioni per nodo, applica il grafo tramite gli attuali modi operatore P2P, genera tracce dalla capacità misurata e analizza ripetizioni appaiate. Pilot e campagna principale restano separati, con artefatti immutabili e nessun coordinatore dell'allocazione.

**Tech Stack:** Java 25/Spring Boot esistenti; Python >=3.12, Pydantic/jsonschema, Sonata, Multipass, Docker/buildx, Rust workload e k6 esistenti. Statistica con libreria standard Python; SDK e backend di logging esistenti; stack Fluent Bit/Loki/Grafana opzionale come da piano collegato.

**Spec:** [disegno della campagna](../specs/2026-10-10-one-shot-heterogeneous-multipass-design.md), estensione locale della [specifica one-shot](../specs/2026-10-04-one-shot-offload-design.md).

**Stato:** aggiornato con logging edge. L’utente ha autorizzato l’implementazione native dopo la progettazione; merge/push non inclusi. La campagna principale resta subordinata ai gate e al protocollo congelato.

**Prerequisito:** [piano logging edge comune](2026-10-10-edge-logging.md) e [relativa specifica](../specs/2026-10-10-edge-logging-design.md). Contratto per tutti i moduli/processi/SDK, Fluent Bit per nodo, Loki/Grafana su host separati dal budget funzioni; gate di overhead prima della calibrazione.

## Global Constraints

- Cinque VM edge e una VM cloud su Multipass; traffico positivo a tutte le sei VM.
- Grafo: `1–2, 2–3, 3–1, 3–4, 4–5, 5–3`; cloud direttamente raggiungibile da ciascun edge; one-hop terminale.
- Dimensioni, quote e pool della tabella della specifica; 17 vCPU/30 GiB iniziali, subordinati al preflight delle risorse realmente disponibili.
- Una funzione Rust CPU-bound identica ovunque; input e checksum identici, c1 fisica, quote CPU per nodo, 256 MiB per replica.
- Modello usa `D[node,function]` misurato; CPU/RAM e intervallo di repliche devono essere validati anche sotto contesa simultanea.
- Sei profili nativi v1, un indice NanoLab; nessuna modifica dello schema pubblico v1 e nessun aggiornamento implicito dei vecchi profili.
- Provider `multipass`; finalità `scientific-experiment` per il nuovo preset, mantenendo `workflow-validation` per quelli precedenti. Non etichettare i risultati Azure.
- Carico cloud diretto al 5% della sua capacità, distinto dall'offload; cloud con almeno 10% di margine rispetto a tutto il carico pianificato.
- Nessuna clonazione dell'asta/solver, nuovo algoritmo, PG, emulazione WAN o modifica di workload prevista; SDK modificati soltanto dal piano logging comune.
- Arrivi open-loop, ID originali, zero retry, errori espliciti separati da esiti irrisolti; cleanup solo delle risorse possedute.
- Pilot sei triplette; campagna principale 12–36 triplette secondo il dimensionamento congelato; nessuna conclusione favorevole richiesta per completare il lavoro.
- Lavorare in worktree isolati; prima di ogni modifica a simboli NanoFaaS eseguire impact GitNexus, segnalare HIGH/CRITICAL, chiarire UNKNOWN; prima dei commit NanoFaaS detect_changes completo. Non sostituire il grafo con ricerche testuali.

## Contesto verificato e mappa dei file

Base osservata: NanoFaaS `d00d9f666b8a200480edeee6c203656c8b4edf3a`, NanoLab `3b2d8b4d64227248ee4d29ed0ec05668c77caff1`, entrambi su main. All'esecuzione aggiornare riferimenti e verificare nuove modifiche prima di derivare i worktree.

Il codice attuale NanoLab: `PublishCalibrationTask` aggrega i nodi e rifiuta `node service profiles differ`; `_platform` mette tutti gli edge nei seed e `CollectTopologyTask` richiede n−1 peer; `FreezeManifestTask` verifica repliche statiche globali; il plan sperimentale genera origini solo edge e confronta il carico con la sola somma delle celle qualificate. Sono i punti da cambiare.

Il runtime NanoFaaS legge il profilo **locale** in `ProfileEpochInputFactory`; P2P dispone già di `PeerMode.EXCLUDED/FORCE_ACTIVE` e file operatore. Consultato prima il grafo dell'isolato `/tmp/nanofaas-one-shot-correlation`, indice una revisione dietro (precede il solo fix del body HTTP); i punti interessati sono stati confrontati con sorgente corrente. **All'esecuzione aggiornare l'indice**: questo sopralluogo non sostituisce gli impact pre-edit.

Percorsi nei task: `N/` = root NanoFaaS; `L/` = root NanoLab. Prefisso Java test `J/` = `N/platform/modules/offload/src/test/java/it/unimib/datai/nanofaas/modules/offload/oneshot/`. Prefisso P2P test `P/` = `N/platform/modules/p2p-discovery/src/test/java/it/unimib/datai/nanofaas/modules/p2pdiscovery/`.

| File/unità | Responsabilità |
| --- | --- |
| `L/packages/nanolab/src/nanolab/config/one_shot.py` | Limiti per nodo, grafo e finestre ripetute; validazione prima di acquisire VM |
| `L/packages/nanolab/src/nanolab/one_shot/profiles.py` (nuovo) | Lettura dell'indice e risoluzione dei profili; compatibilità legacy |
| `L/packages/nanolab/src/nanolab/one_shot/topology.py` (nuovo) | Grafo dichiarato e file operatore P2P |
| `L/packages/nanolab/src/nanolab/one_shot/campaign.py` (nuovo) | Protocollo immutabile, numerosità, scenari per blocco e analisi appaiata |
| `L/packages/nanolab/src/nanolab/tasks/one_shot/{calibration,qualification,experiment,verification,trace}.py` | Misure e controlli esistenti adattati al nodo di destinazione |
| `L/packages/nanolab/src/nanolab/plans/one_shot_*.py` | Composizione dei workflow esistenti, senza nuovo motore o nuova CLI |
| `L/packages/nanolab/src/nanolab/one_shot/report.py` | Distinzione cloud diretto/offload e risultati per blocco |
| Preset e dossier NanoLab | Comandi pubblici riproducibili e prove grezze |

## Review Focus

1. Discovery vede anche non-vicini: nessun messaggio operativo o flusso peer può attraversare un arco non dichiarato; regressioni Task 2/5.
2. Profilo o quota del nodo sbagliato: carico inoltrato deve usare domanda e limiti della destinazione, non la media globale; regressioni Task 3/5.
3. Due vettori con identico totale ma hotspot diverso: una qualificazione non copre automaticamente l'altro; regressione Task 5.
4. Più richieste nel cloud diretto o meno completamenti: non devono apparire come maggiore/minore offload o vantaggio algoritmico; regressioni Task 4/6/7.
5. Campagna interrotta o dati correlati: conservare blocchi incompleti; intervalli per ripetizioni e nessun risultato confermativo da selezione favorevole; regressioni Task 6/8/9.

## Task 1: Limiti per nodo e configurazione della campagna

**Files:** modificare `L/packages/nanolab/src/nanolab/config/one_shot.py`, `L/packages/nanolab/src/nanolab/plans/one_shot_common.py`, `L/packages/nanolab/src/nanolab/one_shot/infrastructure.py`; creare `L/packages/nanolab/tests/config/test_one_shot_heterogeneous.py`; aggiornare `L/packages/nanolab/tests/one_shot/test_preflight.py`.

**Interfaces:** `NodeFunctionLimits(cpu: float, memory_mib: int, max_replicas: int)`; `OneShotNode.function_limits: dict[str, NodeFunctionLimits]`, alias YAML `functionLimits`; `resolve_node_functions(settings: OneShotConfig, node_id: str) -> dict[str, OneShotFunction]`. Chiavi = chiavi configurazione; il plan comune applica gli alias runtime già esistenti. Conservare i parametri input/coefficenti comuni, consentendo override solo dei tre limiti. `OneShotConfig.neighbors: dict[str,list[str]] | None`; `ExperimentWindow(TimingCell).epochs: int = 1`; il nuovo preset richiede warmup di due periodi e un massimo configurabile di 120s anziché l'attuale tetto 60s. Default senza override/grafo mantiene i preset attuali.

- [ ] Scrivere `test_effective_limits_are_node_specific`, `test_replica_pool_cannot_exceed_cpu_budget`, `test_unknown_function_override_is_rejected`, `test_legacy_preset_resolves_identical_limits`, `test_host_budget_accounts_for_external_vms`. Assert: edge-1 0.35/256/1, cloud 1/256/7; impossibile 3 repliche da 1 CPU su VM da 2; nessun provisioning se manca riserva host.
- [ ] RED: `uv run --frozen --all-packages --all-groups pytest packages/nanolab/tests/config/test_one_shot_heterogeneous.py --no-cov -q` da L. Expected: fallimenti dei nuovi contratti.
- [ ] Implementare risoluzione/validazione e registrazione per nodo. Definire `host_budget` includendo VM già attive e riserva per host/generatore/backend Loki-Grafana e 0.10 CPU/64 MiB per agente Fluent Bit, più 256 MiB di storage per nodo; non liberare quelle estranee. Acquisizione delle sei VM resta nel resource manager Sonata esistente.
- [ ] GREEN: comando precedente più `packages/nanolab/tests/config packages/nanolab/tests/one_shot/test_preflight.py`; expected tutti passati, legacy invariato. Commit locale: `Support node-specific one-shot resource limits`.

## Task 2: Grafo edge esplicito con P2P esistente

**Files:** creare `L/packages/nanolab/src/nanolab/one_shot/topology.py`, `L/packages/nanolab/tests/one_shot/test_sparse_topology.py`; modificare `L/packages/nanolab/src/nanolab/plans/one_shot_common.py`, `L/packages/nanolab/src/nanolab/tasks/one_shot/preflight.py`, `L/packages/nanolab/src/nanolab/tasks/one_shot/experiment.py`.

**Interfaces:** `validate_neighbor_graph(settings: OneShotConfig) -> dict[str,tuple[str,...]]`; `operator_peer_modes(node_id: str, graph: dict[str,tuple[str,...]]) -> dict[str,str]`; `verify_neighbor_sets(observed: dict[str,set[str]], expected: dict[str,tuple[str,...]]) -> None`. File YAML `config.peers[{id,mode}]` scritto prima del container, bind-mounted con `--nanofaas.p2p.state-file=<path>`; seed e modalità non si confondono.

- [ ] Scrivere regressioni: grafo esatto con gradi 2/3/2/2/3; non-vicini EXCLUDED; self-loop, cloud come peer edge, ID ignoto, asimmetria e disconnessione rifiutati; peer sbagliato con stesso conteggio rifiutato; gossip che riscopre un non-vicino non lo rende operativo; deriva durante il run invalida la conformità tecnica.
- [ ] RED: `uv run --frozen --all-packages --all-groups pytest packages/nanolab/tests/one_shot/test_sparse_topology.py --no-cov -q`. Expected fallimenti dei nuovi controlli.
- [ ] Implementare file operatore, mount e confronto degli insiemi di identità nei preflight/collector; registrare RTT misurati sugli archi. Default legacy rimane completo. Nessuna nuova allowlist, API P2P o emulazione di rete.
- [ ] GREEN: comando precedente più `packages/nanolab/tests/one_shot/test_preflight.py`; expected pass. Commit: `Freeze and verify sparse one-shot peer topology`.

## Task 3: Calibrazione per nodo e indice immutabile

**Prerequisito obbligatorio:** ripetere il gate logging sullo stack completo congelato della campagna; il pilot tecnologico a una replica/nodo e P2P disabilitato non lo qualifica. stack/config/formato/livello/budget nei fingerprint. Tre coppie overhead entro 5% per goodput/p95; non riusare profili dello stack precedente.

**Files:** creare `L/packages/nanolab/src/nanolab/one_shot/profiles.py`, `L/packages/nanolab/src/nanolab/assets/one-shot/profile-set.schema.json`, `L/packages/nanolab/tests/one_shot/test_node_profiles.py`; modificare `models.py`, `tasks/one_shot/calibration.py`, `plans/one_shot_calibration.py` sotto `L/packages/nanolab/src/nanolab/`; aggiornare i test di calibrazione esistenti.

**Interfaces:** `CalibrationProfiles(by_node: dict[str,CalibrationProfile], sha256: str, environment_fingerprint: str, capacities: dict[str,float] | None)` in `profiles.py`; `read_profiles(settings: OneShotConfig) -> CalibrationProfiles`; `node_environment_identity(settings, topology, built, node_id: str) -> tuple[str,dict[str,str]]`. Indice schemaVersion 1/artifactType `node-profile-set`, provider/purpose/global fingerprint, `nodes: dict[str,ArtifactReference]`, `capacity: ArtifactReference`; riferimenti relativi al file indice. Ogni profilo nativo contiene fingerprint locale e ambiente VM del nodo. Il reader legacy produce la stessa mappa soltanto dopo aver verificato omogeneità dei limiti; capacità non misurate restano None, non vengono inventate da 1/D.

- [ ] RED: test profili con D=0.30/0.10 su due nodi accettati **separatamente**; hash alterato, nodo mancante, swap dei profili, limite/fingerprint non corrispondente, path fuori dal dossier e profilo singolo con override eterogeneo rifiutati. Ripubblicazione identica ammessa, diversa vietata. `pytest packages/nanolab/tests/one_shot/test_node_profiles.py --no-cov -q` nel medesimo wrapper uv.
- [ ] Pubblicare sei profili v1, raw samples/statistiche per nodo, indice atomico con gli helper esistenti. D misurato da occupancy SDK; niente media globale e niente modifica della tolleranza per mascherare la differenza.
- [ ] Estendere capacity sweep a ogni r da 1 al massimo locale e verificare anche tutti i nodi simultaneamente. `C[node]` = maggiore rate testato sostenuto in tre prove da 60s, dopo 30s di warmup per prova, con almeno 99% completamenti provati, zero deficit generatore e nessun esito irrisolto. Sweep iniziale `[0.5,0.7,0.9,1.0,1.1,1.25] * r/D`; mantenere anche le prove non sostenute. È capacità empirica nel dominio misurato, non un massimo garantito. Salvare rate/prove/scelta in `capacity.jsonl` e pubblicare `capacity-summary.json` immutabile referenziato dall'indice; se CPU contention viola il profilo, rifiutare l'intervallo.
- [ ] GREEN: `uv run --frozen --all-packages --all-groups pytest packages/nanolab/tests/one_shot/test_node_profiles.py packages/nanolab/tests/one_shot/test_calibration.py packages/nanolab/tests/one_shot/test_calibration_pipeline.py --no-cov -q`; expected pass inclusi fixture legacy. Commit: `Calibrate independent one-shot node profiles`.

## Task 4: Tracce da capacità e traffico su tutte le VM

**Files:** modificare `L/packages/nanolab/src/nanolab/tasks/one_shot/trace.py`, `L/packages/nanolab/src/nanolab/plans/one_shot_experiment.py`, `L/packages/nanolab/src/nanolab/tasks/one_shot/experiment.py`, `qualification_load.py`, `one_shot/report.py`; creare `L/packages/nanolab/tests/one_shot/test_capacity_trace.py`.

**Interfaces:** `capacity_trace(capacities: dict[str,float], settings: OneShotConfig, period: float, seed: int) -> TraceArtifact`. `TraceArtifact` conserva origine/ruolo per tutti i sei nodi e ID della fase per ogni epoca; origini cloud non producono forecast né aste. `ExperimentWindow.epochs` espanso in epoche identiche, senza replicare la logica k6. Capacità lette da artefatto misurato hash-bound, mai da valori nascosti nel codice.

- [ ] RED: test 54 epoche, tasso positivo su tutti i nodi, hotspot 1.8C, fondo 0.35C, sovraccarico 1.10C, cloud diretto 0.05C; q arrotondata secondo specifica, errore se perde la positività; stessa trace→stesso oracle edge/schedule. Assert cloud diretto non compare negli upload forecast.
- [ ] Implementare generatori usando schedule/k6 esistenti; separare `cloud-direct` da edge→cloud nei contatori e nel controllo di capacità cloud. Usare il più prudente fra capacità cloud empirica validata e maxReplicas/D per il margine del 10%, includendo tutti gli arrivi; rifiutare tracce che lo eccedono. Goodput/utilità del confronto edge escludono il fondo cloud; totale piattaforma lo riporta separatamente, senza doppio conteggio. Warmup positivo per nodo in funzione della propria capacità, contabilizzato a parte; durata almeno due periodi per il nuovo preset, preservando default legacy.
- [ ] GREEN: `uv run --frozen --all-packages --all-groups pytest packages/nanolab/tests/one_shot/test_capacity_trace.py packages/nanolab/tests/one_shot/test_trace.py packages/nanolab/tests/one_shot/test_conservation.py --no-cov -q`; expected pass. Commit: `Generate measured-capacity traces for all one-shot nodes`.

## Task 5: Qualificazione e accounting eterogenei fino al runtime

**Files:** modificare `L/packages/nanolab/src/nanolab/tasks/one_shot/{qualification,qualification_load,experiment,verification}.py`, `L/packages/nanolab/src/nanolab/plans/one_shot_qualification.py`; creare `L/packages/nanolab/tests/one_shot/test_heterogeneous_evidence.py`; estendere `J/api/ProfileEpochInputFactoryTest.java`, `J/coordination/OneShotPeerIntegrationTest.java`, `P/DefaultPeerTransportTest.java`, `P/PeerMessagingTest.java` solo con regressioni dei contratti già presenti.

**Interfaces:** `verify_profile_scope` e `configuration_for_node` consumano `CalibrationProfiles` e scelgono `by_node[node_id]`; `verify_observations` risolve D/tolleranza per `(destination,function)`; `verify_runtime_inventory` risolve i limiti effettivi del nodo. `verify_qualified_load(trace: TraceArtifact, qualification: TimingQualification) -> None` richiede la copertura delle effettive celle/vettori della campagna, incluso il carico cloud; non basta un totale o un rettangolo di massimi indipendenti.

- [ ] RED NanoLab: D edge lento non confrontato con D cloud; profilo/quota scambiati rifiutati; due vettori con stesso totale ma hotspot diverso non si qualificano reciprocamente; origini cloud senza aste, cloud senza margine, arco peer proibito e conferma non reciproca rifiutati. `pytest packages/nanolab/tests/one_shot/test_heterogeneous_evidence.py --no-cov -q` con wrapper uv.
- [ ] Aggiornare manifest/freeze, baseline statico per nodo, drift e CPU/RAM/repliche; riusare collector e cleanup. Conservare prove SDK esatte e classificazione conservativa degli errori remoti. Il cloud diretto può omettere header di nodo come l'API standard: accettarlo soltanto con prova SDK esatta e destinazione cloud congelata, rifiutando header presenti ma discordanti; metadata nativa edge invariata.
- [ ] Verificare sul runtime invariato profili locali con D differenti e negoziazione compatibile, endpoint/EXCLUDED che bloccano richieste operative sia in uscita sia in ingresso, senza richiedere lo stesso profileId sui nodi. Sono test di contratto: possono passare subito; non inventare un RED. Se falliscono, documentare il difetto NanoFaaS, fare impact/TDD e fix minimo nel suo worktree prima di continuare; mai aggirarlo in NanoLab.
- [ ] GREEN L: `uv run --frozen --all-packages --all-groups pytest packages/nanolab/tests/one_shot packages/nanolab/tests/plans/test_one_shot_qualification.py packages/nanolab/tests/plans/test_one_shot_experiment.py --no-cov -q`. GREEN N: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-arm64 ./gradlew :control-plane-modules:offload:test :control-plane-modules:p2p-discovery:test :control-plane:test`; scegliere un Java 25 effettivamente disponibile se il percorso cambia. Expected pass; analisi GitNexus completa prima di eventuale commit N. Commit L: `Verify node-specific one-shot timing and physical evidence`.

## Task 6: Protocollo congelato e analisi per ripetizioni

**Files:** creare `L/packages/nanolab/src/nanolab/one_shot/campaign.py`, `L/packages/nanolab/tests/one_shot/test_campaign_statistics.py`; modificare `L/packages/nanolab/src/nanolab/config/one_shot.py`, `one_shot/models.py`, `plans/one_shot_experiment.py`, `tasks/one_shot/experiment.py`.

**Interfaces:** `plan_repetitions(paired_cloud_differences: Sequence[float]) -> int`; `freeze_campaign_protocol(*, profiles, qualification, pilot_rows, settings) -> dict`; `write_campaign_scenarios(protocol: dict, output: Path) -> list[Path]`; `paired_summary(blocks: Sequence[dict], *, seed: int, draws: int = 10000) -> dict`. Nuovi campi opzionali `experiment.campaignProtocol: ArtifactReference` e `experiment.campaignBlock: int`: presenti insieme, congelano seed/trace/order/ruolo pilot-main del singolo blocco. Ogni scenario usa il workflow esistente `one-shot-experiment`, una ripetizione/tre modi; nessuna nuova CLI o orchestrazione infrastrutturale duplicata.

- [ ] RED: `test_requests_are_not_independent_repetitions`, `test_pairing_requires_all_modes_and_matching_scope`, `test_cloud_direct_is_excluded_from_primary_metric`, `test_pilot_is_excluded_from_main_analysis`, `test_sample_size_is_frozen_before_main`, `test_interrupted_campaign_is_incomplete`. Casi: sei permutazioni bilanciate, hash non coincidenti rifiutati, errore se il calcolo richiede >36, blocchi mancanti non trattati come zero.
- [ ] RED: `uv run --frozen --all-packages --all-groups pytest packages/nanolab/tests/one_shot/test_campaign_statistics.py --no-cov -q`; expected fallimenti dei nuovi contratti. Aggiungere queste asserzioni numeriche:

```python
assert plan_repetitions([0.05] * 6) == 12
# Dodici blocchi, in ciascuno EWMA 10pp meno cloud del baseline:
assert paired_summary(twelve_constant_pairs, seed=7)["primary"]["estimate"] == pytest.approx(-0.10)
assert paired_summary(twelve_constant_pairs, seed=7)["primary"]["blockCount"] == 12
```

- [ ] Implementare formula, bilanciamento, manifest protocollo, ricampionamento dei blocchi appaiati e intervalli 95% con stdlib. Non ricampionare individualmente le richieste. Conservare numeri per blocco, tutte le esclusioni e la condizione di completamento non inferiore di oltre 2pp. Conclusioni confermative non disponibili con campagna incompleta o esclusioni associate al modo/algoritmo; riportare ordine temporale/diagnostica di drift e dipendenza seriale senza dichiarare indipendenza sulla sola assenza di un test significativo.
- [ ] GREEN: `uv run --frozen --all-packages --all-groups pytest packages/nanolab/tests/one_shot/test_campaign_statistics.py packages/nanolab/tests/one_shot/test_report.py --no-cov -q`; expected pass. Commit: `Freeze paired one-shot campaign designs and summaries`.

## Task 7: Preset, report e documentazione riproducibili

**Files:** creare sotto `L/packages/nanolab/src/nanolab/assets/presets/scenarios/` i preset `one-shot-heterogeneous-{calibration,qualification,pilot}.yaml`; aggiornare `L/packages/nanolab/src/nanolab/one_shot/report.py`, `L/docs/one-shot-workflows.md`; creare `L/docs/testing/one-shot-heterogeneous-multipass/README.md`, `L/packages/nanolab/tests/one_shot/test_heterogeneous_report.py`; aggiornare `test_installed_presets.py`, `test_package_layout.py` esistenti.

**Interfaces:** report numerico e HTML consumano gli stessi `RunEvidence`/`paired_summary`; per fase e nodo mostrano carico/C, domanda, quota, repliche e routing. Grafico del grafo, matrice origine/destinazione, cloud diretto separato e intervalli fra blocchi. Rendere `coverageSatisfied` indipendente da `conforming` e dall'effetto favorevole: baseline peer=0 è valido.

- [ ] RED: preset installati fuori checkout trovano schema/script; report cloud diretto distinto, baseline senza peer valido, main con zero offload non nascosto, report parziale/invalido senza significatività né CI inventati.
- [ ] Implementare preset della tabella, qualificazione della specifica e protocollo pilot; i riferimenti hash vuoti devono continuare a fallire prima delle VM. Documentare comandi e stima durata, limite locale/CPU emulata/rete osservata, differenze di coda, contrasto primario e inferenza secondaria.
- [ ] GREEN: `uv run --frozen --all-packages --all-groups pytest packages/nanolab/tests/one_shot/test_heterogeneous_report.py packages/nanolab/tests/test_installed_presets.py packages/nanolab/tests/test_package_layout.py --no-cov -q`. Commit: `Document reproducible heterogeneous one-shot campaigns`.

## Task 8: Gate completo e pilot reale, prima della campagna

**Files:** dossier `L/docs/testing/one-shot-heterogeneous-multipass/`; solo configurazioni/campioni/report/receipt/comandi nel dossier, archivi immagini e HTML voluminosi negli artefatti operatore. Test pertinenti dei Task 1–7 già presenti.

**Consumes:** gate logging/overhead, tre workflow pubblici, profili per nodo con osservabilità congelata, capacità, qualificazione e protocollo pilot. **Produces:** sei triplette pilot indipendenti, evidenze di copertura, misura variabilità e stima del budget della campagna; mai risultati pilot presentati come main.

- [ ] Prima delle VM eseguire suite completa e hook L: `NANOFAAS_ROOT=<pin-CI-compatibile> uv run --frozen --all-packages --all-groups pytest -c packages/nanolab/pyproject.toml packages/nanolab/tests` e `uv run --frozen --all-packages --all-groups pre-commit run --all-files`. Expected pass/coverage >=90%. Il pin delle fixture CI è distinto dal checkout runtime reale e dichiarato.
- [ ] Eseguire gate N del Task 5, più `:execution-runtime:test`; nessuna produzione N cambiata senza TDD/impact/detect_changes. Verificare inline contratti e dati prima delle prove; la sola review indipendente dell'intero lavoro prevista dal metodo native avviene dopo il Task 9, includendo il dossier. Non riscrivere l'algoritmo per ottenere un risultato desiderato.
- [ ] Inventariare host e VM estranee; esportare distribuzione dalla revisione runtime pulita. Preparare configurazioni operatore senza hash fittizi. `NANOFAAS_ROOT=<checkout-runtime>` in ciascun comando. Sequenza da L: `./nanolab.sh run <calibration.yaml> --environment <multipass.yaml> --run-dir <runs/calibration>`; poi qualification con hash dell'indice misurato; poi i sei scenari pilot con i due riferimenti congelati. Salvare ogni comando esatto, non soltanto i placeholder qui illustrati.
- [ ] Verificare preflight/contesa, tutti i profili e capacità, cinquanta sample per cella, zero censura, grafo esatto; pilot con arrivi su sei VM, almeno un completamento peer e uno edge→cloud nei modi nativi, saturazione fisica misurata, nessun collo di bottiglia del generatore/cloud. Se la copertura manca, conservare dati e revisionare esplicitamente il disegno, senza promuoverlo a esperimento significativo.
- [ ] Esportare log centralizzati per run/nodo/mode e metriche del collector con ricevute hash prima del cleanup; completezza/parzialità dei log distinta dalla validità del run, senza inferire completamenti dai log. Preservare volumi/backend estranei.
- [ ] Iniettare un failLoad in run separato e verificare terminate/reap, chiusura collector, raccolta best-effort e tutte le release; inventario esterno preservato, nessun k6/VM posseduto residuo. Expected errore originale conservato, run invalido, cleanup confermato.
- [ ] Dal pilot congelare N, sei-permutazioni, seed, tutti gli hash, contrasto/analisi e piano di rimpiazzo. Pubblicare stima wall time: `3 * N * (54*T + warmup + provisioning/release)` più calibrazione/qualificazione/pilot; con T=20s sono già 18 minuti di carico per modo. T=20s è soltanto un esempio di costo, non un valore da riusare. Commit dossier: `Record heterogeneous one-shot pilot qualification`.

## Task 9: Campagna principale e consegna delle evidenze

**Files:** `L/docs/testing/one-shot-heterogeneous-multipass/main/`, report aggregati e dossier di provenienza. Nessun codice produttivo modificato mentre si misura.

**Consumes:** protocollo main immutabile prodotto dal Task 8; N 12–36 blocchi appaiati. **Produces:** risultato scientifico locale, favorevole, nullo o inconcludente, con intervalli, dati e limitazioni espliciti.

- [ ] Verificare che l'esecuzione del piano e delle sue prove reali sia stata autorizzata: l’utente ha autorizzato l’implementazione native dopo il progetto. La campagna principale richiede gate precedenti e protocollo congelato; non partire direttamente dalla nuova autorizzazione saltandoli. All'esecuzione registrare autorizzazione e protocollo congelato nel ledger; non assumere che il pilot autorizzi a cambiare N a posteriori.
- [ ] Eseguire serialmente gli scenari generati attraverso `nanolab.sh run`, ciascuno in directory nuova, con ordine bilanciato e topologia ricreata per ogni modo. Conservare status e receipt anche se un blocco fallisce; seed sostitutivi prestabiliti, massimo floor(0.20*N) rimpiazzi solo per invalidità tecnica esterna. Se il problema è associato a uno specifico algoritmo o la causa è ambigua, dichiarare l'inferenza inconcludente, non eliminare selettivamente le prove. Non fare retry delle richieste.
- [ ] Expected: ogni richiesta contabilizzata con completamento SDK o errore noto; nessun doppio tentativo/hop vietato; limiti per nodo e matrice qualificata rispettati; drift entro tolleranze immutabili. Saturazione/offload misurati e riportati anche quando un algoritmo usa meno peer del previsto.
- [ ] Calcolare `paired_summary` solo dopo aver congelato il registro completo. Riportare primary effect e CI, completion guard, risultati ORACLE secondari, latenze/timeouts, matrici dei flussi, overhead e tutti i blocchi/scarti. Nessuna conclusione confermativa se campagna incompleta, contesa invalida o esclusioni legate al modo.
- [ ] Verificare byte/hash anche dopo eventuale compressione, inventario finale e assenza delle sole risorse possedute. Expected dossier ricostruibile e nessun residuo; benchmark e main distinti. Commit: `Publish paired heterogeneous one-shot campaign evidence`. Merge/push futuri soltanto quando richiesti.

## Dipendenze e completamento

`logging comune + gate overhead → 1 limiti → 2 grafo → 3 profili → 4 traccia → 5 qualificazione/accounting → 6 protocollo/statistica → 7 report/preset → 8 pilot/gate → 9 campagna`.

Task 1–7 consegnano la capacità tecnologica; Task 8 dimostra copertura e dimensiona il disegno; Task 9 è l'esperimento ripetuto. Non chiamare «campagna conclusa» la sola presenza di VM/configurazioni o il passaggio dei test. Non chiamare «significativo» il semplice aumento del numero di VM, richieste o offload.

Alla fine del Task 9 eseguire la sola review indipendente dell'intero branch e delle evidenze prevista dal metodo native. Se una correzione invalida acquisizione, scope o artefatti, dichiarare quali prove sono superseded e ripetere i relativi prerequisiti: non presentare vecchie misure come verifica di codice modificato. Nessuna ri-review aggiuntiva prevista.

Self-review del piano: requisiti utente mappati ai Task 1–5/8; significatività ai Task 6/9; cloud diretto e differenze di servizio espliciti; cinque rischi coperti da regressioni; legacy preservato; piano logging collegato, budget/fingerprint/overhead e raccolta pre-cleanup espliciti; autorizzazione native successiva registrata.
