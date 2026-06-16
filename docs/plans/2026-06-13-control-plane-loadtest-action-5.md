# Control Plane Load Test Action 5 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build and run a focused benchmark suite that quantifies control-plane behavior under many functions, high backlog, saturated concurrency, and internal autoscaling load.

**Architecture:** Keep this separate from code hardening so performance data can validate the fixes from actions 1-4 without mixing benchmark work into runtime changes. Prefer existing scripts and load-test docs before adding new tools. Capture repeatable scenarios, raw artifacts, and a short interpretation document.

**Tech Stack:** Existing `scripts/controlplane.sh` E2E/loadtest workflows, Gradle, k3s/Multipass when needed, Prometheus/Micrometer metrics, k6 or existing project load generator if already wired.

---

## Preflight

**Files:**
- Read: `docs/loadtest-payload-profile.md`
- Read: `docs/slo.md`
- Read: `docs/e2e-tutorial.md`
- Read: `scripts/controlplane.sh`
- Read: `scripts/e2e-loadtest-registry.sh`
- Read: `control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/perf/InvocationHotPathPerfTest.java`
- Read: `control-plane-modules/async-queue/src/test/java/it/unimib/datai/nanofaas/modules/asyncqueue/AsyncSchedulerFairnessPerfTest.java`
- Read: `control-plane-modules/sync-queue/src/test/java/it/unimib/datai/nanofaas/controlplane/sync/SyncQueueThroughputPerfTest.java`

**Step 1: Confirm available workflows**

Run:

```bash
./scripts/controlplane.sh e2e list
```

Expected:
- Identify existing local, k3s, Helm, and loadtest scenarios.

**Step 2: Confirm baseline unit/perf tests**

Run:

```bash
./gradlew :control-plane:test --tests '*InvocationHotPathPerfTest'
./gradlew :control-plane-modules:async-queue:test --tests '*AsyncSchedulerFairnessPerfTest'
./gradlew :control-plane-modules:sync-queue:test --tests '*SyncQueueThroughputPerfTest'
```

Expected:
- PASS. Record rough runtime and any perf assertions already encoded.

---

### Task 1: Define Benchmark Matrix

**Files:**
- Create: `docs/perf/control-plane-loadtest-action-5.md`

**Step 1: Create benchmark document**

Document these scenarios:

| Scenario | Purpose | Suggested shape |
|---|---|---|
| many-functions-idle | Metric/state overhead with many registered functions | 100, 500, 1000 functions, no traffic |
| async-backlog-saturated | Validate async scheduler does not spin under saturated concurrency | 1 hot function, concurrency 1, queue 1000 |
| sync-backlog-saturated | Measure sync queue scan/backoff behavior | 100 functions, 10 hot, max-depth 1000 |
| deployment-autoscaler-many | Measure scaler API pressure | 50, 100, 250 deployment functions |
| mixed-hot-cold | Check tail latency and cold starts | 80 percent one hot function, 20 percent sparse functions |

**Step 2: Define metrics to capture**

Include:
- throughput: requests/s
- p50/p95/p99 latency
- timeout/rejection rate
- queue depth and in-flight per function
- scheduler thread CPU
- process RSS/heap after warmup and after test
- Kubernetes API call count if available
- Prometheus scrape cardinality for function meters

**Step 3: Commit doc-only benchmark spec**

```bash
git add docs/perf/control-plane-loadtest-action-5.md
git commit -m "Document control-plane load test matrix"
```

---

### Task 2: Add Or Reuse Scenario Configuration

**Files:**
- Prefer modifying existing loadtest scenario manifests if present.
- Possible create: `experiments/loadtest/control-plane-action-5/`
- Possible create: `docs/perf/results/.gitkeep`

**Step 1: Search for existing loadtest scenario format**

Run:

```bash
rg -n "loadtest|k6|vus|duration|payload|scenario" scripts experiments docs control-plane control-plane-modules
```

Expected:
- Identify whether the project already has a scenario manifest or generator.

**Step 2: Add minimal scenario configs**

Create or update scenario definitions for:
- async backlog saturated
- sync backlog saturated
- many deployments for scaler

Keep payload small at first, for example 1 KB JSON, then use `docs/loadtest-payload-profile.md` for larger payload runs.

**Step 3: Add a dry-run command**

If the existing scripts support it, add a dry-run/list command that prints:
- selected functions
- requested concurrency
- duration
- expected target endpoint
- output artifact directory

**Step 4: Run dry-run/list**

Run the relevant command discovered in Step 1.

Expected:
- No cluster required.
- Scenario config validates.

---

### Task 3: Add Metric Capture Script

**Files:**
- Create: `scripts/control-plane-perf-snapshot.sh`
- Modify: docs from Task 1 to reference the script

**Step 1: Write snapshot script**

The script should accept:

```bash
scripts/control-plane-perf-snapshot.sh <base-url> <output-dir>
```

Capture:
- `/actuator/prometheus` from management port if reachable
- process info using `ps`
- optional `jcmd` heap summary if Java PID is local
- timestamp and git commit

Use existing shell style from `scripts/`.

**Step 2: Run shell syntax check**

Run:

```bash
bash -n scripts/control-plane-perf-snapshot.sh
```

Expected:
- No syntax errors.

**Step 3: Run against local/unavailable endpoint**

Run:

```bash
scripts/control-plane-perf-snapshot.sh http://127.0.0.1:8081 /tmp/nanofaas-perf-snapshot-test
```

Expected:
- If control plane is not running, script exits clearly and leaves diagnostic metadata.

**Step 4: Commit**

```bash
git add scripts/control-plane-perf-snapshot.sh docs/perf/control-plane-loadtest-action-5.md
git commit -m "Add control-plane performance snapshot helper"
```

---

### Task 4: Run Local JVM Benchmarks

**Files:**
- Create: `docs/perf/results/YYYY-MM-DD-local-action-5-summary.md`
- Store raw artifacts under: `docs/perf/results/YYYY-MM-DD-local-action-5/`

**Step 1: Start local control plane**

Run:

```bash
./gradlew :control-plane:bootRun
```

Expected:
- Control plane starts on `8080`, management on `8081`.

**Step 2: Run local scenarios**

Run the scenario command discovered or added in Task 2. If no script exists yet, use a minimal HTTP load driver already present in the repo. Avoid introducing a new dependency unless necessary.

Expected:
- Produce raw latency/throughput output.
- Snapshot before, during, and after each scenario.

**Step 3: Summarize local results**

Write:
- test machine details
- command lines
- scenario matrix
- key metrics
- observed bottlenecks
- comparison against expectations from actions 1-4

**Step 4: Commit results if they are small**

```bash
git add docs/perf/results/YYYY-MM-DD-local-action-5-summary.md
git commit -m "Record local control-plane load test results"
```

If raw artifacts are large, do not commit them. Document their local path instead.

---

### Task 5: Run k3s/Helm Benchmark

**Files:**
- Create: `docs/perf/results/YYYY-MM-DD-k3s-action-5-summary.md`

**Step 1: Provision k3s scenario**

Run one of the existing workflows:

```bash
./scripts/controlplane.sh e2e run k3s-junit-curl
```

or the appropriate loadtest workflow identified in preflight.

Expected:
- Cluster is up.
- Control plane and functions are reachable.

**Step 2: Run deployment-autoscaler-many**

Use internal autoscaler deployment mode. Capture:
- scaler loop duration if exposed
- Kubernetes API pressure, if available from metrics/logs
- replica convergence time
- request latency during scale-up

**Step 3: Run mixed-hot-cold**

Capture:
- cold start count
- p95/p99 latency
- timeout/rejection rate
- queue wait

**Step 4: Summarize k3s results**

Write:
- cluster size
- function count
- duration
- load parameters
- observed bottlenecks
- pass/fail against SLOs

**Step 5: Commit summary**

```bash
git add docs/perf/results/YYYY-MM-DD-k3s-action-5-summary.md
git commit -m "Record k3s control-plane load test results"
```

---

## Final Verification

**Step 1: Re-run unit/perf tests**

```bash
./gradlew :control-plane:test --tests '*InvocationHotPathPerfTest'
./gradlew :control-plane-modules:async-queue:test --tests '*AsyncSchedulerFairnessPerfTest'
./gradlew :control-plane-modules:sync-queue:test --tests '*SyncQueueThroughputPerfTest'
```

Expected:
- PASS.

**Step 2: Compare before/after actions 1-4**

In the final summary, include:
- baseline before hardening if available
- after hardening numbers
- percentage change in CPU, memory, p95/p99 latency, rejection rate
- remaining bottlenecks ranked by severity

**Step 3: Decide next optimization**

Use data to decide whether to:
- keep current single-thread schedulers
- implement per-function ready queues
- cache Kubernetes ready replica reads
- cap or sample per-function metrics
- introduce an external queue/persistence layer
