# Campagna overload-path — settembre 2026

Misura le due domande di misura del piano
[`../../plans/2026-09-04-overload-path-fixes.md`](../../plans/2026-09-04-overload-path-fixes.md):
quanta RAM serve adesso (Esperimento A) e se il JIT vale ancora e con quanti
event loop (Esperimento B). Non è un'indagine: le indagini che hanno prodotto
i numeri qui citati come riferimento stanno in
[`../archive/`](../archive/README.md) e in
[`../baseline-2026-08/`](../baseline-2026-08/README.md). Questa campagna
produce i numeri che il piano userà per decidere.

- **Base**: `main` a `e9cbe50d` (2026-09-04) — dopo lo split
  `inFlight`/`outcomes` dell'`ExecutionStore` (2026-08-26), che è la ragione
  per cui questa campagna esiste: nessun numero di agosto, incluso quello in
  `../baseline-2026-08/` (corso il 2026-08-25, un giorno prima del fix),
  misura il codice attuale.
- **Infrastruttura riusata, non reinventata**: gli scenari NanoLab
  (`runtime-comparison-mem512.yaml`, `runtime-comparison-mem1024.yaml`,
  `runtime-comparison-cpu1.yaml`), le varianti di build
  (`packages/nanolab/src/nanolab/images/control_plane_variants.py`), e la
  raccolta di RSS/CFS via cAdvisor
  (`packages/nanolab/src/nanolab/metrics/catalogue.py::container_queries`)
  esistono già in `../nanolab` e non sono toccati da questa campagna, tranne
  dove l'Esperimento B lo richiede esplicitamente (vedi sotto).
- **Dati grezzi, report HTML, snapshot Prometheus**: [`raw/`](raw/), un
  sotto-albero per cella, nella forma che `tables.py` si aspetta (copiato da
  `../baseline-2026-08/tables.py`, invariato: legge `comparison-manifest.json`
  e gli snapshot Prometheus di ogni cella, non sa nulla di questa campagna in
  particolare).
- **Regola**: nessun numero in questo documento senza uno script in questa
  directory che lo calcoli dai dati grezzi in `raw/`.
- **Stato**: nessuna cella eseguita. `run-queue.sh` non è stato lanciato.

## Prima di lanciare `run-queue.sh`

`run-queue.sh` provisiona VM Azure reali, a pagamento, ed esegue codice preso
da `main` sotto carico per ore. Non è stato eseguito da questa sessione e va
lanciato deliberatamente, non come conseguenza automatica di questo documento:

1. `az login` fatto, con accesso al resource group in
   `packages/nanolab/environments/azure-comparison.yaml` (creare quel file da
   `azure-comparison.yaml.example` se non esiste ancora — non è nel
   controllo versione).
2. `GROUP` in `run-queue.sh` allineato al `resource_group` di
   quell'ambiente — il default nello script (`maurinoRicerca-rg`) è quello
   usato dalla campagna precedente su questa stessa infrastruttura NanoLab,
   non un valore universale.
3. Per l'Esperimento B, il codice delle due varianti a un solo event loop
   deve esistere prima (vedi sotto) — altrimenti `nanolab compare` rifiuta la
   riga con "unknown control-plane variants".

## Esperimento A — Quanta RAM serve adesso

**Domanda:** `limits.memory: 2Gi` nel chart è ancora giustificato?

`runtime-comparison-mem1024.yaml` e `runtime-comparison-mem512.yaml`
(entrambi già in `../nanolab/packages/nanolab/scenarios-v2/`) fissano la CPU a
2 core per isolare l'asse memoria dal ginocchio CPU — non è la configurazione
che il chart spedisce oggi (1 core, vedi `deploy/helm/nanofaas/values.yaml`).
Le celle sotto misurano quindi "quanta RAM serve quando la CPU non è il
collo di bottiglia"; se serve anche il numero alla configurazione spedita,
va aggiunta una terza cella a `cpu: 1`.

Un solo braccio, `jvm-c2` — non un confronto fra build, una misura assoluta
sul codice di oggi. `jvm-c2` perché è il candidato più probabile secondo
l'Esperimento B, non perché l'Esperimento A ne dipenda.

### 1 GiB

<!-- A-mem1024:inizio -->
| Build | Runs | Throughput (rps) | p50 (ms) | p95 (ms) | p99 (ms) | Shed (%) | Control plane peak (MiB) | Control plane CPU (cores) |
|---|---|---|---|---|---|---|---|---|
| JVM (serial GC, full tiering) | 3 | 435.1 ± 0.0 | 1.1 ± 0.0 | 1.8 ± 0.2 | 4.1 ± 0.2 | 0.00 ± 0.01 | 871.3 ± 123.6 | 0.37 ± 0.06 |

Quello che il report di confronto non guarda:

| Build | Servite/s (dispatch) | Vivo a fine cella | CPU strozzata (%) | Heap picco (MB) | Collezioni GC | Pausa GC media (ms) | Tempo in GC (%) | gauge gc_time_fraction | quota async degli arrivi (%) | rifiuti porta sync (%) | rifiuti porta async (%) | coda sync (max) | coda async (max) | replay sync | chiavi idempotenza (max) | Compilazione (s) | Immagine (MB) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| JVM (serial GC, full tiering) | 302 ± 0 | si | 0.0 ± 0.1 | 434 ± 110 | 838 ± 951 | 5.4 ± 2.5 | 0.56 ± 0.41 | ok | 0.0 ± 0.0 | 0.00 ± 0.01 | — | 4 ± 5 | — | 0 ± 0 | 0 ± 0 | 43.7 | 244 |
<!-- A-mem1024:fine -->

### 512 MiB

<!-- A-mem512:inizio -->
| Build | Runs | Throughput (rps) | p50 (ms) | p95 (ms) | p99 (ms) | Shed (%) | Control plane peak (MiB) | Control plane CPU (cores) |
|---|---|---|---|---|---|---|---|---|
| JVM (serial GC, full tiering) | 3 | 411.8 ± 11.0 | 1.2 ± 0.1 | 4.6 ± 0.8 | 112.9 ± 16.5 | 2.27 ± 0.60 | 495.5 ± 5.9 | 0.53 ± 0.03 |

Quello che il report di confronto non guarda:

| Build | Servite/s (dispatch) | Vivo a fine cella | CPU strozzata (%) | Heap picco (MB) | Collezioni GC | Pausa GC media (ms) | Tempo in GC (%) | gauge gc_time_fraction | quota async degli arrivi (%) | rifiuti porta sync (%) | rifiuti porta async (%) | coda sync (max) | coda async (max) | replay sync | chiavi idempotenza (max) | Compilazione (s) | Immagine (MB) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| JVM (serial GC, full tiering) | 143 ± 3 | NO: 3/3 | 5.5 ± 0.7 | 262 ± 6 | 998 ± 14 | 3.2 ± 0.3 | 0.66 ± 0.04 | ok | 0.0 ± 0.0 | 3.32 ± 0.35 | — | 40 ± 0 | — | 0 ± 0 | 0 ± 0 | 45.1 | 244 |
<!-- A-mem512:fine -->

## Esperimento B — Il JIT vale ancora, e quanti event loop

**Domanda 1:** dopo il fix dell'ExecutionStore, C2 batte ancora C1?
**Domanda 2:** a 1 core, un event loop batte quattro?

**Varianti aggiunte, 2026-09-04** — non più bloccato sul codice, bloccato solo
sul branch. Le due varianti a un event loop esistono in
`control_plane_variants.py` sul branch NanoLab `feature/loop-count-variants`
(non ancora su `main` di NanoLab). Quattro celle:

| variante | `JVM_TUNING` |
|---|---|
| `jvm` (esiste su main) | *(default)* — C1, 4 loop |
| `jvm-c2` (esiste su main) | `-XX:+UseSerialGC` |
| `jvm-loop1` (su `feature/loop-count-variants`) | `-XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Dreactor.netty.ioWorkerCount=1` |
| `jvm-c2-loop1` (su `feature/loop-count-variants`) | `-XX:+UseSerialGC -Dreactor.netty.ioWorkerCount=1` |

La riga `B-loop-cpu1` in `queue.tsv` è scommentata. Prima di lanciarla, il
checkout NanoLab che `$NANOLAB` in `run-queue.sh` punta deve avere
`feature/loop-count-variants` estratto (o quel branch unito a `main`) — su
`main` `resolve_variants()` rifiuta ancora `jvm-loop1`/`jvm-c2-loop1` come
sconosciute.

**Correzione, 2026-09-04: la metrica non mancava.** Verificato avviando
davvero il control plane e leggendo `/actuator/prometheus`:
`reactor_netty_eventloop_pending_tasks{name="reactor-http-nio-N"}` compare
già, una serie per event loop, senza scrivere una riga di codice — è un
gauge integrato in reactor-netty stesso
(`reactor.netty.transport.EventLoopMeters`/`MicrometerEventLoopMeterRegistrar`
nel jar `reactor-netty-core`), che si attiva da solo perché
`NettyServerMetricsConfig` accende già le metriche del server
(`server.metrics(true, ...)`). Il precedente `grep -r
"reactor.netty.eventloop.pending" platform/` cercava una stringa nel
sorgente nanofaas, ma il gauge vive nel bytecode della libreria, non lì —
quel grep non poteva vederlo. NanoLab lo interroga già, dall'agosto scorso:
`netty_eventloop_pending` (sommato) e `netty_eventloop_pending_per_loop`
(per thread) in `packages/nanolab/src/nanolab/metrics/catalogue.py:338-344`
— è il numero che nel 2026-08-23 aveva già rivelato gli 863 task in coda.
L'unico prerequisito reale per questo esperimento sono le due varianti
NanoLab sopra.

<!-- B-loop-cpu1:inizio -->
| Build | Runs | Throughput (rps) | p50 (ms) | p95 (ms) | p99 (ms) | Shed (%) | Control plane peak (MiB) | Control plane CPU (cores) |
|---|---|---|---|---|---|---|---|---|
| JVM (Java 25, JIT) | 3 | 435.1 ± 0.0 | 2.4 ± 0.1 | 110.5 ± 3.2 | 176.5 ± 2.4 | 26.63 ± 0.27 | 620.2 ± 1.7 | 0.56 ± 0.01 |
| JVM (serial GC, full tiering) | 3 | 435.1 ± 0.0 | 1.1 ± 0.1 | 8.4 ± 9.1 | 19.7 ± 13.8 | 0.46 ± 0.47 | 716.4 ± 1.2 | 0.41 ± 0.01 |
| JVM (serial GC, C1 only, 1 event loop) | 3 | 435.1 ± 0.0 | 2.1 ± 0.1 | 57.6 ± 0.6 | 72.7 ± 0.8 | 11.15 ± 0.13 | 626.1 ± 1.3 | 0.50 ± 0.01 |
| JVM (serial GC, full tiering, 1 event loop) | 3 | 435.1 ± 0.0 | 1.1 ± 0.0 | 2.5 ± 0.1 | 5.4 ± 0.4 | 0.05 ± 0.02 | 711.2 ± 2.7 | 0.37 ± 0.01 |

Quello che il report di confronto non guarda:

| Build | Servite/s (dispatch) | Vivo a fine cella | CPU strozzata (%) | Heap picco (MB) | Collezioni GC | Pausa GC media (ms) | Tempo in GC (%) | gauge gc_time_fraction | quota async degli arrivi (%) | rifiuti porta sync (%) | rifiuti porta async (%) | coda sync (max) | coda async (max) | replay sync | chiavi idempotenza (max) | Compilazione (s) | Immagine (MB) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| JVM (Java 25, JIT) | 207 ± 1 | si | 22.6 ± 0.2 | 471 ± 4 | 1378 ± 6 | 4.2 ± 0.3 | 1.22 ± 0.07 | ok | 0.0 ± 0.0 | 26.63 ± 0.27 | — | 40 ± 0 | — | 0 ± 0 | 0 ± 0 | 48.2 | 244 |
| JVM (serial GC, full tiering) | 300 ± 2 | si | 5.5 ± 3.5 | 492 ± 3 | 2042 ± 20 | 2.6 ± 0.2 | 1.12 ± 0.06 | ok | 0.0 ± 0.0 | 0.46 ± 0.47 | — | 14 ± 10 | — | 0 ± 0 | 0 ± 0 | 48.7 | 244 |
| JVM (serial GC, C1 only, 1 event loop) | 257 ± 1 | si | 17.1 ± 1.4 | 480 ± 4 | 1586 ± 14 | 3.1 ± 0.0 | 1.02 ± 0.02 | ok | 0.0 ± 0.0 | 11.15 ± 0.13 | — | 26 ± 6 | — | 0 ± 0 | 0 ± 0 | 20.3 | 244 |
| JVM (serial GC, full tiering, 1 event loop) | 302 ± 0 | si | 2.5 ± 0.2 | 490 ± 1 | 2025 ± 8 | 2.5 ± 0.0 | 1.05 ± 0.00 | ok | 0.0 ± 0.0 | 0.05 ± 0.02 | — | 1 ± 1 | — | 0 ± 0 | 0 ± 0 | 18.1 | 244 |
<!-- B-loop-cpu1:fine -->

## Esperimento C, passo 0 — non entra in questa campagna

Il banco locale che decide se vale la pena di una corsa Azure (vedi
"Esperimento C" nel piano) non usa NanoLab: è un microbenchmark Java dentro
`platform/control-plane/src/test`, sul modello di
`../archive/refusal-cost.md`. Non ha una riga qui perché non produce una
`comparison-manifest.json` da far leggere a `tables.py` — il suo risultato va
riportato in `../archive/refusal-cost.md` come continuazione di quella nota,
o in un nuovo file gemello, non in `raw/` di questa campagna.

## Riprodurre

```bash
cd docs/experiments/overload-path-2026-09
./run-queue.sh                          # legge queue.tsv, provisiona, misura, archivia, committa
# oppure una cella sola, a mano, dopo aver lanciato nanolab compare:
uv run --project ../../../../nanolab/packages/sonata-tasks \
    python3 tables.py raw/A-mem1024 --doc README.md --marker A-mem1024
shasum -a 256 -c SHA256SUMS             # integrità, dopo che raw/ ha contenuto
```
