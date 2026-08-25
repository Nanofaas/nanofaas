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

## La campagna in una schermata

Calcolata da `sintesi.py` su `raw/`. Le tabelle per esperimento stanno piu'
sotto, con lo spread che qui non ci starebbe.

<!-- sintesi:inizio -->
| esperimento | condizione | celle | offerte/s | servite/s | HTTP falliti | p95 | tutte vive |
|---|---|---|---|---|---|---|---|
| A1-cpu1 | 4 build · 1 core · 2 GiB | 12 | 435 | 239 | 17.3% | 154.7 ms | sì |
| A1b-cpu2 | 4 build · 2 core · 2 GiB | 12 | 435 | 286 | 4.9% | 120.6 ms | sì |
| A2-mem1024 | 4 build · 2 core · 1 GiB | 12 | 435 | 284 | 5.5% | 124.4 ms | sì |
| A2-mem512 | 4 build · 2 core · 512 MiB | 12 | 430 | 242 | 25.6% | 290.0 ms | sì |
| A1c | jvm vs c2 · 1 core · 2 GiB | 6 | 435 | 262 | 10.8% | 77.7 ms | sì |
| A1d | jvm vs c2 · 2 core · 2 GiB | 6 | 435 | 301 | 0.3% | 4.0 ms | sì |
| A2c | jvm vs c2 · 2 core · 1 GiB | 6 | 435 | 302 | 0.1% | 5.8 ms | sì |
| A2d | jvm vs c2 · 2 core · 512 MiB | 6 | 419 | 197 | 64.7% | 2.7 ms | sì |
| A3-sync-2x | jvm-c2 · sync · 2x | 3 | 871 | 587 | 1.6% | 4.6 ms | sì |
| A3-misto-2x | jvm-c2 · misto · 2x | 3 | 914 | 584 | 2.0% | 5.2 ms | sì |
| A3-sync-3x | jvm-c2 · sync · 3x | 3 | 1306 | 804 | 9.6% | 58.9 ms | sì |
| A3-misto-3x | jvm-c2 · misto · 3x | 3 | 1371 | 776 | 12.6% | 169.3 ms | sì |
<!-- sintesi:fine -->

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
| Build | Runs | Throughput (rps) | p50 (ms) | p95 (ms) | p99 (ms) | Shed (%) | Control plane peak (MiB) | Control plane CPU (cores) |
|---|---|---|---|---|---|---|---|---|
| JVM (Java 25, JIT) | 3 | 435.1 ± 0.0 | 4.7 ± 0.1 | 182.9 ± 2.0 | 253.3 ± 4.0 | 25.33 ± 0.31 | 1068.4 ± 2.1 | 0.60 ± 0.00 |
| Native, -Os, serial GC | 3 | 435.1 ± 0.0 | 2.9 ± 0.4 | 175.6 ± 6.4 | 406.3 ± 9.0 | 18.82 ± 0.71 | 242.2 ± 13.5 | 0.52 ± 0.01 |
| Native, -O3, serial GC | 3 | 435.1 ± 0.0 | 2.9 ± 1.0 | 182.5 ± 27.8 | 422.2 ± 19.3 | 15.79 ± 0.62 | 254.9 ± 18.6 | 0.50 ± 0.01 |
| Native, -O3, G1 (Oracle GraalVM) | 3 | 435.1 ± 0.0 | 1.7 ± 0.0 | 77.8 ± 2.3 | 137.0 ± 4.9 | 9.17 ± 0.78 | 462.4 ± 9.9 | 0.47 ± 0.00 |

Quello che il report di confronto non guarda:

| Build | Servite/s (dispatch) | Vivo a fine cella | CPU strozzata (%) | Heap picco (MB) | Collezioni GC | Pausa GC media (ms) | Tempo in GC (%) | gauge gc_time_fraction | Compilazione (s) | Immagine (MB) |
|---|---|---|---|---|---|---|---|---|---|---|
| JVM (Java 25, JIT) | 210 ± 1 | si | — | 939 ± 3 | 1352 ± 6 | 6.6 ± 0.3 | 1.87 ± 0.08 | NaN | 46.3 | 244 |
| Native, -Os, serial GC | 235 ± 2 | si | — | 201 ± 2 | 1315 ± 3 | 29.2 ± 1.9 | 7.99 ± 0.50 | ok | 355.4 | 242 |
| Native, -O3, serial GC | 245 ± 2 | si | — | 216 ± 7 | 1347 ± 34 | 30.7 ± 3.8 | 8.60 ± 0.86 | ok | 271.4 | 278 |
| Native, -O3, G1 (Oracle GraalVM) | 267 ± 3 | si | — | — | — | — | — | ok | 779.1 | 840 |
<!-- A1:fine -->

## A1b — Le stesse build senza il muro di CPU

**Domanda.** Tolto il limite che le schiaccia tutte contro lo stesso tetto,
quanto costa davvero ciascuna build?

**Impianto.** `runtime-comparison-cpu2.yaml`: identico a A1 tranne che dichiara
`limits.cpu: 2`. Due core e non quattro perche' lo sweep archiviato colloca il
ginocchio fra uno e due — lo shed scende da 24,6% a 11,0% e poi non si muove
piu' a tre e a quattro. Le celle oltre il ginocchio costano le stesse ore e
misurano la stessa cosa.

**La predizione da falsificare.** A un core i tre fix hanno spostato tutte e tre
le build native del 12–16% e la JVM di zero: 210 ± 1 dispatch/s in entrambe le
campagne. Se quella piattezza e' contesa per l'unico core — thread del JIT e del
collector contro il lavoro di richiesta — a due core la JVM deve guadagnare. Se
e' un limite del suo percorso di codice, restera' piatta anche li'.

**Risultati.**

<!-- A1b:inizio -->
| Build | Runs | Throughput (rps) | p50 (ms) | p95 (ms) | p99 (ms) | Shed (%) | Control plane peak (MiB) | Control plane CPU (cores) |
|---|---|---|---|---|---|---|---|---|
| JVM (Java 25, JIT) | 3 | 435.1 ± 0.0 | 1.9 ± 0.1 | 16.3 ± 11.4 | 25.6 ± 17.1 | 0.39 ± 0.15 | 1571.3 ± 1.2 | 0.69 ± 0.02 |
| Native, -Os, serial GC | 3 | 435.1 ± 0.0 | 1.7 ± 0.0 | 241.8 ± 13.7 | 431.0 ± 10.6 | 10.09 ± 0.40 | 277.8 ± 17.7 | 0.61 ± 0.01 |
| Native, -O3, serial GC | 3 | 435.0 ± 0.1 | 1.7 ± 0.0 | 221.3 ± 11.8 | 415.0 ± 6.3 | 9.10 ± 0.61 | 282.5 ± 23.1 | 0.57 ± 0.00 |
| Native, -O3, G1 (Oracle GraalVM) | 3 | 435.0 ± 0.0 | 1.5 ± 0.0 | 3.1 ± 0.1 | 18.8 ± 1.1 | 0.04 ± 0.02 | 551.5 ± 2.5 | 0.50 ± 0.01 |

Quello che il report di confronto non guarda:

| Build | Servite/s (dispatch) | Vivo a fine cella | CPU strozzata (%) | Heap picco (MB) | Collezioni GC | Pausa GC media (ms) | Tempo in GC (%) | gauge gc_time_fraction | Compilazione (s) | Immagine (MB) |
|---|---|---|---|---|---|---|---|---|---|---|
| JVM (Java 25, JIT) | 301 ± 1 | si | 5.4 ± 2.8 | 1005 ± 12 | 1542 ± 2 | 4.5 ± 0.2 | 1.45 ± 0.07 | NaN | 43.1 | 244 |
| Native, -Os, serial GC | 269 ± 1 | si | 1.6 ± 0.2 | 249 ± 3 | 1494 ± 23 | 32.8 ± 1.7 | 10.20 ± 0.37 | ok | 371.7 | 242 |
| Native, -O3, serial GC | 272 ± 2 | si | 1.1 ± 0.2 | 254 ± 9 | 1480 ± 8 | 31.8 ± 1.2 | 9.80 ± 0.32 | ok | 281.6 | 278 |
| Native, -O3, G1 (Oracle GraalVM) | 302 ± 0 | si | 1.7 ± 0.1 | — | — | — | — | ok | 846.4 | 840 |
<!-- A1b:fine -->

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

A 1024 MiB:

<!-- A2-1024:inizio -->
| Build | Runs | Throughput (rps) | p50 (ms) | p95 (ms) | p99 (ms) | Shed (%) | Control plane peak (MiB) | Control plane CPU (cores) |
|---|---|---|---|---|---|---|---|---|
| JVM (Java 25, JIT) | 3 | 435.1 ± 0.0 | 1.7 ± 0.0 | 3.3 ± 0.2 | 11.5 ± 0.6 | 0.02 ± 0.00 | 866.0 ± 0.2 | 0.66 ± 0.01 |
| Native, -Os, serial GC | 3 | 435.1 ± 0.0 | 1.5 ± 0.0 | 224.4 ± 15.2 | 427.0 ± 14.4 | 9.10 ± 0.49 | 279.1 ± 7.7 | 0.58 ± 0.01 |
| Native, -O3, serial GC | 3 | 435.0 ± 0.1 | 1.5 ± 0.0 | 237.6 ± 20.5 | 419.8 ± 8.2 | 10.10 ± 1.08 | 272.2 ± 14.0 | 0.56 ± 0.00 |
| Native, -O3, G1 (Oracle GraalVM) | 3 | 435.0 ± 0.0 | 1.3 ± 0.0 | 32.4 ± 21.5 | 268.9 ± 138.8 | 2.69 ± 1.67 | 292.1 ± 0.8 | 0.59 ± 0.02 |

Quello che il report di confronto non guarda:

| Build | Servite/s (dispatch) | Vivo a fine cella | CPU strozzata (%) | Heap picco (MB) | Collezioni GC | Pausa GC media (ms) | Tempo in GC (%) | gauge gc_time_fraction | Compilazione (s) | Immagine (MB) |
|---|---|---|---|---|---|---|---|---|---|---|
| JVM (Java 25, JIT) | 302 ± 0 | si | 3.0 ± 1.0 | 665 ± 7 | 846 ± 8 | 5.8 ± 0.1 | 1.03 ± 0.01 | NaN | 43.9 | 244 |
| Native, -Os, serial GC | 272 ± 2 | si | 1.3 ± 0.2 | 261 ± 2 | 1465 ± 76 | 31.2 ± 1.8 | 9.52 ± 0.31 | ok | 328.3 | 242 |
| Native, -O3, serial GC | 269 ± 3 | si | 1.1 ± 0.1 | 244 ± 5 | 1482 ± 24 | 33.5 ± 2.7 | 10.33 ± 0.67 | ok | 264.3 | 278 |
| Native, -O3, G1 (Oracle GraalVM) | 293 ± 6 | si | 7.7 ± 2.3 | — | — | — | — | ok | 813.8 | 840 |
<!-- A2-1024:fine -->

A 512 MiB. **Da leggere con una riserva**: questo braccio e' partito prima che
il piano raccogliesse i contatori della seconda funzione, quindi la sua colonna
«shed» copre solo `word-stats-java`. Nella cella `jvm/run-1` k6 riporta il
30,4% di richieste fallite contro lo 0,3% di rifiuti visti dalla piattaforma:
la differenza e' carico rifiutato a `word-stats-javascript`, che nessuna serie
di quel run registra. A2d rimisura lo stesso tetto con la raccolta completa.


<!-- A2-512:inizio -->
| Build | Runs | Throughput (rps) | p50 (ms) | p95 (ms) | p99 (ms) | Shed (%) | Control plane peak (MiB) | Control plane CPU (cores) |
|---|---|---|---|---|---|---|---|---|
| JVM (Java 25, JIT) | 3 | 418.4 ± 12.0 | 2.1 ± 0.5 | 3.6 ± 0.2 | 36.8 ± 23.7 | 56.32 ± 23.69 | 496.9 ± 22.0 | 0.38 ± 0.10 |
| Native, -Os, serial GC | 3 | 435.1 ± 0.0 | 2.7 ± 0.0 | 194.0 ± 55.8 | 397.7 ± 34.8 | 8.83 ± 2.41 | 349.4 ± 112.4 | 0.59 ± 0.01 |
| Native, -O3, serial GC | 3 | 435.1 ± 0.0 | 2.6 ± 0.0 | 210.8 ± 42.8 | 403.4 ± 20.2 | 9.09 ± 1.76 | 299.2 ± 36.1 | 0.56 ± 0.01 |
| Native, -O3, G1 (Oracle GraalVM) | 3 | 431.2 ± 0.2 | 2.7 ± 0.0 | 751.4 ± 4.2 | 1300.1 ± 101.0 | 28.25 ± 0.56 | 165.2 ± 1.6 | 0.82 ± 0.01 |

Quello che il report di confronto non guarda:

| Build | Servite/s (dispatch) | Vivo a fine cella | CPU strozzata (%) | Heap picco (MB) | Collezioni GC | Pausa GC media (ms) | Tempo in GC (%) | gauge gc_time_fraction | Compilazione (s) | Immagine (MB) |
|---|---|---|---|---|---|---|---|---|---|---|
| JVM (Java 25, JIT) | 212 ± 41 | si | 0.6 ± 0.1 | 316 ± 48 | 475 ± 47 | 5.4 ± 2.0 | 0.95 ± 0.07 | NaN | 15.3 | 244 |
| Native, -Os, serial GC | 273 ± 8 | si | 1.8 ± 0.3 | 258 ± 9 | 1505 ± 36 | 26.2 ± 6.4 | 8.17 ± 1.87 | ok | 250.8 | 242 |
| Native, -O3, serial GC | 272 ± 6 | si | 1.0 ± 0.2 | 267 ± 31 | 1466 ± 79 | 30.9 ± 5.3 | 9.40 ± 1.32 | ok | 260.8 | 278 |
| Native, -O3, G1 (Oracle GraalVM) | 211 ± 2 | si | 20.6 ± 1.0 | — | — | — | — | ok | 836.9 | 840 |
<!-- A2-512:fine -->

## A1c–A2d — La JVM con il suo compilatore ottimizzante

**Domanda.** Quanto della distanza fra la JVM e le build native e' AOT contro
JIT, e quanto e' soltanto `-XX:TieredStopAtLevel=1`?

**Impianto.** Quattro run da sei celle, `--variants jvm,jvm-c2`, ai punti gia'
misurati dagli altri bracci: 1 core, e poi 2 core ai tre tetti di memoria.
`jvm` rientra in ogni run come baseline interna, cosi' la coppia confrontata
sta sempre sulla stessa macchina e nella stessa ora; le celle degli altri
bracci restano come controllo incrociato fra sessioni diverse.

I tre run a 2 core girano sulla stessa VM senza teardown intermedio: sono lo
stesso sweep, e le due immagini confrontate sono cosi' letteralmente gli stessi
byte a tutti e tre i tetti.

Il prepare qui costa due o tre minuti invece di venticinque, perche' nessuna
delle due varianti e' un'immagine nativa.

**Sono anche i primi run con le serie di GC separate per generazione**, quindi
dicono quante collezioni sono giovani e quante complete — la domanda rimasta
aperta su perche' stringere l'heap migliori la JVM.

### A1c — 1 core, 2 GiB

<!-- A1c:inizio -->
| Build | Runs | Throughput (rps) | p50 (ms) | p95 (ms) | p99 (ms) | Shed (%) | Control plane peak (MiB) | Control plane CPU (cores) |
|---|---|---|---|---|---|---|---|---|
| JVM (Java 25, JIT) | 3 | 435.1 ± 0.0 | 3.0 ± 0.5 | 151.1 ± 21.5 | 206.2 ± 30.3 | 20.83 ± 3.31 | 1097.8 ± 18.0 | 0.56 ± 0.02 |
| JVM (serial GC, full tiering) | 3 | 435.1 ± 0.0 | 1.2 ± 0.0 | 4.3 ± 0.4 | 45.9 ± 13.0 | 0.72 ± 0.29 | 1643.3 ± 5.3 | 0.41 ± 0.01 |

Quello che il report di confronto non guarda:

| Build | Servite/s (dispatch) | Vivo a fine cella | CPU strozzata (%) | Heap picco (MB) | Collezioni GC | Pausa GC media (ms) | Tempo in GC (%) | gauge gc_time_fraction | Compilazione (s) | Immagine (MB) |
|---|---|---|---|---|---|---|---|---|---|---|
| JVM (Java 25, JIT) | 225 ± 12 | si | 22.0 ± 0.8 | 972 ± 20 | 1402 ± 32 | 5.5 ± 0.2 | 1.61 ± 0.07 | NaN | 43.4 | 244 |
| JVM (serial GC, full tiering) | 300 ± 1 | si | 4.6 ± 0.4 | 995 ± 15 | 1851 ± 8 | 3.6 ± 0.1 | 1.40 ± 0.03 | NaN | 16.1 | 244 |
<!-- A1c:fine -->

### A1d — 2 core, 2 GiB

<!-- A1d:inizio -->
| Build | Runs | Throughput (rps) | p50 (ms) | p95 (ms) | p99 (ms) | Shed (%) | Control plane peak (MiB) | Control plane CPU (cores) |
|---|---|---|---|---|---|---|---|---|
| JVM (Java 25, JIT) | 3 | 435.1 ± 0.0 | 1.8 ± 0.1 | 6.1 ± 4.3 | 16.6 ± 14.6 | 0.33 ± 0.06 | 1571.8 ± 1.2 | 0.65 ± 0.02 |
| JVM (serial GC, full tiering) | 3 | 435.1 ± 0.0 | 1.1 ± 0.1 | 2.0 ± 0.5 | 5.8 ± 1.2 | 0.23 ± 0.15 | 1649.4 ± 10.2 | 0.37 ± 0.07 |

Quello che il report di confronto non guarda:

| Build | Servite/s (dispatch) | Vivo a fine cella | CPU strozzata (%) | Heap picco (MB) | Collezioni GC | Pausa GC media (ms) | Tempo in GC (%) | gauge gc_time_fraction | Compilazione (s) | Immagine (MB) |
|---|---|---|---|---|---|---|---|---|---|---|
| JVM (Java 25, JIT) | 301 ± 0 | si | 2.2 ± 2.6 | 1004 ± 16 | 1546 ± 10 | 4.3 ± 0.1 | 1.39 ± 0.02 | NaN | 43.0 | 244 |
| JVM (serial GC, full tiering) | 301 ± 0 | si | 0.1 ± 0.1 | 1103 ± 147 | 1270 ± 890 | 13.0 ± 14.5 | 1.06 ± 0.39 | NaN | 15.4 | 244 |
<!-- A1d:fine -->

### A2c — 2 core, 1 GiB

<!-- A2c:inizio -->
| Build | Runs | Throughput (rps) | p50 (ms) | p95 (ms) | p99 (ms) | Shed (%) | Control plane peak (MiB) | Control plane CPU (cores) |
|---|---|---|---|---|---|---|---|---|
| JVM (Java 25, JIT) | 3 | 435.1 ± 0.0 | 1.9 ± 0.3 | 9.3 ± 9.8 | 17.2 ± 12.5 | 0.08 ± 0.09 | 867.3 ± 2.1 | 0.67 ± 0.08 |
| JVM (serial GC, full tiering) | 3 | 435.1 ± 0.0 | 1.2 ± 0.2 | 2.3 ± 0.6 | 7.1 ± 1.2 | 0.02 ± 0.00 | 952.0 ± 5.1 | 0.42 ± 0.02 |

Quello che il report di confronto non guarda:

| Build | Servite/s (dispatch) | Vivo a fine cella | CPU strozzata (%) | Heap picco (MB) | Collezioni GC | Pausa GC media (ms) | Tempo in GC (%) | gauge gc_time_fraction | Compilazione (s) | Immagine (MB) |
|---|---|---|---|---|---|---|---|---|---|---|
| JVM (Java 25, JIT) | 302 ± 0 | si | 3.5 ± 3.7 | 663 ± 6 | 849 ± 3 | 5.8 ± 0.4 | 1.03 ± 0.08 | NaN | 31.7 | 244 |
| JVM (serial GC, full tiering) | 302 ± 0 | si | 0.1 ± 0.0 | 614 ± 26 | 975 ± 8 | 4.7 ± 0.2 | 0.96 ± 0.04 | NaN | 14.6 | 244 |
<!-- A2c:fine -->

### A2d — 2 core, 512 MiB

<!-- A2d:inizio -->
| Build | Runs | Throughput (rps) | p50 (ms) | p95 (ms) | p99 (ms) | Shed (%) | Control plane peak (MiB) | Control plane CPU (cores) |
|---|---|---|---|---|---|---|---|---|
| JVM (Java 25, JIT) | 3 | 421.8 ± 7.0 | 1.0 ± 0.6 | 3.5 ± 1.8 | 58.5 ± 76.3 | 50.08 ± 35.44 | 498.0 ± 20.2 | 0.41 ± 0.16 |
| JVM (serial GC, full tiering) | 3 | 416.9 ± 0.2 | 0.4 ± 0.0 | 1.9 ± 0.1 | 57.3 ± 5.5 | 79.34 ± 0.02 | 455.7 ± 12.9 | 0.25 ± 0.02 |

Quello che il report di confronto non guarda:

| Build | Servite/s (dispatch) | Vivo a fine cella | CPU strozzata (%) | Heap picco (MB) | Collezioni GC | Pausa GC media (ms) | Tempo in GC (%) | gauge gc_time_fraction | Compilazione (s) | Immagine (MB) |
|---|---|---|---|---|---|---|---|---|---|---|
| JVM (Java 25, JIT) | 235 ± 69 | si | 1.4 ± 1.2 | 304 ± 53 | 510 ± 84 | 5.5 ± 2.6 | 1.06 ± 0.20 | NaN | 15.2 | nan |
| JVM (serial GC, full tiering) | 160 ± 3 | si | 2.8 ± 0.1 | 255 ± 7 | 419 ± 2 | 3.2 ± 0.1 | 0.80 ± 0.02 | NaN | 11.9 | nan |
<!-- A2d:fine -->

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

Solo sync, 2x:

<!-- A3-sync-2x:inizio -->
| Build | Runs | Throughput (rps) | p50 (ms) | p95 (ms) | p99 (ms) | Shed (%) | Control plane peak (MiB) | Control plane CPU (cores) |
|---|---|---|---|---|---|---|---|---|
| JVM (serial GC, full tiering) | 3 | 871.2 ± 0.0 | 1.1 ± 0.0 | 4.6 ± 0.5 | 50.6 ± 10.6 | 1.61 ± 0.03 | 1705.1 ± 16.2 | 0.63 ± 0.05 |

Quello che il report di confronto non guarda:

| Build | Servite/s (dispatch) | Vivo a fine cella | CPU strozzata (%) | Heap picco (MB) | Collezioni GC | Pausa GC media (ms) | Tempo in GC (%) | gauge gc_time_fraction | quota async degli arrivi (%) | rifiuti porta sync (%) | rifiuti porta async (%) | coda sync (max) | coda async (max) | replay sync | chiavi idempotenza (max) | Compilazione (s) | Immagine (MB) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| JVM (serial GC, full tiering) | 587 ± 0 | si | 0.5 ± 0.4 | 1325 ± 39 | 741 ± 868 | 34.2 ± 21.8 | 1.78 ± 0.23 | NaN | 0.0 ± 0.0 | 1.61 ± 0.03 | — | 14 ± 7 | — | 0 ± 0 | 0 ± 0 | 40.9 | 244 |
<!-- A3-sync-2x:fine -->

Misto, 2x:

<!-- A3-misto-2x:inizio -->
| Build | Runs | Throughput (rps) | p50 (ms) | p95 (ms) | p99 (ms) | Shed (%) | Control plane peak (MiB) | Control plane CPU (cores) |
|---|---|---|---|---|---|---|---|---|
| JVM (serial GC, full tiering) | 3 | 914.5 ± 0.2 | 1.1 ± 0.0 | 5.2 ± 0.4 | 102.7 ± 15.6 | 2.00 ± 0.14 | 1720.5 ± 7.1 | 0.67 ± 0.04 |

Quello che il report di confronto non guarda:

| Build | Servite/s (dispatch) | Vivo a fine cella | CPU strozzata (%) | Heap picco (MB) | Collezioni GC | Pausa GC media (ms) | Tempo in GC (%) | gauge gc_time_fraction | quota async degli arrivi (%) | rifiuti porta sync (%) | rifiuti porta async (%) | coda sync (max) | coda async (max) | replay sync | chiavi idempotenza (max) | Compilazione (s) | Immagine (MB) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| JVM (serial GC, full tiering) | 584 ± 1 | si | 0.8 ± 0.4 | 1331 ± 54 | 756 ± 887 | 38.6 ± 24.8 | 1.99 ± 0.17 | NaN | 19.9 ± 0.0 | 2.10 ± 0.13 | 2.08 ± 0.19 | 11 ± 8 | 3 ± 2 | 19081 ± 102 | 37786 ± 18414 | 14.8 | 244 |
<!-- A3-misto-2x:fine -->

Solo sync, 3x:

<!-- A3-sync-3x:inizio -->
| Build | Runs | Throughput (rps) | p50 (ms) | p95 (ms) | p99 (ms) | Shed (%) | Control plane peak (MiB) | Control plane CPU (cores) |
|---|---|---|---|---|---|---|---|---|
| JVM (serial GC, full tiering) | 3 | 1306.3 ± 0.0 | 1.2 ± 0.0 | 58.9 ± 27.3 | 541.4 ± 79.3 | 9.62 ± 0.23 | 1707.3 ± 4.2 | 0.84 ± 0.01 |

Quello che il report di confronto non guarda:

| Build | Servite/s (dispatch) | Vivo a fine cella | CPU strozzata (%) | Heap picco (MB) | Collezioni GC | Pausa GC media (ms) | Tempo in GC (%) | gauge gc_time_fraction | quota async degli arrivi (%) | rifiuti porta sync (%) | rifiuti porta async (%) | coda sync (max) | coda async (max) | replay sync | chiavi idempotenza (max) | Compilazione (s) | Immagine (MB) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| JVM (serial GC, full tiering) | 804 ± 2 | si | 9.3 ± 0.1 | 1375 ± 31 | 231 ± 2 | 52.1 ± 5.1 | 2.48 ± 0.24 | NaN | 0.0 ± 0.0 | 9.62 ± 0.23 | — | 20 ± 0 | — | 0 ± 0 | 0 ± 0 | 32.4 | 244 |
<!-- A3-sync-3x:fine -->

Misto, 3x:

<!-- A3-misto-3x:inizio -->
| Build | Runs | Throughput (rps) | p50 (ms) | p95 (ms) | p99 (ms) | Shed (%) | Control plane peak (MiB) | Control plane CPU (cores) |
|---|---|---|---|---|---|---|---|---|
| JVM (serial GC, full tiering) | 3 | 1371.5 ± 0.0 | 1.2 ± 0.0 | 169.3 ± 95.9 | 814.5 ± 109.8 | 12.63 ± 0.40 | 1717.1 ± 5.7 | 0.89 ± 0.04 |

Quello che il report di confronto non guarda:

| Build | Servite/s (dispatch) | Vivo a fine cella | CPU strozzata (%) | Heap picco (MB) | Collezioni GC | Pausa GC media (ms) | Tempo in GC (%) | gauge gc_time_fraction | quota async degli arrivi (%) | rifiuti porta sync (%) | rifiuti porta async (%) | coda sync (max) | coda async (max) | replay sync | chiavi idempotenza (max) | Compilazione (s) | Immagine (MB) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| JVM (serial GC, full tiering) | 776 ± 4 | si | 11.3 ± 1.9 | 1366 ± 32 | 812 ± 876 | 47.6 ± 30.3 | 3.36 ± 0.40 | NaN | 19.9 ± 0.0 | 13.27 ± 0.44 | 12.87 ± 0.33 | 18 ± 0 | 8 ± 2 | 25599 ± 172 | 52578 ± 25818 | 14.1 | 244 |
<!-- A3-misto-3x:fine -->

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

### La build G1 nativa non ha nessuna visibilita' sul collector

Trovato in A1, cella `native-o3-g1/run-1`. Su quella build:

| serie | punti | valore |
|---|---|---|
| `jvm_heap_used_bytes` | 0 | assente |
| `jvm_gc_collection_count` | 0 | assente |
| `jvm_gc_collection_time` | 0 | assente |
| `jvm_gc_time_fraction` | 97 | **0,0 costante** |

Il gauge risponde e dice che il collector non ha mai girato, mentre il processo
serviva 435 rps per otto minuti con 471 MiB di working set. Il meccanismo e' in
`GcMetricsConfiguration.value()`: mappa il `-1` dell'MXBean — che significa
«questo collector non sa riportare la cifra» — a `0.0`, per impedire che un
contatore negativo si legga a valle come un reset. Su Oracle GraalVM G1 quel
guard trasforma «misura non disponibile» in «misura pari a zero».

E' la stessa trappola che il javadoc della classe descrive per il binder a
notifiche di Micrometer, ma sul percorso a polling, che di quella era la
soluzione.

Conseguenza per la campagna, da tenere presente leggendo A1: la spiegazione
ovvia dei risultati di G1 — vince perche' non paga le pause del collector
seriale — e' proprio quella che questi dati non permettono di verificare.
L'unica evidenza indiretta e' il p99.

Correzione: distinguere «non disponibile» da zero, cioe' non registrare affatto
il contatore quando l'MXBean risponde `-1`, invece di pubblicare uno zero.

### La variante «JVM» ha il JIT ottimizzante spento

Non e' un difetto del codice ma del disegno del confronto, e va detto prima di
leggere qualunque tabella della serie A.

`platform/control-plane/Dockerfile` fissa:

```dockerfile
ARG JVM_TUNING="-XX:+UseSerialGC -XX:TieredStopAtLevel=1"
```

Verificato sul processo in esecuzione durante A1b, non dedotto: l'attuatore
riporta `jvm_gc_collection_count_total{gc="Copy"}` e `{gc="MarkSweepCompact"}`,
cioe' gli MXBean del collector seriale, anche con due core e 2 GiB.
`TieredStopAtLevel=1` ferma la compilazione a C1.

Il commento del Dockerfile dice perche': quei default furono scelti per un
deployment a **un core**. A1b toglie quel vincolo e lascia in piedi
l'assunzione, quindi misura la JVM sotto un'ipotesi che il braccio stesso ha
appena rimosso.

Quanto costa, dal 2x2 archiviato (`azure-jvm-2x2-cpu1` e `-cpu2`):

| | 1 core | | | 2 core | | |
|---|---|---|---|---|---|---|
| | servite/s | shed | p95 | servite/s | shed | p95 |
| seriale + C1 (default) | 211 | 30,3% | 118 ms | 298 | 1,4% | 21,1 ms |
| G1 + C1 | 190 | 37,0% | 153 ms | 279 | 7,8% | 46,6 ms |
| seriale + C2 | 296 | 2,0% | 18 ms | 302 | 0,0% | 2,5 ms |
| G1 + C2 | 275 | 9,0% | 58 ms | 302 | 0,1% | 3,0 ms |

Il livello di tiering vale da solo il 40% del throughput a un core e un fattore
sei sul p95. Il collector no: G1 e' *peggio* di seriale finche' il C2 e' spento,
e lo pareggia solo quando e' acceso.

**Misurato su questo codice in A1c**, non piu' solo ereditato dall'archivio. A un
core, stessa immagine e stesso collector, unica differenza il livello di
compilazione:

| | `jvm` (seriale + C1) | `jvm-c2` (seriale + C2) |
|---|---|---|
| servite/s | 225 ± 12 | **300 ± 1** |
| shed | 20,83 ± 3,31% | **0,72 ± 0,29%** |
| p95 | 151,1 ± 21,5 ms | **4,3 ± 0,4 ms** |
| CPU strozzata | 22,0% | **4,6%** |
| CPU media | 0,56 core | **0,41 core** |
| memoria | 1098 MiB | 1643 MiB |

Il C2 non costa CPU, ne fa risparmiare: stesso lavoro con meno istruzioni,
quindi sotto una quota di un core la build viene strozzata un quinto. Paga 545
MiB di memoria.

**E questo ribalta il risultato di A1.** Le quattro build a un core davano 210,
235, 245 e 267 servite/s, con la G1 nativa in testa; `jvm-c2` ne fa 300 e le
batte tutte. La conclusione «a un core il nativo batte la JVM» — il risultato
dell'esperimento originale — era un artefatto di una riga del Dockerfile scelta
per un deployment a un core, non una proprieta' della compilazione AOT.

Conseguenza: «JVM (Java 25, JIT)» contro `native-o3` non e' JIT contro AOT, e'
AOT ottimizzato contro JIT dimezzato. **La serie A ha bisogno di un braccio
`jvm-c2`** perche' il confronto sia quello che il capitolo dichiara di fare. La
variante esiste gia' nel branch `dispatch-instrumentation` di NanoLab, non su
`main`; aggiungerla e' un cambio di disegno e resta da decidere.

### `jvm-c2` dimensiona l'heap in due modi, e la media fra i due non esiste

Non e' un difetto: e' una variabilita' che non costa nulla al chiamante ma
rende insensata una riga di tabella, quindi va saputa prima di citarla.

Su nove celle, `jvm-c2` cade in uno di **due modi netti**, senza valori
intermedi, mentre `jvm` con C1 non lo fa mai:

| | collezioni | pausa media | heap di picco |
|---|---|---|---|
| `jvm` (C1), tre celle | 1536–1556 | 4,2–4,4 ms | 989–1021 MB |
| `jvm-c2`, modo comune | 1863–1898 | 3,3–5,3 ms | 1005–1277 MB |
| `jvm-c2`, modo raro | 83–163 | 32,3–48,8 ms | 1298–1356 MB |

Nel modo raro le collezioni sono ~11 volte meno numerose e ~10 volte piu'
lunghe, con un tempo totale di GC simile: cambia la forma, non il lavoro. Meno
collezioni piu' lunghe con piu' heap significa una young generation piu' grande.

**Non si sente sul chiamante.** Throughput identico a tre cifre (301–302 in A1d,
593 in A3), shed identico, e il massimo di latenza *piu' basso* nel modo raro
(114 ms contro 743 in A1d; 740–935 contro 1202 in A3). L'aritmetica torna: 160
collezioni da 48 ms sono 7,7 s di pause su 480, contro 10 s di 1898 collezioni
da 5,3 ms — e ogni pausa resta un ordine di grandezza sotto il massimo osservato,
che quindi e' dominato da altro.

**Due conseguenze pratiche.** La riga «pausa GC media» di `jvm-c2` non va citata:
3,3 ms e 48,8 ms non hanno una media sensata, e riportare 34,2 ± 21,8 fa
sembrare rumore quello che e' un interruttore. E la minor occupazione di heap di
`jvm-c2` osservata in A2c non e' un effetto del C2 sulla memoria: e' la lotteria
del modo, caduta li' dalla parte piccola.

Meccanismo non stabilito. I fatti sono che accade solo con il C2, che il modo
raro ha sempre l'heap di picco piu' grande, e che in nove celle non esistono
valori intermedi.

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
