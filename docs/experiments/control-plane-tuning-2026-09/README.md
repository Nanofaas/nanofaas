# Campagna: tuning guidato dalle misure (piano §6, attività T1–T4)

Campagna della fase 4 del piano
`docs/plans/2026-09-05-control-plane-correctness-and-performance.md`.
Non sovrascrive `overload-path-2026-09`, che resta la campagna precedente.

## Cosa misura, e cosa NO

Il piano §6 chiede, per ogni attività T, **prima un profilo e un confronto
isolato**; §8 chiede in aggiunta una campagna end-to-end su infrastruttura
condivisa, con raccolta Prometheus e workflow *compare* di NanoLab.

Questa campagna copre **solo il primo**: confronti isolati, in-JVM, sulla
macchina di sviluppo. È deliberato e va dichiarato leggendo i numeri.

| | Coperto qui | Dove va fatto |
|---|---|---|
| Profilo e confronto isolato di un intervento | sì | qui |
| Matrice end-to-end, 3 ripetizioni/braccio, ordine alternato | no | NanoLab (§8) |
| Raccolta Prometheus, p99 lato client, CPU/allocazioni per successo | no | NanoLab (§8) |
| Soak che attraversa le finestre di ritenzione | no | NanoLab (§8) |

Motivo dell'esclusione: in questa sessione non esistono né il checkout
`../nanolab` né `k6`. Un numero end-to-end prodotto senza quella infrastruttura
non sarebbe confrontabile con la baseline della campagna precedente, e §8 chiede
esplicitamente «baseline e candidato nella stessa matrice e sulla stessa
infrastruttura».

## Come leggere i numeri

Ogni braccio gira `REPS` ripetizioni **alternate** (A,B,A,B,…) nello stesso
processo, dopo warm-up, e riporta mediana e IQR — non la media, che una singola
pausa GC sposta. Un intervento viene adottato solo se il miglioramento supera
la dispersione misurata della baseline; altrimenti si documenta l'esito
negativo e si tiene l'implementazione precedente (§6).

Le soglie di §8 (10% sulla metrica bersaglio, nessun peggioramento > 5%) sono
criteri sperimentali, non soglie CI: **nessuna misura di questa campagna
diventa un test wall-clock**. Le garanzie strutturali restano nei test.

## Struttura

- `bench/` — sorgenti dei banchi, uno per attività
- `raw/` — output grezzo per esecuzione, in JSON
- `run.sh` — esegue un banco contro il classpath del control-plane
- `RISULTATI.md` — esito e decisione per ciascuna T
