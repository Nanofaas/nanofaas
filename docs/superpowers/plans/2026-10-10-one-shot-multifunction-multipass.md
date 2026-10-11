# One-shot multi-funzione con round di sei minuti — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans in native/inline mode. Steps use checkbox (`- [ ]`) syntax. One fresh whole-branch/data review at the end; no per-task delegation/review.

**Goal:** Eseguire su cinque edge e cloud una campagna con cinque funzioni Rust, memoria strict e carico sincronizzato al round, nove intervalli da360 s e54 minuti misurati complessivi per modo.

**Architecture:** Riutilizzare le VM/ownership Sonata, SDK fisici, grafo esatto, profili locali e paired registry già implementati. Generalizzare i punti esplicitamente single-function, senza reinterpretare artefatti scalari; runtime e logging congelati dopo nuova esportazione. Tre modi eseguiti separatamente con gli stessi30 flussi di input per intervallo.

**Tech Stack:** Java25/Spring Boot, Rust SDK esistente, Python>=3.12/NanoLab, Multipass, Docker cgroupv2, FluentBit/Loki/Grafana.

**Spec:** `docs/superpowers/specs/2026-10-10-one-shot-multifunction-multipass-design.md`.

## Global Constraints

- Utente: due triangoli1–2–3 e3–4–5, cloud diretto da ogni edge; cinque funzioni; tempi/carichi diversi; memoria strict; sincronizzare carico e one-shot ogni360 s;9 intervalli,54 minuti per esecuzione con sei nodi simultanei.
- Futuro60 s separato e riqualificato, nessun cambio automatico durante acquisizione. Non ripresentare54 intervalli o6 round/fase come scelta utente corrente.
- Worktrees esistenti N=`/home/michele/Documenti/nanofaas/.worktrees/edge-logging`, L=`/tmp/nanolab-edge-logging`; native inline. Nessun nuovo worktree necessario.
- `nanofaas-stack` purged con autorizzazione esplicita; preservare tutte le altre risorse estranee. Bootstrap-01/02 interrotti, zero misure, list vuoto; conservare receipt/errori.
- CP.35CPU/512MiB, collector.1CPU/64MiB; host20CPU:17 VM+1.5 backend+1 operatore. Replica c1 e zero retry; una richiesta originale/un solo handler terminale.
- Nessuna preallocazione promessa: hard RAM limit=memoria del planner, swap0, pool contabile. Profili30 coppie locali, limiti e input verificati; capacità concorrenti, non somme di capacità isolate.
- Logging on per calibrazione/pilot/main, gate off/on soltanto observer; dati/log completi o parziali distinti. Fonte/hash/ricetta/package/protocollo congelati e verificati prima delle VM.
- TDD per modifiche; GitNexus impact valido prima di N code edit e detect_changes completo prima del commit. Indice con relazioni corrotte è irrisolto: riparare/verificare, non basarsi su caller0/UNKNOWN.
- Baseline/EWMA/ORACLE trace identica. Sei pilot triplets, mainN12–36 se si prosegue nello stesso scope; paired bootstrap10k95%, guard lower95>=-.02; tutti i fallimenti restano nel registry, rimpiazzi tecnici preset<=floor(.2N). Intervalli condizionati all'indipendenza, nessuna conclusione confermativa.
- Vecchio last-four-of-six non applicabile ai nove round sincronizzati: tutto il periodo misurato, nessuna steady-state claim. Non usare pilot come main. No merge/push finché non richiesto.

## Review Focus

1. Nome/oracolo della prima funzione accidentalmente riutilizzato per le altre: ogni request deve avere handler, input, digest e prova fisica della cella giusta (Tasks2/3/5).
2. Limite swap solo dichiarato oppure CPU sommata su combinazioni memory-infeasible: test cgroup effettivi e upper bound sicuro sulle configurazioni feasible (Tasks1/3).
3. Capacità isolate promosse a misura del mix/cloud: rifiutare ambito/vettore/quantum cambiato o headroom per funzione insufficiente (Tasks3/4/6).
4. Epoch0/9 interpretati con vecchio modulo 6, alias collisione, cloud diretto contato come offload: exact denominators/tutte9 epoche30 celle e compatibilità vecchia (Tasks4/5).
5. Carico futuro passato a EWMA, richiesta duplicata/censurata o errore algoritmo eliminato: oracle isolato, generator identity/release accounting e registry inconclusive (Tasks4/5/7).

## Task 1: Limiti Docker strict e verifica fisica

**N:** nel modulo `platform/modules/container-deployment-provider`, modificare `ContainerLocalProperties`, `ContainerDeploymentProviderConfiguration`, `DockerJavaContainerRuntimeAdapter`, `CliContainerRuntimeAdapter` e relativi test. Documentare la nuova proprietà operatore. **L:** `config/one_shot.py`, `tasks/one_shot/preflight.py` e test di configurazione/preflight.

- [ ] Ripristinare e verificare GitNexus prima degli edit N; eseguire impact upstream sui simboli cambiati e riportare rischio, chiamanti e processi. Relazioni corrotte e UNKNOWN restano irrisolti; HIGH/CRITICAL richiedono avviso prima degli edit.
- [ ] Scrivere test RED: con strict e limite64MiB, Docker Java imposta Memory=MemorySwap=67108864 e CLI i flag equivalenti; strict senza limite positivo fallisce prima della creazione. Defaultfalse e vecchi costruttori restano compatibili.
- [ ] Implementare `nanofaas.container-local.strict-memory`, defaultfalse, cablaggio e validazione nei due adapter. Abilitarla soltanto nella nuova ricetta sperimentale; nessun nuovo DTO pubblico necessario.
- [ ] Preflight legge limiti Docker e cgroup reali: memory.max=M, memory.swap.max=0, cpu.max. Conservare memory.current/peak/events e OOM. Test RED→GREEN per unità errate, swap diverso da0, limite diverso, cgroup non disponibile ed errore remoto.
- [ ] Gate su container posseduto: allocazione oltre limite produce OOM/evento previsto; prova entro limite resta sana. Conservare errore originale e cleanup. Questa prova verifica enforcement, non qualifica la campagna.
- [ ] Eseguire test Gradle provider pertinenti e pytest preflight/config. Analisi graph changes completa prima del commit locale `Enforce optional strict memory for local containers`.

## Task 2: Cinque workload Rust e risultati attesi indipendenti

**N:** `functions/rust/one-shot-workload/src/{main,lib}.rs`, piccoli wrapper/bin e metadati per le cinque identità; Cargo/Dockerfile/README ove necessario. **L:** catalogo, ricetta multi-funzione, oracoli in `tasks/one_shot/workload.py` oppure nell'esistente calibration.py, test di checksum/catalogo/ricetta/assets installati.

- [ ] Contesto e impact Rust compute/handle e registrazione SDK prima degli edit. Verificare il packaging effettivamente supportato dal catalogo/recipe; condividere implementazione, usare wrapper piccoli, evitare cinque copie.
- [ ] Test RED con vettori attesi indipendenti Rust/Python per aritmetica, ordinamento, scansione memoria, matrici intere e misto: determinismo, seed0/7/u64max, zero lavoro, buffer vuoto, limiti/overflow invalidi. Input/output originali restano compatibili; u64 oltre2^53 non perde precisione nel generatore.
- [ ] Implementare workload limitati con standard library, serde e SDK esistenti, c1/spawn_blocking e nuovo logging. Nessuno sleep per simulare processamento. Validare dimensioni/prodotti prima delle allocazioni.
- [ ] Registrare cinque nomi distinti su ogni nodo; ricetta esporta identità e digest per ciascuno. Un'immagine condivisa è ammessa se handler/input distinti sono provati da test reali e risultati attesi indipendenti.
- [ ] Eseguire Cargo test e pytest catalogo/oracoli/ricetta, wheel e smoke fuori checkout. Graph changes N prima del commit `Add five deterministic Rust one-shot workloads`.

## Task 3: Budget ammissibili e capacità delle30 coppie

**L:** `config/one_shot.py`, `one_shot/{profiles,capacity}.py`, `tasks/one_shot/{capacity,calibration,preflight}.py`; nuovo `tests/one_shot/test_multi_function_capacity.py` e test di profili, sweep, generatore e calibrazione.

- [ ] Test RED: cinque massimi individuali non simultaneamente possibili per memoria non implicano automaticamente overcommit CPU; una combinazione ammissibile che eccede CPU deve invece fallire. Riservare CP/collector/OS; il pool iniziale deve contenere tutte le cinque repliche.
- [ ] Implementare un upper bound conservativo, ad esempio min(sum(q*rmax),pool*max(q/M)), conservando limiti CPU/memoria. Quote finali proporzionali a M_f/pool e arrotondate per difetto; maxr=floor(pool/M_f). Non riscaldare contemporaneamente tutti i massimi incompatibili.
- [ ] Gate memoria64/128/256MiB per funzione, input veri e logging acceso: scegliere il più piccolo limite senza OOM/errori e picco<=75%. Congelare M_f, pool e quote; ripetere il controllo con quote finali e co-locazione.
- [ ] Generalizzare `run_capacity_trial` a matrice nodo/funzione e vettore di repliche. Test RED→GREEN su funzione non prima nel dict, URL/oracolo errato, tentativi duplicati, censura, deficit, lateness, OOM/swap e terminate/reap in caso di errore.
- [ ] Misurare D per tutte30coppie, almeno50sample completi; capacità sui livelli/vettori qualificati, sweep prefissato e tre ripetizioni. Conservare anche i trial falliti. Qualificare mix differenti e prove simultanee sui sei nodi: drift/modelerror<=15%, completamenti>=99%, zero censura/deficit, lateness<=.25s.
- [ ] Versionare l'artefatto multi-funzione: nodo, funzione, vettore, mix, tassi, riferimenti e SHA dei trial. Conservare lettura scalar legacy soltanto per scope compatibile; non promuoverlo a capacità concorrente. Native service-profilev1 resta multi-riga, con quote/input/range verificati per ogni coppia.
- [ ] Test pertinenti e stima del tempo dai trial effettivi prima delle acquisizioni lunghe. Commit `Calibrate multi-function resource-feasible capacity`.

## Task 4: Matrici temporali e qualificazione360s

**L:** `tasks/one_shot/{trace,qualification,experiment}.py`, `one_shot/profiles.py`, preset e test di trace, qualificazione ed evidenza eterogenea.

- [ ] Test RED: nove finestre da360s, trenta celle positive per finestra, grafo a due triangoli, tassi diversi per nodo/funzione/tempo. Cambio del carico e planner sullo stesso confine; generatore e oracle derivano dalla stessa traccia. Quantizzazione che perde celle/sovraccarico/capacità residua fallisce prima delle VM.
- [ ] Costruire da capacità concorrenti nove condizioni: controllo, cinque hotspot diversi, hotspot nei due triangoli, sovraccarico diffuso di alcuni edge, recupero. Mix e seed congelati prima di osservare i risultati degli algoritmi; tassi isolati non sono capacità del mix.
- [ ] Cloud diretto basso per funzione, contabilizzato separatamente. Vettore cloud fisso e ammissibile, margine>=10% per funzione e mix sull'intera traccia. Test per headroom aggregato che nasconde una funzione satura, pool/CPU ecceduti, mix non qualificato e riferimenti modificati.
- [ ] Qualificare l'intera matrice con cloud fisicamente pronto: candidato T=360s, almeno50sample completi per cella, q>=.95, epsilon<=.05, margini .2/.3s e zero censura. Registrare wall time completo, non soltanto solver/CPU.
- [ ] Verificare che EWMA non riceva il futuro di ORACLE, baseline senza peer sia valida e nessuna funzione perda quota/input. Test pertinenti; commit `Synchronize multi-function load and one-shot at six minutes`.

## Task 5: Protocollo e analisi dei nove round

**L:** `one_shot/{campaign,report}.py`, binding di configurazione/experiment, test campaignstatistics/report e nuovo test_synchronized_campaign.py; documentazione pubblica e dossier.

- [ ] Test RED→GREEN: cinque alias biunivoci, nove finestre con una sola epoca ciascuna,360s e54min con sei nodi simultanei. Rifiutare modifiche a codice/runtime/profili/matrice/seed/ordine/analisi prima delle VM. Il vecchio protocollo single-function resta leggibile senza reinterpretazione.
- [ ] Congelare analysisEpochIndices=[0..8]. Denominatore tutti gli originali EDGE pianificati nei nove intervalli; nessun vecchio modulo6/last-four. Exact accounting per funzione/nodo/intervallo, cloud diretto escluso, offload soltanto su prove fisiche terminali. Test per missing/duplicati/funzione sbagliata/u64 grande.
- [ ] Primary paired contrast e completionguard invariati nelle unità dichiarate; bootstrap blocchi interi. Report anche per funzione/intervallo/nodo, evitando che l'aggregato nasconda errori di una funzione. Registry incompleto/algoritmo invalido => inconclusive, non CI selettiva.
- [ ] Report temporale, grafo, matrici delle destinazioni, risorse effettive e tempi complessivi. Stage360s separato da futuro60s; nessuna affermazione di regime stazionario sui nove cambi di carico.
- [ ] Suite CI completa con pin fixture distinto da runtime, tutti gli hook e smoke CLI/assets. Commit `Freeze synchronized multi-function campaign evidence`.

## Task 6: Gate reali e sei triplette pilot

**Artifacts:** dossier L `docs/testing/one-shot-heterogeneous-multipass/`; nuova root operatore `/tmp/oneshot-multifunction-20261010/`. Conservare la root precedente come storico.

- [ ] Nuove immagini da revisioni pulite, inventario host/estranei, fonti/helper/comandi/hash archiviati. Distribution65a40716 non qualifica nuovi workload/strict. Gate N provider/offload/discovery/control-plane/execution-runtime e fullsuite/hook L prima delle VM.
- [ ] Observer bootstrap: tre coppie logging off/on, cinque funzioni, vettori massimi ammissibili, limiti strict, c1 e controllo nativo. Usare Sonata/publicCLI e profili amministrativi esplicitamente non scientifici; goodput/p95<=5% per coppia, cleanup posseduto e risorse estranee preservate.
- [ ] Memoria/quote finali e calibrazione scientifica con logging acceso,30coppie e mix verificati. Raw trial/prove/errori conservati. Modifiche dopo acquisizione invalidano esplicitamente lo scope coinvolto.
- [ ] Qualificazione360s sulle nove matrici, cinquanta campioni per cella, cloud con margine. Ripetere tre coppie observer sulla traccia qualificata prima di accettare pilot/main.
- [ ] Sei triplette pilot bilanciate, publicCLI, topologia nuova per modo:54min misurati e warm>=720s. Sono almeno66min per modo prima di provisioning/cleanup; sei triplette hanno16h12 di solo carico misurato. Pubblicare il costo totale, non chiamarlo54min per tutta la campagna ripetuta.
- [ ] Gate su completamenti effettivi peer e cloud nei modi nativi, saturazione dei limiti e utilizzo VM misurati separatamente, generatore sano/cloud non bottleneck. Copertura mancante => dati conservati e revisione esplicita, non algoritmo corretto per produrre un vantaggio.
- [ ] FailLoad separato sul generatore reale: terminate/reap, collector chiuso, prove/log raccolti best effort, tutte le release e nessun residuo posseduto. Conservare errore originale.
- [ ] Congelare N12–36 dallo stimatore sui nove intervalli del pilot, tutti gli hash, traccia/analisi/seeds/ordini/rimpiazzi. Commit `Record synchronized multi-function pilot qualification`.

## Task 7: Campagna principale e consegna

- [ ] Eseguire i N blocchi appaiati con publicCLI e VM nuove per modo, nove round360s e scope immutabile. Conservare ogni fallimento; rimpiazzi preset<=floor(.2N) soltanto per cause tecniche esterne documentate. Mai retry degli originali o esclusioni associate all'algoritmo.60s non parte automaticamente.
- [ ] Analizzare dopo il congelamento del registro completo: primary/guard/ORACLE secondario, funzioni/intervalli/nodi, latenze/errori/drift/saturazione e completezza dei log. CI95% condizionate all'indipendenza; nessuna conclusione confermativa e nessun completamento inferito dai log.
- [ ] Verificare hash anche dopo compressione, inventario estraneo e assenza di VM/generatore/backend posseduti. Commit locale del dossier; nessun push/merge senza richiesta.
- [ ] Una sola review indipendente finale dell'intero N+L, dati e ledger, come previsto dal metodo native; un passaggio di correzioni verificato. Correzioni che invalidano dati richiedono nuova acquisizione. Conservare ruling/minori e percorsi reali prima del cleanup esclusivo dello workspace SDD previsto dalla skill.

## Self-review

Topologia: Tasks4/6. Cinque Rust e memoria strict:1/2/3. Carico nodo/funzione/tempo e offload:3/4/6.9x360s e54min complessivi:4/5/6. Registry/indipendenza/guard:5/7. Test espliciti coprono combinazioni ammissibili, alias, memoria/swap, mix cloud, quantum e contabilità. Artefatti precedenti distinti;60s richiede nuova decisione e qualificazione.

## Estensione approvata: telemetria continua VM e container

Prima di misure scientifiche, avviare su ciascuna delle sei VM un campionatore Python stdlib locale, posseduto tramite Sonata/NodeResource. Stream JSONL persistito continuamente sull'host, indipendente dal generatore e dai log Fluent Bit/Loki già presenti. Frequenza1Hz per warmup720s e tutte9 epoche360s; timestamp UTC, tempo monotonic e delta effettivo. CPU busy normalizzata alle vCPU, iowait/steal distinti, guest escluso dal doppio conteggio; MemTotal/MemAvailable/used e swap total/free/used in byte. Cgroupv2 per container: CPU usage e quota, memoria corrente/limite/picco/swap, nr_periods/nr_throttled/throttled_usec e delta; identità/incarnazione, ruolo control-plane/collector/function, funzione e replica.

Ogni campione porta run/modo/fase/epoca. La CPU VM è una misura del nodo, non attribuibile artificialmente a una funzione; il vettore di carico identifica tutte5 funzioni dell'epoca, i campioni delle repliche ne identificano una. Errori/dati non disponibili non diventano zeri. Segnalare e archiviare secondi mancanti/duplicati per ciascun nodo, errori e copertura di container, invalidando copertura scientifica incompleta. Preservare raw anche nel fallimento e sincronizzare prima del teardown, mantenendo l'errore primario. Report temporali per nodo CPU/RAM/swap e per container CPU/RAM/throttling, leggibili anche quando evidence.json manca.

Congelare politica1s, asset SHA e package fingerprint prima delle VM. Nessuna modifica post-freeze. Gate reale prima delle misure: tre coppie telemetria off/on con logging acceso, stesso carico concorrente e quote finali; goodput e p95 non peggiorano oltre5% per nodo/funzione. Registrare anche CPU/RAM del campionatore stesso. Gate logging off/on resta separato. Test RED→GREEN per normalizzazione/delta, restart/identità, memoria/swap, confini warmup/epoche, copertura/missing, cleanup/fallimenti e fingerprint/report. Nessun nuovo backend né dipendenza di raccolta.

### Task6 prerequisite: HTTP failure propagation (approved2026-10-10)

Before renewed observer gates, propagate SDK429/503 with typed errors and validated Retry-After, upstream timeout504 and transport502. Preserve metadata in terminal completion/archive/idempotent replay and one-hop forwarding; preserve intentional function status markers and waiter408 behavior. Add dispatcher/controller/lifecycle/replay/offload regression tests and update OpenAPI. Existing interrupted observer-v2 artifacts remain immutable and unqualified. Export a new clean runtime distribution and freeze software-v3 before VM qualification.

Task6 physical-gate follow-up: measure and validate clock skew on all six VMs, while publishing clock health only to the edge negotiation APIs. The terminal cloud intentionally disables those APIs; its clock must still fail qualification when unhealthy. Preserve failed observer-v3 evidence and rebuild the immutable source/operator freeze after this NanoLab fix.

## Revisione temporale richiesta l’11 ottobre: progetto in corso

Il requisito emerso è rendere one-shot robusto al disallineamento UTC dei nodi. Una soglia di clock-health non soddisfa da sola questo requisito. La [bozza di progetto](../specs/2026-10-11-one-shot-clock-robustness-design-draft.md) documenta le dipendenze verificate da UTC e gli invarianti di ammissione, fencing e drain da preservare. È una bozza architetturale, non un protocollo implementato o qualificato; non autorizza a reinterpretare i dati v11 né a rimuovere gate scientifici.

Task6 resta incompleto. Prima della nuova acquisizione occorrono una specifica concreta per l’avvio delle epoche P2P e le transizioni di capacità, test con offset/salti UTC e messaggi ritardati, nuovo fingerprint e nuova qualificazione. UTC resta necessario per correlare le osservazioni, con incertezza esplicita; la sua qualità va valutata separatamente dalla correttezza del protocollo.
