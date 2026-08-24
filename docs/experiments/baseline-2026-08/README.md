# Campagna baseline — agosto 2026

Rimisurare gli esperimenti originali sul codice attuale di nanoFaaS. Non è
un'indagine: le indagini sono finite e stanno in
[`../archive/`](../archive/README.md). Questa campagna produce **i numeri che il
documento cita come prestazioni della piattaforma**.

- **Base**: `main` a `2432a68e` — contiene la ritenzione guidata dal lettore, i
  contatori per percorso (`path=sync|async`), la probe di management su un event
  loop suo.
- **Dati grezzi, report HTML, snapshot Prometheus**: [`raw/`](raw/), un
  sotto-albero per run, archiviato con
  [`../archive/dispatch-bottleneck/archive_run.sh`](../archive/dispatch-bottleneck/archive_run.sh).
- **Regola**: nessun numero in questo documento senza uno script in questa
  directory che lo calcoli dai dati grezzi. Un valore fisicamente impossibile è
  una smentita, non rumore.
- **Stato**: nessuna cella eseguita. Ogni sezione «Risultati» qui sotto è vuota
  di proposito e si riempie man mano.

## Perché rifare

Tre difetti trovati dalla campagna diagnostica hanno cambiato il codice **dopo**
che i numeri originali erano stati presi, e hanno cambiato proprio le cose che
quei numeri misuravano:

1. `ExecutionStore` ritiene per lettore, non per solo orologio. Cambia
   l'occupazione di memoria in regime, quindi il comportamento del collector —
   che è l'oggetto del capitolo su footprint e GC.
2. Il rifiuto avviene prima di costruire l'esecuzione. Sotto sovraccarico la
   maggior parte degli arrivi è un rifiuto, quindi cambia il costo dominante.
3. La probe di management ha il proprio event loop. Cambia chi muore e quando,
   e quindi se una serie lunga arriva alla fine.

Ogni numero preso prima di questi tre è preso su un'altra piattaforma.

## Metriche: quale set, e a che prezzo

**Risposta breve: il set massimo, ovunque. Non c'è controindicazione seria, ed è
già il default in due casi su tre.** Le tre cose da sapere:

**1. Il profilo `advanced` è già acceso per ogni load test.**
`plans/loadtest.py` passa `metrics_profile="advanced"` senza condizioni, e la
via container fa lo stesso (`components/helm.py`). Non c'è niente da cambiare
negli scenari. Cosa aggiunge rispetto a `basic`:

| Famiglia | Cosa dà | Costo misurato (dai commenti in `MetricsProfileConfiguration`) |
|---|---|---|
| Istogrammi percentile su 4 timer | le code, non solo la media | serie = bucket × timer × funzione — il costo vero, ma è memoria di Prometheus, non percorso caldo |
| `function_init_duration_ms`, `function_queue_wait_ms` | scomposizione della latenza | 2,00 record per dispatch = **0,0105%** di un budget bi-core a 2430 req/s |
| Prefissi diagnostici (`function_scheduler_`, `function_dispatch_slot_hold_`, …) | dove va il tempo dentro al dispatch | 15,74 record per dispatch × 43 ns = **0,079%** dello stesso budget |
| Gauge di concorrenza e runtime-config | cosa ha deciso il governor | gauge a supplier, letti allo scrape: costo nullo sul percorso caldo |

Meno di un decimo di percento in tutto, e lo pagano **tutte** le celle allo
stesso modo: un confronto fra build non si sposta, si sposta solo di quel
decimo il valore assoluto.

**2. `container_metrics` invece è acceso solo per il confronto fra build.**
`runtime_comparison.py` lo mette a `True`; il resto dei load test lo lascia a
`False`. Accende uno scrape del kubelet e la RBAC per raggiungerlo, e porta
`container_memory_working_set_bytes` e `container_cpu_usage_seconds_total` per
ogni container — control plane **e funzioni**.

È la famiglia che ha risolto l'indagine sul dispatch: il tetto a ~300 disp/s era
`limits.cpu: "1"`, e si è visto perché il throttling era all'85%. Senza queste
serie un run registra solo l'heap JVM del control plane — un numero che una
build nativa non pubblica affatto, e che comunque non dice nulla delle funzioni.

→ **Da accendere anche in B**, dove oggi è spento. È l'unica modifica di
strumentazione che questa campagna richiede.

**3. La sola vera controindicazione: «massimo» non vuol dire «una lista unica».**
Il catalogo (`metrics/catalogue.py`) è raggruppato per pubblicatore proprio per
questo, e la sua docstring racconta i due modi in cui una lista scritta a mano
ha già fallito, tutti e due in silenzio:

- *incompleta*: `sync-queue` pubblica cinque metriche, nessuna era nella lista →
  un confronto a nove celle ha registrato zero rifiuti mentre la piattaforma ne
  rifiutava 29 555, con la coda a 100 su 100;
- *sovra-completa*: serie dell'autoscaler chieste a run che non l'avevano
  caricato → risultato vuoto, che significa «nessuno poteva rispondere» ma si
  legge identico a «non è successo».

Quindi il set massimo va inteso come *tutto ciò che i moduli di quel run possono
pubblicare*, che è esattamente ciò che il catalogo già fa. Il corollario
pratico: una build nativa non pubblica `jvm_gc_pause_seconds` né
`jvm_heap_used_bytes`, e infatti il piano di confronto passa
`jvm_metrics_required=False`. Pretenderle produrrebbe un fallimento falso.

Due avvertenze minori, che non cambiano la decisione ma vanno ricordate:

- Gli istogrammi vivono nell'heap del control plane. In **A2**, che misura la
  memoria, lo strumento sta dentro l'oggetto misurato. È limitato (4 timer × 2
  funzioni fisse) e identico in tutte le celle, quindi non confonde il
  confronto — ma va detto, non scoperto dopo.
- I meter per funzione spariscono quando la funzione viene cancellata. È il
  fallimento `function_enqueue_total not found` di validate-k3s: raccogliere
  **prima** del teardown, o tenere viva l'ultima funzione.

---

# A — Il nucleo della campagna

## A1 — Quanto costa il control plane, per build

**Domanda.** A parità di funzioni e di carico, quanto throughput, quanta
latenza, quanta memoria e quanto tempo di GC costa ciascun modo di compilare lo
stesso sorgente?

**Impianto.**

```bash
(cd ../nanolab && ./nanolab.sh compare \
    packages/nanolab/scenarios-v2/runtime-comparison-jvm.yaml \
    --environment packages/nanolab/environments/azure-comparison.yaml \
    --repetitions 3)
```

Quattro varianti — `jvm` (Java 25 JIT), `native-os` (−Os, GC seriale),
`native-o3` (−O3, GC seriale), `native-o3-g1` (−O3, G1 su Oracle GraalVM) — per
tre ripetizioni, celle interlacciate. Funzioni fisse `word-stats-java` e
`word-stats-javascript`, costruite una volta sola nella fase di prepare. Profilo
di carico `comparison`: 450 s per cella, due rampe con due picchi
(30 s warm → base → picco → recupero → alto → picco più duro → drain).

**Il limite di CPU, che nessuno imposta.** Nulla in NanoLab passa un limite di
CPU per il control plane: `control_plane_helm_values` non lo tocca e il piano di
confronto nemmeno, quindi vale il default del chart, `limits.cpu: "1"`. È la
condizione originale — ed è anche quella che la campagna diagnostica ha
smontato: a 1 CPU il tetto era ~300 dispatch/s con l'85% dei periodi strozzati,
e a 4 CPU diventava 843/s con lo 0%. Il profilo `comparison` offre fino a
900+315 rps, cioè molto oltre quel muro.

Conseguenza: se lo strozzamento è alto, le quattro build sono appoggiate allo
stesso muro e il confronto misura chi rifiuta meglio, non quanto costa ciascuna.
`tables.py` calcola `periodi CPU strozzati (%)` proprio per rendere la domanda
decidibile dai dati invece che dall'aspettativa. Se risulta alto serve un secondo
braccio con un limite che non sia il collo di bottiglia.

**Cosa conta come risposta.** Throughput sostenuto e p50/p95 per variante, RSS
del container, pause del collector, dimensione dell'immagine, tempo di avvio —
tutti dallo stesso run, con la dispersione su tre ripetizioni. Separazione in
deviazione standard aggregata, mai in p-value: tre celle non lo reggono.

**Costo.** 12 celle × 7,5 min di carico, più le tre compilazioni native. Il
collo di bottiglia è G1 su Oracle GraalVM: su una VM da 12 GB è stato ucciso
dall'OOM killer dopo 9m50s, e sotto un limite di memoria ha impiegato 94 minuti
senza finire. Da qui la `Standard_D8s_v5` con 32 GB e `--native-build-memory`
lasciato libero.

**Risultati.**

<!-- A1:inizio -->
_Da eseguire._
<!-- A1:fine -->

## A2 — Dove sta il ginocchio della memoria

**Domanda.** Sotto quale limite di memoria la risposta di A1 cambia — e quale
build vince da quella parte del ginocchio?

**Impianto.** A1 ripetuto con `resources.limits.memory` a 500 MB, 1 GB, 2 GB.
Versione integrale: 3 × A1. Versione economica, se A1 mostra che due varianti si
distinguono e due no: 2 varianti × 3 limiti × 3 ripetizioni = 9 celle.

**Cosa conta come risposta.** Il limite sotto il quale una variante smette di
servire il carico, e il *modo* in cui smette. La volta scorsa il risultato
controintuitivo era che G1 sotto un limite piccolo è una trappola — e a 500 MB
non ci stava nulla. Vale la pena rifarlo perché la ritenzione guidata dal lettore
ha cambiato proprio l'occupazione in regime.

**Nota.** È l'unico pezzo del capitolo footprint che A1 non copre. Ed è
l'esperimento in cui lo strumento sta dentro l'oggetto misurato (vedi sopra).

**Risultati.**

<!-- A2:inizio -->
_Da eseguire._
<!-- A2:fine -->

## A3 — Le due porte restano distinguibili

**Domanda.** Con sync e async sulla stessa coda, le metriche separano ancora i
due percorsi, e l'idempotenza tiene sotto carico?

**Impianto.** Matrice a quattro bracci — `sync-baseline` e `mixed-workload`, a
2x e 3x — per tre ripetizioni. Il generatore è uno solo, con la mescolanza come
parametro (`K6_ASYNC_SHARE`, `K6_IDEM_SHARE`).

**Prerequisito.** Scenari e generatore (`mixed-workload.js`) vivono sul branch
`dispatch-instrumentation` di NanoLab, non su `main`. Va fatto il merge prima.

**Cosa conta come risposta.** I contatori `path=sync|async` devono muoversi
indipendentemente; le coppie idempotenti devono concordare sull'`X-Execution-Id`;
la probe deve restare sotto il budget di 1 s. **Il difetto noto da riconfermare o
smentire**: nell'ultima misura, a 3x le due porte venivano rifiutate al 15,7% e
al 15,4% — indistinguibili. L'equità è la politica sbagliata quando le scadenze
sono diverse.

**Risultati.**

<!-- A3:inizio -->
_Da eseguire._
<!-- A3:fine -->

---

# B — Sensati, in ordine di quanto è cambiato sotto

## B1 — I controllori di concorrenza

**Domanda.** `ADAPTIVE_PER_POD`, `BUDGETED` e `SOJOURN` si comportano ancora come
misurato, ora che un rifiuto costa meno?

**Impianto.** `concurrency-openloop-adaptive.yaml`,
`concurrency-openloop-sojourn.yaml`, `concurrency-saturation-adaptive.yaml`,
`concurrency-saturation-budgeted.yaml`. Ciclo aperto, non chiuso: il ciclo
chiuso non può porre questa domanda, perché gli arrivi si adattano al servizio.

**Perché è primo in B.** `SOJOURN` decide dalla latenza del chiamante, e il
rifiuto anticipato ha cambiato quanto costa un arrivo respinto — cioè una
componente del segnale su cui decide.

**Cosa conta come risposta.** Concorrenza concessa per finestra, latenza del
chiamante, tasso di rifiuto. E la taratura ancora aperta delle soglie 0,5 / 0,15.

**Risultati.**

<!-- B1:inizio -->
_Da eseguire._
<!-- B1:fine -->

## B2 — Co-tenancy: cross-talk senza contesa

**Domanda.** Due funzioni sullo stesso nodo si disturbano, e quanto, sotto le
quattro politiche?

**Impianto.** `concurrency-burst-cotenancy-{adaptive,sojourn,budgeted,budgeted-native}.yaml`.

**Cosa conta come risposta.** Conferma, non scoperta: misurato il 2026-08-16 che
il cross-talk è debole, che la cricchetta è assente e che la sotto-utilizzazione
è smentita (2818 rps insieme contro 1821 da sola). Serve la stessa misura sullo
stesso hardware degli altri esperimenti di questa campagna.

**Risultati.**

<!-- B2:inizio -->
_Da eseguire._
<!-- B2:fine -->

## B3 — Autoscaler interno contro HPA

**Domanda.** Le due strade per decidere quante repliche danno la stessa
traiettoria sullo stesso ciclo di carico?

**Impianto.** `autoscaling-cycle-k8s.yaml` contro `autoscaling-cycle-k8s-hpa.yaml`.

**Cosa conta come risposta.** Traiettoria delle repliche, ritardo di reazione,
overshoot. Con l'HPA serve anche il verdetto del controller, non solo il valore
della metrica: un plateau a `maxReplicas` non si distingue altrimenti da una
metrica che ha smesso di salire.

**Nota.** Codice non toccato dai tre difetti. Si rifà solo per avere la serie
sullo stesso hardware, non perché ci si aspetti un risultato diverso.

**Risultati.**

<!-- B3:inizio -->
_Da eseguire._
<!-- B3:fine -->

## B4 — Offload edge→cloud sotto pressione

**Domanda.** Il proxy trasparente verso l'istanza remota scatta quando deve, e
cosa costa l'hop?

**Impianto.** `edge-cloud-offload-policy.yaml` e
`edge-cloud-offload-contract.yaml`, ambiente `multipass-offload`.

**Cosa conta come risposta.** Latenza con e senza hop, tasso di attivazione per
trigger (`EAGER`, `DEPTH`, `EST_WAIT`), comportamento al fallimento remoto
(502/504, nessun fallback locale).

**Nota.** Il trigger `EST_WAIT` dipende dall'ammissione, che è la cosa ancora
aperta da A3. Se A3 conferma che le due porte sono indistinguibili, questo
esperimento misura un trigger che non discrimina.

**Risultati.**

<!-- B4:inizio -->
_Da eseguire._
<!-- B4:fine -->

## B5 — I quattro SDK a confronto

**Domanda.** Cosa costa il runtime di funzione, per linguaggio, a parità di
lavoro?

**Impianto.** `concurrency-cycle-container-{go,javascript,python}.yaml` più la
variante Java, backend container.

**Cosa conta come risposta.** Throughput per core, latenza, RSS del container di
funzione. Qui `container_metrics` è indispensabile: la memoria delle funzioni non
compare da nessun'altra parte.

**Confondente noto da evitare.** I due `word-stats` non erano la stessa cosa —
va verificato che le implementazioni facciano lo stesso lavoro prima di
attribuire la differenza al linguaggio.

**Risultati.**

<!-- B5:inizio -->
_Da eseguire._
<!-- B5:fine -->

## B6 — Cold start

**Domanda.** Quanto costa il risveglio da zero repliche, per build del control
plane e per linguaggio della funzione?

**Impianto.** `autoscaling-cycle-k8s-hpa.yaml` con scale-to-zero, ambiente
`multipass-hpa-scale-to-zero`.

**Cosa conta come risposta.** `function_cold_start_total` contro
`function_warm_start_total`, e `function_init_duration_ms` — che esiste solo
sotto il profilo `advanced`.

**Nota.** Codice non toccato. Ultimo in ordine di priorità.

**Risultati.**

<!-- B6:inizio -->
_Da eseguire._
<!-- B6:fine -->

---

## Difetti trovati dalla campagna

Annotati quando emergono, corretti **dopo**: cambiare il codice mentre la
matrice lo misura invaliderebbe le celle gia' prese.

### `jvm_gc_time_fraction` e' NaN sulla build JVM

Trovato in A1, cella `jvm/run-1`. Il gauge e' NaN in tutti e 97 i punti sulla
JVM e funziona sulla nativa (0 punti NaN). Cioe' la metrica scritta apposta per
essere confrontabile fra build — `GcMetricsConfiguration` lo dice nel commento,
«registrata su entrambe perche' una diagnostica presente in una sola
configurazione non puo' servire a confrontarle» — e' proprio quella che fallisce
su una delle due.

Meccanismo quasi certo: `registry.gauge(nome, tag, oggetto, funzione)` tiene un
riferimento **debole** all'oggetto, e l'oggetto e' la lista fresca restituita da
`ManagementFactory.getGarbageCollectorMXBeans()`, che nessun altro trattiene. Su
HotSpot viene raccolta e il gauge riporta NaN; su SubstrateVM sopravvive.

Non blocca la campagna: `jvm_gc_collection_time` e `jvm_gc_collection_count`
vengono dal polling dell'MXBean, rispondono su entrambe le VM, e sono le serie
che `tables.py` usa per la frazione di tempo in GC. Correzione: tenere un
riferimento forte alla lista.

## Protocollo comune

Invariante fra le celle, perché un confronto con due parti mobili non è un
confronto:

- **Funzioni fisse** (`word-stats-java`, `word-stats-javascript`), stessa
  immagine per tutte le varianti — le costruisce la fase di prepare, una volta.
- **Niente governor e niente autoscaler** in A1/A2: entrambi muoverebbero i
  limiti di concorrenza sotto le build che si stanno confrontando. Lo impone lo
  schema dello scenario.
- **Generatore su una VM separata** (ruolo `loadgen`), altrimenti k6 compete con
  k3s, il control plane e Prometheus per gli stessi core. È la limitazione
  permanente di ogni numero preso su multipass.
- **Varianti interlacciate, non a blocchi** (`build_matrix`): una deriva del
  provider durante la notte colpirebbe altrimenti una sola build.
- **3 ripetizioni**, il minimo che mostra dispersione.
- **Helm disinstallato prima di ogni run.** Un release riusato con un tag mutabile
  non fa rollout: si finisce a misurare l'immagine precedente. `imagePullPolicy:
  Always` non salva, aiuta solo un pod nuovo. La prova che il pod è quello giusto
  è `creationTimestamp`/`startedAt`, non l'assenza di una serie.
- **Teardown verificato.** `teardown.sh` esce con 1 se resta residuo: ha già
  stampato «finito» lasciando accese una VM, la sua rete e il suo disco.

## Registro delle run

| Run | Esperimento | Data | Celle | Directory in `raw/` | Esito |
|---|---|---|---|---|---|
| _(nessuna)_ | | | | | |
