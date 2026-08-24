# Dispatch Reacquisition Segmentation Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Separare il tempo rilascio→nuovo CAS fra attesa prima dell'attivazione dello scheduler e lavoro dopo l'attivazione, senza cambiare il dispatch.

**Architecture:** Il timer totale esistente conserva il timestamp di rilascio in un FIFO per funzione. Lo scheduler passa a `QueueManager` il timestamp d'inizio della visita alla funzione; al CAS riuscito viene registrato un solo nuovo timer, dal massimo fra rilascio e inizio visita fino all'acquisizione. Il tratto pre-attivazione si deriva come `total_sum - active_sum`; i conteggi sono identici perché entrambi i timer sono aggiornati nello stesso metodo.

**Tech Stack:** Java 25, Micrometer `Timer`, JUnit 5/AssertJ, Python/NanoLab Prometheus catalogue.

---

### Task 1: Sonda nel control plane

**Files:**
- Modify: `platform/modules/async-queue/src/test/java/it/unimib/datai/nanofaas/modules/asyncqueue/AsyncQueueDiagnosticsTest.java`
- Modify: `platform/modules/async-queue/src/main/java/it/unimib/datai/nanofaas/modules/asyncqueue/Scheduler.java`
- Modify: `platform/modules/async-queue/src/main/java/it/unimib/datai/nanofaas/modules/asyncqueue/QueueManager.java`
- Modify: `platform/modules/async-queue/src/test/java/it/unimib/datai/nanofaas/modules/asyncqueue/QueueManagerTest.java`
- Modify: `platform/modules/async-queue/src/test/java/it/unimib/datai/nanofaas/modules/asyncqueue/QueueManagerGaugeCleanupTest.java`

**Step 1: Write the failing test**

Estendere `schedulerPublishesReleaseToReacquisitionDelay` con:

```java
assertThat(registry.get("function_dispatch_slot_reacquisition_active_delay")
        .tag("function", "echo").timer().count()).isEqualTo(1);
```

**Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :control-plane-modules:async-queue:test \
  --tests '*AsyncQueueDiagnosticsTest.schedulerPublishesReleaseToReacquisitionDelay'
```

Expected: FAIL perché il nuovo meter non esiste.

**Step 3: Write minimal implementation**

- Registrare `function_dispatch_slot_reacquisition_active_delay` nello stesso `DiagnosticMeters` del timer totale.
- Acquisire `schedulerActivatedAt = System.nanoTime()` subito prima di `processFunction` e passarlo al metodo.
- Dopo il CAS riuscito, estrarre il timestamp di rilascio e registrare:

```java
long acquiredAt = System.nanoTime();
long activeAt = Math.max(releasedAt, schedulerActivatedAt);
total.record(acquiredAt - releasedAt, NANOSECONDS);
active.record(acquiredAt - activeAt, NANOSECONDS);
```

- Nel percorso pubblico `tryAcquireSlot`, usare il timestamp immediatamente precedente al tentativo come punto di attivazione.
- Aggiornare da 11 a 12 il numero di meter atteso nei due test di cleanup.

**Step 4: Run tests to verify GREEN**

Run:

```bash
./gradlew :control-plane-modules:async-queue:test
```

Expected: `BUILD SUCCESSFUL`.

**Step 5: Commit**

```bash
git add platform/modules/async-queue
git commit -m "Segment dispatch slot reacquisition delay"
```

### Task 2: Raccolta NanoLab

**Files:**
- Modify: `packages/nanolab/tests/metrics/test_catalogue_coverage.py`
- Modify: `packages/nanolab/src/nanolab/metrics/catalogue.py`

**Step 1: Write the failing test**

Richiedere le query `_count` e `_sum` del nuovo timer nel test di copertura.

**Step 2: Verify RED**

```bash
uv run pytest packages/nanolab/tests/metrics/test_catalogue_coverage.py -q
```

Expected: FAIL con query mancante.

**Step 3: Write minimal implementation**

Aggiungere soltanto:

```python
PrometheusQuery("function_dispatch_slot_reacquisition_active_delay_count",
                f"function_dispatch_slot_reacquisition_active_delay_seconds_count{function}"),
PrometheusQuery("function_dispatch_slot_reacquisition_active_delay_sum",
                f"function_dispatch_slot_reacquisition_active_delay_seconds_sum{function}"),
```

**Step 4: Verify GREEN and commit**

```bash
uv run pytest packages/nanolab/tests/metrics/test_catalogue_coverage.py -q
git add packages/nanolab
git commit -m "Collect reacquisition scheduler segments"
```

### Task 3: Probe, evidence and cleanup

**Files:**
- Modify: `docs/experiments/dispatch-bottleneck/analyze_snapshot.py`
- Modify: `docs/experiments/dispatch-bottleneck/README.md`
- Modify: `docs/experiments/dispatch-bottleneck/SHA256SUMS`
- Modify: `docs/plans/2026-08-21-dispatch-bottleneck-and-comparison-rerun.md`
- Create: `docs/experiments/dispatch-bottleneck/raw/azure-dispatch-reacquisition-segments-c2/**`

**Steps:**

1. Verificare IP pubblico, account Azure, inventario vuoto e assenza di `caffeinate`.
2. Eseguire una sola cella `native-o3-g1`, 2/20, una ripetizione, sotto `caffeinate -dimsu`.
3. Calcolare per fase `active ms` e `pre-active ms = reacquisition_sum/count - active_sum/count`.
4. Archiviare i quattro JSON essenziali, aggiornare risultati e checksum, eseguire lo script.
5. Eliminare esattamente le 12 risorse Azure e verificare inventario `[]`.
6. Verificare che `caffeinate` non sia più presente e che entrambi i worktree siano puliti.
