# Scheduler thread accounting Implementation Plan

**Goal:** Decidere se i 1.078 µs spesi dentro `poll()` siano coda dietro le
visite dello stesso thread oppure attesa reale, misurando il bilancio completo
del thread scheduler invece di un altro frammento.

**Architecture:** Due timer non taggati sul solo thread scheduler.
`scheduler_idle_duration` copre la `poll()` bloccante, `scheduler_visit_duration`
copre `processFunction`. Insieme partizionano il wall clock del ciclo, quindi la
loro somma su una finestra **non può superare la finestra**. Nessuna
correlazione fra thread, nessun FIFO, nessun pairing di timestamp: è l'assenza
di tutti e tre che ha reso falsificabile solo a posteriori la sonda di
reacquisizione (§14 del documento di indagine).

**Tech Stack:** Java 25, Micrometer, JUnit 5, NanoLab Azure comparison.

---

### Task 1: Misura e test — FATTO (mcFaas `8b90889e`)

- `QueueManager`: due `Timer` di istanza, senza tag `function`. Il thread è uno
  solo e la `poll()` avviene prima che si sappia per quale funzione si è
  svegliato: un selettore `function` non restituirebbe nulla.
- `Scheduler.loop`: `idleStarted` attorno alla `poll()`, `visitStarted` attorno a
  `processFunction`. Il timestamp di visita sostituisce la `System.nanoTime()`
  che già veniva passata a `processFunction`, quindi non aggiunge chiamate.
- Test `schedulerThreadTimeNeverExceedsTheElapsedWallClock`: asserisce
  `Σvisit + Σidle ≤ elapsed`. Il limite fisico è verificato in locale, non dopo
  una run su Azure.

### Task 2: Raccolta — FATTO (NanoLab `e6fa60b`)

Quattro query in `_async_queue_queries`, senza selettore di funzione. Il test di
copertura del catalogo le pretende: con la query rimossa fallisce con
`async-queue publishes metrics the catalogue never asks for`.

**Trappola verificata:** `pytest_configure` ripunta `NANOFAAS_ROOT` su un
`git archive` di HEAD, non sull'albero di lavoro. La guardia non vede modifiche
non committate — il controllo negativo va rifatto **dopo** il commit mcFaas, o
passa a vuoto.

### Task 3: Esperimento

1. `caffeinate -dimsu`, una cella `native-o3-g1`, 2/20, una ripetizione.
2. Teardown NanoLab e verifica dell'inventario Azure vuoto.
3. Copiare manifest e tre raw JSON nel registro, aggiornare `SHA256SUMS`.

```bash
export NANOFAAS_ROOT=/path/to/mcFaas/.worktrees/dispatch-instrumentation
cd /path/to/nanolab/.worktrees/dispatch-instrumentation
caffeinate -dimsu ./nanolab.sh compare \
  packages/nanolab/scenarios-v2/runtime-comparison-jvm.yaml \
  --environment packages/nanolab/environments/azure-comparison.yaml \
  --run-dir packages/nanolab/runs/azure-scheduler-thread-accounting-c2 \
  --variants native-o3-g1 --repetitions 1
```

## Criteri di decisione

Leggere la riga `peak900` di `analyze_snapshot.py`. **Prima di tutto**, la
colonna `accounted<=100`: se dice `NO`, la sonda è rotta e nessun'altra colonna
significa niente.

| lettura al `peak900` | conclusione | intervento |
|---|---|---|
| `thread busy %` alto (≳70) | il thread fa coda dietro le proprie visite; i 1.078 µs sono attesa del proprio turno | sharding per funzione, o dispatch diretto dal thread che rilascia lo slot (§4.3) |
| `thread idle %` alto (≳70) | il thread dorme davvero mentre il lavoro aspetta: è la primitiva di attesa o lo scheduling del SO | sostituire `LinkedBlockingQueue.poll` (spin-then-park), oppure priorità/affinità del thread |
| `accounted %` ≪ 100 | il tempo se ne va fuori da entrambi i tratti, cioè nel bookkeeping del ciclo | improbabile: già misurato a 5,6 µs |

`visits/dispatch` disambigua il primo caso: con ~2,3 visite per dispatch
(§15) una visita corta ma frequentissima e una lunga e rara portano allo stesso
`thread busy %` ma non allo stesso rimedio.

Le due letture chiedono interventi opposti, quindi l'esperimento decide invece
di aggiungere un altro frammento non vincolato.
