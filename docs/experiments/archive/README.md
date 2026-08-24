# Esperimenti archiviati

Quello che c'è qui dentro è **chiuso**. Sono le campagne diagnostiche del
2026-08: sono servite a trovare colli di bottiglia e a decidere, non a
descrivere le prestazioni della piattaforma. I numeri che contengono valgono
per il codice di allora e non vanno citati come prestazioni correnti.

La campagna aperta è in [`../baseline-2026-08/`](../baseline-2026-08/).

## `dispatch-bottleneck/`

Perché il dispatch si fermava intorno a ~300 invocazioni/s, e poi tutto
quello che è emerso tirando quel filo. Piano di riferimento:
[`../../plans/2026-08-21-dispatch-bottleneck-and-comparison-rerun.md`](../../plans/2026-08-21-dispatch-bottleneck-and-comparison-rerun.md).

Cosa ha stabilito, in ordine di quanto ha cambiato il codice:

| Domanda | Risposta | Conseguenza |
|---|---|---|
| Il tetto a ~300 disp/s è il dispatch? | No: `limits.cpu: "1"` del chart. A 4 CPU → 843/s, throttling 85% → 0% | Nessuna modifica al dispatch |
| La probe di liveness uccide un control plane solo occupato? | Sì. p95 probe 1707 ms contro un budget di 1 s, tre fallimenti → SIGTERM | Latenza della probe resa metrica strutturale |
| Perché la memoria satura? | `ExecutionStore` con ritenzione dichiarata nel tempo e illimitata nello spazio: ~270 000 record ≈ 1,05 GB contro 1002 MB di tenured → GC seriale al 50,6% del wall time, pause 2,851 s | Ritenzione guidata dal lettore (`syncTtl`) |
| Il doppio passaggio JSON sul payload costa? | 0,09% di un core a 138 B | Non toccato — vedi [`payload-passthrough.md`](payload-passthrough.md) |
| Quanto costa un rifiuto? | Vedi [`refusal-cost.md`](refusal-cost.md) | Rifiuto anticipato, prima di costruire l'esecuzione |

Rilettura dei dati grezzi:

```bash
cd docs/experiments/archive/dispatch-bottleneck
python3 build_tables.py raw            # tabelle
shasum -a 256 -c SHA256SUMS            # integrità
```

## `mixed-workload/`

Se sync e async condividono una coda, le metriche riescono a distinguerli?
Matrice 4 bracci × 3 ripetizioni (sync/misto × 2x/3x), 5,04 M richieste.

- Le due porte esistono nelle metriche: contatori `admitted`/`refused`/`replayed`
  con tag `path=sync|async`, profondità di coda per tipo.
- L'idempotenza tiene: 132 203 coppie, 0 disaccordi.
- **Il difetto rimasto**: a 3x le due porte vengono rifiutate al 15,7% e al
  15,4%. Indistinguibili — e l'equità è la politica sbagliata quando le
  scadenze sono diverse. L'ammissione differenziata (`EST_WAIT`) resta aperta.

Gli scenari e il generatore (`mixed-workload.js`) vivono sul branch
`dispatch-instrumentation` di NanoLab, non su `main`.
