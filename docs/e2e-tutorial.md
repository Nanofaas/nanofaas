# nanofaas E2E Validation Tutorial

This tutorial walks through deploying nanofaas on a local Kubernetes cluster,
running load tests against the benchmarked demo functions, and visualizing
metrics with Grafana. The entire process is automated with two scripts.

## Prerequisites

| Tool | Purpose | Install |
|------|---------|---------|
| **python3** | Host-side Ansible bootstrap | Included with macOS/Linux or install Python 3 |
| **ssh** | Remote command execution and file copy | Included with macOS/Linux or install OpenSSH client |
| **multipass** | Optional VM lifecycle manager | `brew install multipass` or [multipass.run](https://multipass.run) |
| **Docker** | Build images + run Grafana | [docker.com](https://docs.docker.com/get-docker/) |
| **k6** | Load testing | `brew install k6` or [k6 docs](https://grafana.com/docs/k6/latest/set-up/install-k6/) |

> SSH/SCP are always used for remote command execution and file transfer.
> Multipass is used only when `E2E_VM_LIFECYCLE=multipass` and you want the
> scripts to create/start/delete the VM for you. VM provisioning is handled by
> Ansible over SSH for both lifecycle modes. All other dependencies (k3s, Helm,
> JDK 21) are installed automatically inside the VM, and k3s defaults to the
> latest official release unless you set `K3S_VERSION`. The current playbooks
> assume a Debian/Ubuntu-style VM because bootstrap and package installation use
> `apt`. If Ansible is missing on the host, the scripts bootstrap it with a
> local virtualenv when available and fall back to a user-site `pip` install.

## Quick Start (2 commands)

```bash
# 1. Deploy nanofaas (creates VM, builds, deploys, verifies — ~15 min first run)
./scripts/controlplane.sh e2e run helm-stack

# 2. Run load tests + Grafana dashboard (~12 min)
./scripts/e2e-loadtest.sh
```

That's it. Open http://localhost:3000 (admin/admin) to see the Grafana dashboard
while the tests run.

When you want to dry-run or narrow the E2E selection before running the heavier wrappers,
use the controlplane tool directly:

```bash
scripts/controlplane.sh functions list
scripts/controlplane.sh e2e run k3s-curl --function-preset demo-java --dry-run
scripts/controlplane.sh e2e run --scenario-file tools/controlplane/scenarios/k8s-demo-java.toml --dry-run
scripts/controlplane.sh e2e run k8s-vm --saved-profile demo-java --dry-run
scripts/controlplane.sh loadtest list-profiles
scripts/controlplane.sh loadtest run --scenario-file tools/controlplane/scenarios/k8s-demo-java.toml --load-profile quick --dry-run
scripts/e2e-loadtest.sh --profile demo-java --dry-run
```

The split is intentional: `scripts/controlplane.sh loadtest run ...` is the generic first-class planner, while `scripts/e2e-loadtest.sh` stays as a compatibility wrapper over `experiments/e2e-loadtest.sh` for the legacy Helm/Grafana/parity flow.

The reusable TOML scenario specs live under `tools/controlplane/scenarios/`.

---

## Step-by-Step Guide

### Step 1: Deploy nanofaas

```bash
./scripts/controlplane.sh e2e run helm-stack
```

This script performs the following automatically:

1. **Creates a Multipass VM** (`nanofaas-e2e`, 4 CPU, 8 GB RAM, 30 GB disk) when `E2E_VM_LIFECYCLE=multipass`, or reuses your existing VM over SSH when `E2E_VM_LIFECYCLE=external`
2. **Runs Ansible provisioning** inside the VM for Docker, JDK 21 and Helm
3. **Installs or upgrades k3s** (lightweight Kubernetes, Traefik disabled) to the latest official release unless `K3S_VERSION` is pinned
4. **Syncs the project** to the VM and **builds all artifacts**:
   - Gradle JARs: control-plane, function-runtime, Java demo functions
   - Docker images: 2 core + 2 Java + 2 Go + 2 Python + 2 Bash + 2 Java Lite = 12 images
5. **Sets up a local registry** in the VM (`localhost:5000`) and **pushes images**
6. **Deploys via Helm** with NodePort services:
   - Control-plane API on port **30080**
   - Actuator/metrics on port **30081**
   - Prometheus on port **30090**
7. **Registers 10 demo functions** (via Helm post-install hook):
   - `word-stats-java`, `json-transform-java` (Java/Spring Boot)
   - `word-stats-go`, `json-transform-go` (Go SDK runtime)
   - `word-stats-python`, `json-transform-python` (Python/FastAPI)
   - `word-stats-exec`, `json-transform-exec` (Bash/Watchdog STDIO)
   - `word-stats-java-lite`, `json-transform-java-lite` (Java native image)
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
| `K3S_VERSION` | latest official release | Optional explicit k3s pin |

#### External/local or remote VM mode

Use these variables when you want to reuse an existing VM instead of creating one with Multipass:

```bash
E2E_VM_LIFECYCLE=external
E2E_VM_HOST=<ip-or-dns>
E2E_VM_USER=<ssh-user>
E2E_VM_HOME=<optional-home>
E2E_KUBECONFIG_PATH=<optional-remote-kubeconfig>
E2E_REMOTE_PROJECT_DIR=<optional-remote-repo-path>
E2E_PUBLIC_HOST=<optional-nodeport-host>
E2E_KUBECONFIG_SERVER=<optional-https-server-url>
```

Examples:

```bash
E2E_VM_LIFECYCLE=external E2E_VM_HOST=192.168.64.20 E2E_VM_USER=ubuntu ./scripts/controlplane.sh e2e run k3s-curl
E2E_VM_LIFECYCLE=external E2E_VM_HOST=ci-k3s.example.com E2E_VM_USER=dev E2E_VM_HOME=/srv/dev E2E_KUBECONFIG_SERVER=https://ci-k3s.example.com:6443 ./scripts/controlplane.sh cli-test run host-platform
```

`E2E_PUBLIC_HOST` is useful when the SSH host and the NodePort-reachable host differ.
`E2E_KUBECONFIG_SERVER` is useful when the kube-apiserver is not reachable at `https://<ssh-host>:6443`.

#### Idempotent re-runs

The script is safe to re-run. It reuses an existing VM, applies Ansible
provisioning idempotently, and performs a clean Helm install each time.

### Step 2: Run load tests

```bash
./scripts/e2e-loadtest.sh
./scripts/e2e-loadtest.sh --profile demo-java --dry-run
```

This script:

1. **Verifies** the nanofaas API is reachable and all 10 functions are registered
2. **Checks output parity** across runtimes (`word-stats`, `json-transform-*`) before load generation
3. **Starts Grafana** locally via Docker (port 3000), auto-provisioned with:
   - Prometheus datasource pointing to the VM
   - Pre-built dashboard with function filter, queue-depth percentiles, and zero-filled error-rate panels
4. **Runs k6 load tests** for each benchmarked function sequentially:
   - Ramp-up profile: 0 → 5 → 10 → 20 → 20 → 0 VUs over ~2 minutes
   - 10-second cooldown between tests
5. **Generates a performance report** with per-function and per-runtime analysis

`--profile demo-java` is compatibility sugar for the legacy script: it narrows the benchmark matrix to the Java demo workloads while keeping the same Helm/Grafana/parity backend. Registry-summary flows remain on `./scripts/e2e-loadtest-registry.sh --summary-only`.

Go demo functions are deployed and smoke-tested by `./scripts/controlplane.sh e2e run helm-stack`,
but they are not yet included in the current k6 benchmark matrix because the
repository does not yet ship `experiments/k6/*-go.js` workloads.

For all supported parameters and examples:

```bash
./scripts/e2e-loadtest.sh --help
```

Payload variability and payload-profile metrics are documented in:

- [docs/loadtest-payload-profile.md](loadtest-payload-profile.md)

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
| `NANOFAAS_URL` | Auto-detected from public host | Override API base URL explicitly |
| `PROM_URL` | Auto-detected from public host | Override Prometheus URL explicitly |
| `VM_NAME` | `nanofaas-e2e` | VM name used when `E2E_VM_LIFECYCLE=multipass` |
| `SKIP_GRAFANA` | `false` | Skip Grafana startup |
| `VERIFY_OUTPUT_PARITY` | `true` | Run semantic output parity checks before k6 |
| `PARITY_TIMEOUT_SECONDS` | `20` | Request timeout (seconds) for each parity invocation |
| `K6_PAYLOAD_MODE` | `legacy-random` | Payload mode: `legacy-random`, `pool-sequential`, `pool-random` |
| `K6_PAYLOAD_POOL_SIZE` | `5000` | Pool size for pool-based modes |

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

The registry summary now includes:

- `SECTION 9: PAYLOAD PROFILE (k6 INPUT MIX)`

This section reports payload coverage/reuse/collision stats and payload-size
distribution (`Avg(B)`, `Q1(B)`, `Q2(B)`, `Q3(B)`).

### Step 4: Cleanup

```bash
# Stop Grafana
docker compose -f grafana/docker-compose.yml down

# Delete the VM if lifecycle=multipass
multipass delete nanofaas-e2e && multipass purge
```

If `E2E_VM_LIFECYCLE=external`, the scripts do not delete the VM. You manage its lifecycle yourself.

---

## Expected Results

Typical results on a 4-core VM (ARM64, Apple Silicon host) for the current
benchmarked runtimes:

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

**Go demo functions are currently deployable but not benchmarked in this
table**. Add dedicated `experiments/k6/word-stats-go.js` and
`experiments/k6/json-transform-go.js` workloads before comparing Go against the
other runtimes here.

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
│  VM (multipass lifecycle or external) — k3s                │
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
│  │  │  fn-word-stats-java      fn-json-transform-java      │   │  │
│  │  │  fn-word-stats-go        fn-json-transform-go        │   │  │
│  │  │  fn-word-stats-python    fn-json-transform-python    │   │  │
│  │  │  fn-word-stats-exec      fn-json-transform-exec      │   │  │
│  │  │  fn-word-stats-java-lite fn-json-transform-java-lite │   │  │
│  │  └────────────────────────────────────────────────┘   │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

## Troubleshooting

### "namespace already exists" during helm install

The script handles this automatically by cleaning up before install. If you hit
this manually, run:

```bash
ssh <user>@<vm-host> 'sudo kubectl delete namespace nanofaas'
```

### Functions return 429 Too Many Requests

The sync queue admission controller rejects requests when it doesn't have enough
throughput history. The setup script disables it via
`SYNC_QUEUE_ADMISSION_ENABLED=false`. If you see this, verify the env var:

```bash
ssh <user>@<vm-host> "sudo kubectl get deployment nanofaas-control-plane \
  -n nanofaas -o jsonpath='{.spec.template.spec.containers[0].env}'"
```

### Pods stuck in ImagePullBackOff

This means k3s cannot pull from the configured local registry. Verify the
registry config and images:

```bash
ssh <user>@<vm-host> 'cat /etc/rancher/k3s/registries.yaml'
ssh <user>@<vm-host> 'curl -s http://localhost:5000/v2/_catalog'
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
ssh <user>@<vm-host> "sudo kubectl exec -n nanofaas \
  deploy/nanofaas-control-plane -- ls /var/run/secrets/kubernetes.io/serviceaccount/"
```

### SSH into the VM for debugging

```bash
ssh <user>@<vm-host>
sudo kubectl get pods -n nanofaas
sudo kubectl logs -n nanofaas deploy/nanofaas-control-plane --tail=50
```
