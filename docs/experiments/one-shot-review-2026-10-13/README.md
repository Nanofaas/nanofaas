# Revisione del piano e audit iniziale dell'esperimento one-shot

**Rapporto storico di pianificazione.** Per lo stato successivo all’implementazione, leggere [correzioni, verifiche e freeze](implementation.md) e [copertura aggiornata](coverage.md).

13 ottobre 2026. Richiesta: rivedere il piano observation-readiness, estendere
la verifica a tutto il codice sperimentale e accertare l'uso di Sonata.
Questo documento registra problemi verificati e lavoro ancora necessario;
non certifica un audit esaustivo già concluso. Nessuna campagna, modifica del
runtime, commit o push durante questa revisione.

## Sorgenti e copertura

- NF: `/home/michele/Documenti/nanofaas/.worktrees/edge-logging`, HEAD `9c2aa94c9503178546dc365f395700842994f2fc`.
- NL: `/tmp/nanolab-one-shot-host-placement`, HEAD `bccd7c9d014f49e46bc4342bc0dca938a112494e`, con le modifiche locali preesistenti incluse nella lettura e nei test.
- `evidence/scope.json`: inventario iniziale di 43 file / 16.198 righe, con SHA-256. È una base da estendere alle dipendenze, non un elenco di file tutti già letti integralmente.
- Lettura mirata del percorso corrente: script observer, builder osservazionale e qualificato, composizione risorse/provisioning, supervisione, configurazione/preparazione epoche, generatore JS, raccolta fisica, clock, sampler VM/container, logging, conteggi e report; contratti Java settings e attuatore.
- Da completare nel Task 0: revisione integrale per file, percorsi secondari di taratura/calibrazione/capacità, tutti gli adapter transitivi, SDK Rust/workload e regressioni di confine Java. I test sul riferimento Python sono stati eseguiti, ma non sostituiscono tale lettura.

GitNexus è stato interrogato prima della lettura dei flussi. Il checkout NL
effettivo non è registrato; il metadata dell'indice NF indica ancora il commit
`e78a742…` del 10 ottobre. Le conclusioni sotto derivano da sorgenti correnti
e riproduzioni, non da un all-clear del grafo. Risolvere freshness/UNKNOWN e
impact prima degli interventi sui simboli; nessun simbolo è modificato qui.

## È un workflow Sonata?

**Sì, il percorso della campagna lo è.** `scripts/one-shot-observe.py:905`
costruisce `Workflow("first-functional-oracle-observation")` e a fine composizione
chiama realmente `workflow.run()`. Il supervisore usa il sink di eventi Sonata;
non è l'agente a far avanzare il workflow.

| Fase | Implementazione effettiva | Ownership/dipendenze |
|---|---|---|
| Prepare/freeze software offline | `prepare()` nello script | Nessuna VM, prima del comando run |
| VM, CPU placement, bootstrap/cloud-init | `build_one_shot_resources`, `_bootstrap` | `Resource`, dipendenza da VM/operator, compensation |
| Immagini, backend logging, collector, CP, probe, funzioni | `add_one_shot_platforms` e risorse riusate | Grafo `Resource.requires`; rilascio ordinato Sonata |
| Preflight, warm instances, precheck, freeze schedule, configurazione ORACLE | `Steps` di task effettivi | `topology.requires` e clock |
| Warmup e nove intervalli | `RunOneShotLoadTask` | Generator dipende da telemetry; collector dipende da generator e topology; clock dichiarato |
| Report | `ObservationReport` | Prima della release delle risorse necessarie alla correlazione |
| Cleanup | Engine Sonata + release locali idempotenti | Solo risorse possedute; compensazione su eccezioni |
| Inventari, report parziale finale, archivio/esito | Wrapper di `Workflow.run` / `CampaignSupervision` | Archivio dopo compensation e join; non sono task nel DAG |

Il builder qualificato pubblico `plans/one_shot_experiment.py` è un altro
workflow Sonata; il comando CLI `one-shot-experiment` non equivale al percorso
osservazionale supervisionato. Il piano prevede di portare builder e task
dell'osservazione nel package, riusando le risorse, e lasciare lo script sottile.
Non serve un nuovo engine né trasformare la copia finale in un task che scrive
l'archivio mentre Sonata emette ancora eventi.

## Finding da risolvere

P1 indica un possibile blocco della prossima esecuzione, perdita di dati o
mancata gestione di un guasto; P2 un errore di risultato/configurazione o di
copertura. Queste sono priorità software, non soglie dell'esperimento.

| ID | Priorità | Problema, trigger ed evidenza | Task del piano |
|---|---|---|---|
| A01 | P1 | `ConfigureOracle.run`, observer:760, passa `lead=8` mentre prep è ora 10 e il manifest dichiara 14. Riprodotto eseguendo il task: args 8/10. `OneShotSettings` richiede prep < lead; `OneShotOperations.configure` richiede asta+prep ≤ lead. La prossima configurazione sarebbe rifiutata. | 3, 6 |
| A02 | P1 | `qualification.prepare_parallel_epoch` considera completo solo PREPARED con tutte le repliche; `RunOneShotLoadTask` arresta anche con `enforce_timing_budget=False`; `verify_epoch_flows` pretende reciprocità anche dell'inbound inutilizzato. Problemi noti, riconfermati nel sorgente. Il `check(p)` post-handshake dell'attuatore può inoltre produrre FAILED dopo pubblicazione del piano locale sicuro. | 1, 2 |
| A03 | P1 | `CampaignSupervision._monitor`: se `record(heartbeat)` fallisce, il catch chiama ancora `record(operational.failure)` prima di `os.kill`. Se fallisce anche quello, il thread muore senza svegliare l'azione. Riproduzione con I/O simulato: l'azione continua; l'errore viene restituito soltanto quando l'azione finisce da sola. | 4 |
| A04 | P1 | `ObservationFreeze` omette `parameters.vmTelemetry`, ma `report.render_report:479` genera i grafici solo con quel campo. Sul manifest archiviato 04: sei raw VM presenti, zero chiamate a `resource_figures`. Raccolta e presenza dei grafici sono due proprietà diverse. | 5, 6 |
| A05 | P1 | `check_sampler` ignora l'intera riga se contiene qualsiasi `errors`: un errore cgroup di una funzione maschera anche l'assenza del CP da un inventario completo. Riprodotto. Inoltre il backend Loki è verificato all'acquire ma non registrato tra i watcher; la sorveglianza CP/Fluent Bit tramite sampler parte solo nel precheck/carico. Sono lacune di copertura, non guasti osservati del run 04. | 4, 6 |
| A06 | P2 | Il JS reale, su risposta JSON `null`, accede a `body.executionId` e lancia TypeError prima di scrivere `result`. Harness Node: un evento emitted, nessun result. Una risposta non conforme deve restare un dato; non deve rompere il logger del generatore. | 5 |
| A07 | P1 | `RunOneShotLoadTask:1151–1154` impone fine+35 s, k6 ha allowance 60 s, il sampler usa una terza formula hardcoded. Un generatore ancora entro la durata configurata può essere fermato dal runner; alzare solo la costante principale non risolve. | 3, 6 |
| A08 | P1 | Il freeze copia package/ricetta/script, ma in esecuzione `one_shot_common` legge definizioni e asset dal checkout NF corrente e `logging.py` legge `deploy/observability/*`. Gli hash verificati all'avvio non coprono tutti questi bytes; il controllo `git diff HEAD` al prepare non comprende file untracked e non impedisce modifiche successive. Rischio riproducibilità confermato dal flusso; test di mutazione ancora da aggiungere. | 5 |
| A09 | P1 | `logging.collector_resource.export` esegue una query Loki limitata e marca partial; `backend_resource.release` poi fa `compose down -v`. Il buffer Fluent Bit può aver già eliminato i chunk consegnati: archiviarlo non preserva i log esclusi dalla query. Con export troncato e backend posseduto si rischia di cancellare l'unica copia completa. Test di teardown simulato ancora da aggiungere. | 5 |
| A10 | P2 | `test_first_observation` sostituisce `Workflow.run` con compile-only oppure esegue un workflow ridotto con `fail_load=True`. I test del supervisore usano Sonata reale, ma non dimostrano tutti i nove intervalli né l'esecuzione del vero `ConfigureOracle`. Questo spiega la mancata regressione di A01. | 6 |
| A11 | P2 | La prima stesura del piano aumentava lateness, maxPeers, parallelismo, code e retention senza dimostrare che arrestassero questa osservazione. Lateness è già solo diagnostica; più retention/parallelismo può peggiorare memoria/contesa con CPU/RAM fisse. Proposta corretta nella spec: valori invariati, export incrementale, aumenti dei timeout realmente pertinenti. | 0, 3 |

I finding A01/A03/A04/A05/A06 hanno riproduzioni salvate. A02 è riconferma
dei problemi già pianificati. A07/A08/A09 sono conclusioni di lettura del
percorso; le regressioni richieste sono esplicite nel piano. Nessun finding
nuovo è attribuito retroattivamente come causa del fallimento storico 04.

## Verifiche eseguite

- Verifica finale integrata della suite `packages/nanolab/tests/one_shot`: **792 passati, zero saltati, 30,49 s**, con sorgenti/input preservati, riferimento Python e socket loopback disponibili (`evidence/complete-tests.xml`). Comando `python -m pytest --no-cov -q packages/nanolab/tests/one_shot`; ambiente di `evidence/verify.py` più `ONE_SHOT_REFERENCE_PYTHON=/tmp/oneshot-first-experiment-20261012/observation-preparation-02/python-env/bin/python`.
- Suite `packages/nanolab/tests/one_shot`: **788 passati, 3 saltati, 1 bloccato dalla sandbox** (`test_owned_probe` richiede socket loopback; PermissionError, non errore del prodotto).
- Riesecuzione isolata con socket locale consentita: `test_owned_probe.py`, **3 passati**.
- Riferimento Python con ambiente originale esplicitamente fornito: `test_reference_load.py`, **7 passati**, compresi i tre precedentemente saltati.
- `reproduce.py`: task configurazione, I/O supervisore, inventario sampler e report reale con manifest archiviato. Le asserzioni verificano la presenza dei bug attuali, non un fix.
- `k6-null.mjs`: JS produttivo caricato come modulo, I/O k6 simulato, nessuna richiesta di rete; riproduce perdita dell'evento result.

I file JUnit e gli script sono in `evidence/`. Nessuna VM/Docker avviata,
nessuna campagna lanciata e nessuna variazione al fingerprint storico.
I test attuali passano nei loro scenari, ma i casi riprodotti fuori da quella
copertura falliscono: l'implementazione richiede le nuove regressioni del piano.

## Consegna

[Piano rivisto](../../superpowers/plans/2026-10-13-one-shot-observation-readiness.md)
e [spec aggiornata](../../superpowers/specs/2026-10-13-one-shot-observation-readiness-design.md).
Sette task (0–6): audit completo; attuatore; policy osservazionale; limiti coerenti;
composizione/supervisione Sonata; integrità dati/fingerprint; workflow completo
simulato. CPU/RAM, pool, carico e otto round rimangono quelli approvati.
