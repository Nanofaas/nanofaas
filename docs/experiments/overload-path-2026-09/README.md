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
_Nessuna cella con dati._
<!-- A-mem1024:fine -->

### 512 MiB

<!-- A-mem512:inizio -->
_Nessuna cella con dati._
<!-- A-mem512:fine -->

## Esperimento B — Il JIT vale ancora, e quanti event loop

**Domanda 1:** dopo il fix dell'ExecutionStore, C2 batte ancora C1?
**Domanda 2:** a 1 core, un event loop batte quattro?

**Bloccato.** Le due varianti a un event loop (`jvm-loop1`, `jvm-c2-loop1`)
non esistono ancora in `control_plane_variants.py`. Il meccanismo per
aggiungerle è già lì: `ControlPlaneVariant.build_env` porta `JVM_TUNING`, che
finisce tal quale in `/jvm.options` e da lì nell'`ENTRYPOINT` come argomenti
JVM diretti (`platform/control-plane/Dockerfile:26-27,49`) — non solo
`-XX:...`, qualunque flag, incluso `-Dreactor.netty.ioWorkerCount=N`. Quattro
celle:

| variante (da aggiungere) | `JVM_TUNING` |
|---|---|
| `jvm` (esiste) | *(default)* — C1, 4 loop |
| `jvm-c2` (esiste) | `-XX:+UseSerialGC` |
| `jvm-loop1` (da aggiungere) | `-XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Dreactor.netty.ioWorkerCount=1` |
| `jvm-c2-loop1` (da aggiungere) | `-XX:+UseSerialGC -Dreactor.netty.ioWorkerCount=1` |

Una volta aggiunte in NanoLab, decommentare la riga `B-loop-cpu1` in
`queue.tsv` e rilanciare la coda.

**Metrica ancora mancante**: task pendenti per event loop. Non esiste né nel
codice sorgente di nanofaas (`grep -r "reactor.netty.eventloop.pending"
platform/` non trova nulla) né nel catalogo di NanoLab — è la sola cosa che
questa campagna non può riusare da nessuna parte. Il design esiste già,
discusso e mai spedito, in
`../../plans/2026-08-21-dispatch-bottleneck-and-comparison-rerun.md:2267-2269`
(`Gauge.builder(..., singleThreadEventExecutor::pendingTasks)`). Senza,
l'Esperimento B vede solo il sintomo (CFS, latenza), non la coda dei loop che
lo spiega.

<!-- B-loop-cpu1:inizio -->
_Bloccato: varianti a un event loop non ancora aggiunte a NanoLab. Nessuna
cella eseguita._
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
