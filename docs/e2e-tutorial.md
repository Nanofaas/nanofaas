# nanofaas E2E Validation Tutorial

This tutorial walks through deploying nanofaas on a local Kubernetes cluster,
running load tests against all demo functions, and visualizing metrics with
Grafana. The entire process is automated with two scripts.

## Prerequisites

| Tool | Purpose | Install |
|------|---------|---------|
| **multipass** | Lightweight VM manager | `brew install multipass` or [multipass.run](https://multipass.run) |
| **Docker** | Build images + run Grafana | [docker.com](https://docs.docker.com/get-docker/) |
| **k6** | Load testing | `brew install k6` or [k6 docs](https://grafana.com/docs/k6/latest/set-up/install-k6/) |

> All other dependencies (k3s, Helm, JDK 21) are installed automatically
> inside the VM.

## Quick Start (2 commands)

```bash
# 1. Deploy nanofaas (creates VM, builds, deploys, verifies — ~15 min first run)
./scripts/e2e-k3s-helm.sh

# 2. Run load tests + Grafana dashboard (~12 min)
./scripts/e2e-loadtest.sh
```

That's it. Open http://localhost:3000 (admin/admin) to see the Grafana dashboard
while the tests run.

---

## Step-by-Step Guide

### Step 1: Deploy nanofaas

```bash
./scripts/e2e-k3s-helm.sh
```

This script performs the following automatically:

1. **Creates a Multipass VM** (`nanofaas-e2e`, 4 CPU, 8 GB RAM, 30 GB disk)
2. **Installs dependencies** inside the VM: Docker, JDK 21, Helm
3. **Installs k3s** (lightweight Kubernetes, Traefik disabled)
4. **Syncs the project** to the VM and **builds all artifacts**:
   - Gradle JARs: control-plane, function-runtime, Java demo functions
   - Docker images: 2 core + 2 Java + 2 Python + 2 Bash = 8 images
5. **Sets up a local registry** in the VM (`localhost:5000`) and **pushes images**
6. **Deploys via Helm** with NodePort services:
   - Control-plane API on port **30080**
   - Actuator/metrics on port **30081**
   - Prometheus on port **30090**
7. **Registers 6 demo functions** (via Helm post-install hook):
   - `word-stats-java`, `json-transform-java` (Java/Spring Boot)
   - `word-stats-python`, `json-transform-python` (Python/FastAPI)
   - `word-stats-exec`, `json-transform-exec` (Bash/Watchdog STDIO)
8. **Smoke-tests every function** with a real invocation

On completion, you'll see:

```
[e2e] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
[e2e]          NANOFAAS E2E SETUP COMPLETE
[e2e] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
[e2e]
[e2e] VM: nanofaas-e2e (192.168.x.x)
[e2e]
[e2e] Endpoints:
[e2e]   API:        http://192.168.x.x:30080/v1/functions
[e2e]   Metrics:    http://192.168.x.x:30081/actuator/prometheus
[e2e]   Prometheus: http://192.168.x.x:30090
[e2e]
[e2e] Next step — run the load test:
[e2e]   ./scripts/e2e-loadtest.sh
```

#### Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `VM_NAME` | `nanofaas-e2e` | Multipass VM name |
| `CPUS` | `4` | VM CPU count |
| `MEMORY` | `8G` | VM memory |
| `DISK` | `30G` | VM disk size |
| `KEEP_VM` | `true` | Keep VM after script exits |
| `SKIP_BUILD` | `false` | Skip build if images already exist |
| `LOCAL_REGISTRY` | `localhost:5000` | Local in-VM registry used by k3s pulls |

#### Idempotent re-runs

The script is safe to re-run. It reuses an existing VM, skips installed
dependencies, and performs a clean Helm install each time.

### Step 2: Run load tests

```bash
./scripts/e2e-loadtest.sh
```

This script:

1. **Verifies** the nanofaas API is reachable and all 6 functions are registered
2. **Starts Grafana** locally via Docker (port 3000), auto-provisioned with:
   - Prometheus datasource pointing to the VM
   - Pre-built dashboard with 7 panels
3. **Runs k6 load tests** for each function sequentially:
   - Ramp-up profile: 0 → 5 → 10 → 20 → 20 → 0 VUs over ~2 minutes
   - 10-second cooldown between tests
4. **Generates a performance report** with per-function and per-runtime analysis

#### Load test profile

Each k6 test uses a 5-stage ramp pattern:

| Stage | Duration | VUs | Purpose |
|-------|----------|-----|---------|
| 1 | 10s | 0 → 5 | Warm-up |
| 2 | 30s | 5 → 10 | Ramp up |
| 3 | 30s | 10 → 20 | Increase load |
| 4 | 30s | 20 | Sustained peak |
| 5 | 10s | 20 → 0 | Ramp down |

#### Pass/fail thresholds

| Metric | Java/Python | Bash |
|--------|-------------|------|
| p95 latency | < 3000ms | < 5000ms |
| p99 latency | < 5000ms | < 10000ms |
| Failure rate | < 15% | < 20% |

#### Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `NANOFAAS_URL` | Auto-detected from VM | Override if not using multipass |
| `PROM_URL` | Auto-detected from VM | Override Prometheus URL |
| `VM_NAME` | `nanofaas-e2e` | VM name for IP detection |
| `SKIP_GRAFANA` | `false` | Skip Grafana startup |

### Step 3: View results

**Grafana dashboard**: http://localhost:3000/d/nanofaas-functions (admin/admin)

7 panels showing:
- Request rate (enqueue vs success per function)
- Error & timeout rate
- Latency percentiles (p50/p95/p99)
- Queue depth and in-flight requests
- Total invocations (cumulative stat)
- Dispatch rate
- Retry rate

**Prometheus**: http://192.168.x.x:30090 — run arbitrary PromQL queries

**k6 results**: `k6/results/` directory with JSON summaries and log files

### Step 4: Cleanup

```bash
# Stop Grafana
docker compose -f grafana/docker-compose.yml down

# Delete the VM
multipass delete nanofaas-e2e && multipass purge
```

---

## Expected Results

Typical results on a 4-core VM (ARM64, Apple Silicon host):

| Function | Requests | Fail% | Avg (ms) | p95 (ms) | Req/s |
|---|---|---|---|---|---|
| word-stats-java | ~750 | ~9% | ~1760 | ~2560 | ~7 |
| json-transform-java | ~750 | ~8% | ~1760 | ~2560 | ~7 |
| word-stats-python | ~12000 | 0% | ~16 | ~66 | ~108 |
| json-transform-python | ~12000 | 0% | ~13 | ~40 | ~110 |
| word-stats-exec | ~4000 | 0% | ~236 | ~497 | ~37 |
| json-transform-exec | ~11000 | 0% | ~25 | ~47 | ~99 |

### Performance observations

**Python functions are the fastest** (~110 req/s, <20ms avg). The FastAPI
runtime is lightweight with minimal overhead per request.

**Bash exec functions are mid-tier**. `json-transform-exec` is fast (~99 req/s)
since `jq` is efficient. `word-stats-exec` is slower (~37 req/s) because
word counting in bash requires more shell operations.

**Java functions have the highest latency** (~1.7s avg, ~7 req/s). This is
dominated by the control-plane dispatcher overhead and the JVM-based function
runtime. The ~8% failure rate comes from the sync queue's estimated-wait
admission controller during the initial warm-up period before throughput
stabilizes.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  Host machine                                               │
│  ┌────────────┐  ┌────────┐  ┌──────────────────────────┐  │
│  │ k6 (load)  │  │ Grafana│  │ curl (manual tests)      │  │
│  └─────┬──────┘  └───┬────┘  └──────────┬───────────────┘  │
│        │             │                   │                  │
│        │   :30090    │                   │ :30080           │
├────────┼─────────────┼───────────────────┼──────────────────┤
│  Multipass VM (nanofaas-e2e) — k3s                          │
│  ┌─────┴─────────────┴───────────────────┴───────────────┐  │
│  │  namespace: nanofaas                                  │  │
│  │                                                       │  │
│  │  ┌─────────────────────┐  ┌────────────────────────┐  │  │
│  │  │ control-plane       │  │ prometheus             │  │  │
│  │  │  :8080 (API)        │  │  :9090 → NodePort:30090│  │  │
│  │  │  :8081 (metrics)    │  │  scrapes all pods      │  │  │
│  │  │  NodePort:30080/81  │  └────────────────────────┘  │  │
│  │  └─────────┬───────────┘                              │  │
│  │            │ dispatches to                             │  │
│  │  ┌─────────┴──────────────────────────────────────┐   │  │
│  │  │  fn-word-stats-java   fn-json-transform-java   │   │  │
│  │  │  fn-word-stats-python fn-json-transform-python │   │  │
│  │  │  fn-word-stats-exec   fn-json-transform-exec   │   │  │
│  │  └────────────────────────────────────────────────┘   │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

## Troubleshooting

### "namespace already exists" during helm install

The script handles this automatically by cleaning up before install. If you hit
this manually, run:

```bash
multipass exec nanofaas-e2e -- sudo kubectl delete namespace nanofaas
```

### Functions return 429 Too Many Requests

The sync queue admission controller rejects requests when it doesn't have enough
throughput history. The setup script disables it via
`SYNC_QUEUE_ADMISSION_ENABLED=false`. If you see this, verify the env var:

```bash
multipass exec nanofaas-e2e -- sudo kubectl get deployment nanofaas-control-plane \
  -n nanofaas -o jsonpath='{.spec.template.spec.containers[0].env}'
```

### Pods stuck in ImagePullBackOff

This means k3s cannot pull from the configured local registry. Verify the
registry config and images:

```bash
multipass exec nanofaas-e2e -- cat /etc/rancher/k3s/registries.yaml
multipass exec nanofaas-e2e -- curl -s http://localhost:5000/v2/_catalog
```

### Bash functions crash-looping

Bash exec functions must use `runtimeMode: STDIO` (not `HTTP`). The setup script
configures this in the Helm values. Verify:

```bash
curl http://<VM_IP>:30080/v1/functions/word-stats-exec | python3 -m json.tool | grep runtimeMode
```

### Control-plane returns 401 Unauthorized on function registration

The Fabric8 Kubernetes client needs the in-cluster ServiceAccount token. The
`KubernetesClientConfig` reads it manually from
`/var/run/secrets/kubernetes.io/serviceaccount/token`. Verify the token is
mounted:

```bash
multipass exec nanofaas-e2e -- sudo kubectl exec -n nanofaas \
  deploy/nanofaas-control-plane -- ls /var/run/secrets/kubernetes.io/serviceaccount/
```

### SSH into the VM for debugging

```bash
multipass shell nanofaas-e2e
sudo kubectl get pods -n nanofaas
sudo kubectl logs -n nanofaas deploy/nanofaas-control-plane --tail=50
```
