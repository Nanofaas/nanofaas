# Registro degli esperimenti sul dispatch

Questo registro conserva protocollo, risultati e dati grezzi dell'indagine del
2026-08-21. Il contesto e le ipotesi sono in
[`../../plans/2026-08-21-dispatch-bottleneck-and-comparison-rerun.md`](../../plans/2026-08-21-dispatch-bottleneck-and-comparison-rerun.md).

## Protocollo comune

- Azure `westeurope`: stack `Standard_D8s_v5` (8 vCPU, 32 GiB), load generator
  `Standard_D2s_v5`; k3s `v1.36.3+k3s1`.
- Profilo k6 open-loop `runtime-comparison.js`, 450 s, due funzioni concorrenti:
  Java al 100% del profilo e JavaScript al 35%.
- Build di riferimento: `native-o3-g1`, Oracle GraalVM, `-O3`, G1.
- Moduli control plane: `k8s-deployment-provider,async-queue`; un pod funzione;
  nessun autoscaler o governor.
- Salvo diversa indicazione, `concurrency=2`, `queueSize=20`.
- Le sonde a una ripetizione falsificano ipotesi meccanicistiche ma non sono
  confronti statistici fra build.

## Esperimenti conservati

| directory raw | configurazione | codice rilevante | risultato |
|---|---|---|---|
| `azure-nosync` | 4 build × 3 ripetizioni, 2/20 | mcFaas `9387c60d`; NanoLab `6012bf3` | G1 è la build migliore: 435,1 rps, p95 92,2 ms, p99 147,7 ms, 17,52% scarti, 553,6 MiB RSS. A parità di `-O3`, il serial GC porta il p95 a 695,6 ms. |
| `azure-conc8-probe` | G1, una ripetizione, 8 slot | NanoLab `ea01127` | 119.892 dispatch contro 115.630 medi a 2 slot (+3,69%, non 4×); p95 121,6 ms e scarti 14,64%. L'ipotesi che un park ogni due dispatch imponesse il tetto è falsa. |
| `azure-dispatch-instrumentation-c2` | G1, una ripetizione, 2/20 | mcFaas `8af8c314`; NanoLab `553b7a5` | `offer` 365 ns, `poll` 238 ns, wake-up 222 µs medi; p95 93,7 ms, 114.797 dispatch, 18,02% scarti. Lock e park/unpark non spiegano da soli attese di decine di ms. |
| `azure-dispatch-slot-hold-c2` | G1, una ripetizione, 2/20 | mcFaas `df5efda1`; NanoLab `07bbbf7` | slot 1,416 ms e `function_latency` 1,491 ms sull'intera run; p95 92,5 ms, 115.859 dispatch, 17,43% scarti. Nessun callback lag millisecond-level nascosto. |
| `azure-dispatch-reacquisition-c2` | G1, una ripetizione, 2/20 | mcFaas `50e7d87a`; NanoLab `2b7f01c` | Al `peak900`, rilascio→successiva acquisizione 3,161 ms sul 97% dei dispatch, slot 4,204 ms, 316,7 dispatch/s. Il gap dopo il rilascio è reale e millisecond-level. |

La compilazione G1 dell'ultima sonda ha richiesto 891,5 s; il push 5,1 s. IP
dell'operatore verificato prima del run: `79.53.75.238`; l'ambiente usava
`operator_source_cidr: auto`.

## Sonda slot-hold per fase

Il timer `function_dispatch_slot_hold_duration` parte dopo l'acquisizione CAS
dello slot e termina nel rilascio comune di `QueueManager`. Il FIFO interno non
attribuisce una durata a una specifica invocazione quando due completamenti si
invertono, ma preserva esattamente somma, conteggio e quindi media. Non usare
questi raw per percentili per-request.

| fase | dispatch/s | slot ms | latency ms | slot util. | idle/ciclo ms | queue wait ms | coda media | wake µs |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| hold200 | 199,3 | 0,596 | 0,702 | 5,9% | 9,440 | 0,047 | 0,00 | 27,7 |
| spike600 | 522,4 | 1,079 | 1,148 | 28,2% | 2,750 | 3,134 | 1,17 | 189,1 |
| recover200 | 210,6 | 0,602 | 0,695 | 6,3% | 8,897 | 0,077 | 0,00 | 35,1 |
| hold350 | 345,1 | 0,915 | 0,986 | 15,8% | 4,880 | 1,584 | 0,11 | 105,8 |
| **peak900** | **347,6** | **4,047** | **4,083** | **70,3%** | **1,707** | **32,125** | **17,17** | **484,6** |
| drain40 | 331,5 | 3,023 | 3,073 | 50,1% | 3,010 | 17,890 | 7,78 | 404,5 |

Al picco la durata slot coincide con la latenza dispatch→completamento entro
35,7 µs medi (la seconda è quantizzata al millisecondo e termina appena dopo il
rilascio). L'ipotesi «la callback trattiene lo slot oltre il tempo già misurato»
è quindi falsificata. Con due slot, 4,047 ms consentirebbero circa 494 dispatch/s;
ne arrivano 347,6. Mentre la coda resta quasi piena, ogni slot passa in media
1,707 ms fuori dal timer. La prossima misura utile è direttamente
rilascio→successiva acquisizione; non serve altra strumentazione del callback.

## Sonda rilascio→reacquisizione per fase

Il timer `function_dispatch_slot_reacquisition_delay` registra il tempo fra un
rilascio che vede ancora backlog e la successiva acquisizione riuscita. Un FIFO
per funzione conserva somma e media aggregate; non attribuisce la misura a uno
slot o a una richiesta specifici e non va usato per percentili per-request.

| fase | dispatch/s | slot ms | idle stimato ms | reacquisizione ms | copertura | queue wait ms | coda media | wake µs |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| hold200 | 199,7 | 0,711 | 9,305 | 0,081 | 1% | 0,055 | 0,00 | 30,9 |
| spike600 | 519,4 | 1,212 | 2,639 | 1,279 | 29% | 5,427 | 7,00 | 229,4 |
| recover200 | 204,7 | 0,674 | 9,098 | 0,199 | 2% | 0,069 | 0,00 | 35,6 |
| hold350 | 349,3 | 1,032 | 4,693 | 0,534 | 20% | 2,008 | 1,33 | 153,1 |
| **peak900** | **316,7** | **4,204** | **2,111** | **3,161** | **97%** | **41,621** | **17,33** | **615,5** |
| drain40 | 311,8 | 3,121 | 3,294 | 2,425 | 71% | 20,830 | 12,22 | 506,2 |

Il timer diretto conferma l'ipotesi: quando il backlog è stabile, il percorso
fra rilascio e nuovo CAS costa millisecondi. Al `peak900` copre il 97% dei
dispatch ed è 5,1 volte il timer segnale→scheduler medio. I due timer osservano
popolazioni diverse (`wake/dispatch=2,37` al picco), quindi non si possono
sottrarre; il dato localizza il problema nel percorso di scheduling successivo
al rilascio, ma non ancora in una singola istruzione. `idle stimato` deriva da
`2/rate - slot` su finestre non perfettamente stazionarie e non deve coincidere
numericamente col timer diretto.

Sull'intera run: 114.527 dispatch Java, 31.332 intervalli misurati, media
reacquisizione 2,038 ms; 435,06 richieste/s complessive, p95 93,21 ms, p99
158,03 ms, 18,36% scarti e 30.522 rifiuti della coda Java.

## Raw e riproduzione

`raw/` contiene, per ogni cella, `comparison-manifest.json`, `k6-summary.json`,
`summary.json` e lo snapshot Prometheus completo. I report HTML non sono
versionati: contengono gli stessi JSON embedded e sono rigenerabili da NanoLab.
`SHA256SUMS` verifica che i raw non siano cambiati.

Ricalcolo della tabella completa per fase, solo con la standard library Python:

```bash
python3 docs/experiments/dispatch-bottleneck/analyze_snapshot.py \
  docs/experiments/dispatch-bottleneck/raw/azure-dispatch-reacquisition-c2/\
native-o3-g1/run-1/metrics/prometheus-snapshot.json
```

```bash
cd docs/experiments/dispatch-bottleneck
shasum -a 256 -c SHA256SUMS
```

Riesecuzione dell'ultima sonda con i due commit indicati sopra:

```bash
export NANOFAAS_ROOT=/path/to/mcFaas
cd /path/to/nanolab
caffeinate -dimsu ./nanolab.sh compare \
  packages/nanolab/scenarios-v2/runtime-comparison-jvm.yaml \
  --environment packages/nanolab/environments/azure-comparison.yaml \
  --run-dir packages/nanolab/runs/azure-dispatch-reacquisition-c2 \
  --variants native-o3-g1 --repetitions 1
```
