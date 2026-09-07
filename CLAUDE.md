# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Development Commands

```bash
# The ops/provisioning tool lives in a separate checkout: https://github.com/miciav/nanolab
export NANOFAAS_ROOT="$(pwd)"   # nanolab commands below read nanoFaaS source from here

# Canonical control-plane orchestration wrapper
(cd ../nanolab && ./nanolab.sh --help)

# Build all modules
./gradlew build

# Run all tests (unit + integration; E2E validation is owned by NanoLab)
./gradlew test --no-parallel

# E2E scenarios (run from NanoLab checkout)
# Container validation (requires Docker)
(cd ../nanolab && ./nanolab.sh run packages/nanolab/scenarios-v2/deployment-lifecycle-container.yaml --environment packages/nanolab/environments/local.yaml)
# Kubernetes validation (requires Multipass; provisions k3s VM, deploys Helm, validates via HTTP + K8s assertions)
(cd ../nanolab && ./nanolab.sh run packages/nanolab/scenarios-v2/deployment-lifecycle-k8s.yaml --environment packages/nanolab/environments/multipass.yaml)
# Plan (dry-run) to preview the workflow without executing
(cd ../nanolab && ./nanolab.sh plan packages/nanolab/scenarios-v2/deployment-lifecycle-k8s.yaml --environment packages/nanolab/environments/local.yaml)

# Build JVM OCI images on Distroless Java 25
docker build -f platform/control-plane/Dockerfile -t nanofaas/control-plane .
docker build -f services/java/warm-echo/Dockerfile -t nanofaas/warm-echo .

# Build native OCI images on Distroless
./scripts/native-java-image.sh control-plane
./scripts/native-java-image.sh warm-echo

# Control-plane optional module selection
./gradlew :control-plane:bootJar -PcontrolPlaneModules=all

# Build every native Java binary (configured GraalVM release via SDKMAN)
./scripts/native-build.sh

# Local SonarQube analysis (ephemeral Docker server, on demand — not CI; see docs/sonarqube.md)
./scripts/sonar.sh [--rm] [--only java|python|rust]

# Experiments / load tests (see experiments/)
./experiments/e2e-cold-start-metrics.sh   # Cold start metrics
./experiments/e2e-runtime-config.sh       # Runtime config hot-update E2E
./experiments/run.sh                      # Interactive wizard (control-plane experiment)
# Python tests for experiments
cd experiments && python -m pytest tests/ -v
```

VM-provisioning Ansible playbooks are bundled inside the `workflow_tasks` library in the `nanolab` repo (`packages/workflow-tasks/src/workflow_tasks/infra/ansible_assets/`).

## Architecture Overview

nanofaas is a minimal FaaS platform for Kubernetes.

### platform/control-plane/
Minimal core API + dispatch orchestration in a single pod. Core components:
- **FunctionRegistry** - In-memory function storage
- **InvocationService** - Sync/async invocation orchestration, retries, idempotency integration
- **ExternalDispatcher** - Forwards invocations to an externally hosted function endpoint (EXTERNAL execution mode)
- **ExecutionStore** - Tracks execution lifecycle with TTL eviction

Core provides no-op defaults for:
- **InvocationEnqueuer**
- **ScalingMetricsSource**
- **SyncQueueGateway**
- **ImageValidator**

Optional control-plane modules (loaded via `ControlPlaneModule` SPI from `platform/modules/`):
- **async-queue** - Per-function queues + scheduler for async enqueue path
- **sync-queue** - Sync admission/backpressure queue
- **autoscaler** - Internal replica scaler and scaling metrics integration
- **concurrency-control** - Per-function concurrency governor: static per replica, latency-adaptive, or `BUDGETED` (per-function SLO served from a platform-wide concurrency budget with weighted max-min fairness); independent of the replica scaling strategy
- **runtime-config** - Hot runtime config service and admin API (`/v1/admin/runtime-config`) when `nanofaas.admin.runtime-config.enabled=true`
- **build-metadata** - `/modules/build-metadata` diagnostics endpoint
- **k8s-deployment-provider** - Kubernetes managed deployment backend and image validation for `DEPLOYMENT`
- **container-deployment-provider** - Local managed deployment backend and image validation using a Docker-compatible runtime
- **offload** - Conditional transparent proxy of sync invocations to a remote nanofaas instance (eager per-function policy, or on sync-queue DEPTH/EST_WAIT rejection); single hop, no local fallback (remote failure → 502/504)

Execution Modes:
- **DEPLOYMENT** - Managed deployment intent resolved through a backend provider (`k8s`, `container-local`, ...)
- **EXTERNAL** - Function hosted outside the control plane; invocations are forwarded to its `endpointUrl` (passthrough, no lifecycle management)
- **LOCAL** - In-process execution for testing

Spring WebFlux (non-blocking). Ports: 8080 (API), 8081 (management/metrics).

### sdks/java/ and services/java/warm-echo/
`sdks/java` supplies the reusable HTTP invocation runtime (`/invoke`, tracing and callbacks). `services/java/warm-echo` embeds that SDK as a runnable WARM reference service; it is provisioned through the normal function-registration API, never installed as shared infrastructure.

### sdks/python/
Python function SDK providing the FastAPI-based runtime for Python handlers.

### platform/common/
Shared contracts: `FunctionSpec`, `InvocationRequest`, `InvocationResponse`, `ExecutionStatus`, `FunctionHandler` interface.

## Request Flow

1. Client -> `POST /v1/functions/{name}:invoke` (sync) or `:enqueue` (async)
2. Control plane validates, applies rate limit, creates execution state
3. Sync path: uses `sync-queue` if enabled, else `async-queue` if enabled, else dispatches inline from core
4. Async path: requires `async-queue` (otherwise API returns `501 Not Implemented`)
5. Dispatcher forwards request to runtime endpoint (LOCAL/EXTERNAL/DEPLOYMENT mode)
6. Control plane updates execution state and returns result/status

## Key Configuration

`platform/control-plane/src/main/resources/application.yml`:
- `nanofaas.defaults.timeoutMs` (30000), `concurrency` (4), `queueSize` (100), `maxRetries` (3)
- `nanofaas.rate.maxPerSecond` (1000000)
- `nanofaas.execution-store.ttl`, `maxLifetime`, `syncTtl`, `max-outcomes` (100000), `max-keys` (100000), `max-outcome-bytes` (0 = `max-outcomes` × 116 B; the byte budget is what actually bounds heap, since a readable outcome retains the caller's payload)
- `nanofaas.http-client.connectTimeoutMs` (5000), `readTimeoutMs` (30000), `maxInMemorySizeMb` (16), `max-connections` (500), `pending-acquire-max-count` (0 = 2 × max-connections), `pending-acquire-timeout-ms` (45000)
- `nanofaas.deployment.default-backend`
- `nanofaas.k8s.namespace`, `callbackUrl`
- `nanofaas.container-local.runtime-adapter`, `bind-host`, `readiness-timeout`, `readiness-poll-interval`
- `nanofaas.offload.enabled` (true), `target-url` (global default; a per-function `offload.targetUrl` also activates), `pressure-enabled` (true)

## Testing

- JUnit 5, tests named `*Test.java`
- E2E uses Testcontainers + WebTestClient
- K8s E2E requires `KUBECONFIG` environment variable
- Fabric8 mock server for K8s unit tests

## Project Constraints

- Single control-plane pod (no HA/distributed mode)
- In-memory state (and in-memory queues when queue modules are enabled)
- No authentication/authorization
- Performance and latency prioritized over features
- Java 25 toolchain, 4-space indentation, `com.nanofaas` package root

<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **nanofaas** (16911 symbols, 45352 relationships, 743 execution flows).

> Index stale? Run `node .gitnexus/run.cjs analyze --index-only` from the project root — it auto-selects an available runner. No `.gitnexus/run.cjs` yet? Bootstrap with `npx`, `bunx`, or `pnpm dlx` — e.g. `bunx gitnexus@latest analyze` (npm 11 npx crash; #1939).

## Always Do

- **MUST run impact before editing.** Use `impact({target: "symbolName", direction: "upstream"})` or `node .gitnexus/run.cjs impact "symbolName" --direction upstream --repo .`; report callers, processes, and risk. Never substitute grep for graph analysis.
- **MUST analyze graph changes before committing.** Use `detect_changes({scope: "all"})` (MCP) or `node .gitnexus/run.cjs detect-changes --scope all --repo .` (CLI fallback). `partial: true` or `truncated: true` is not a clean check — a zero means unseen, not unaffected; re-run it. For regression review: `detect_changes({scope: "compare", base_ref: "main"})` or `node .gitnexus/run.cjs detect-changes --scope compare --base-ref "main" --repo .`.
- MUST warn on HIGH/CRITICAL `risk` pre-edit; never use `riskSharedAxes` to waive a HIGH/CRITICAL `risk` warning. Compare File/symbol: MCP File omits axes; Graph-RAG expands File.
- **MUST treat `risk: UNKNOWN` as unresolved, not as low.** An empty caller set is not evidence the symbol is unused — it can also mean the callers are not resolvable by the index (plain-object property access, dynamic dispatch, cross-language calls). `impact` pairs `UNKNOWN` with a `riskNote` saying so. Confirm with a text search before treating the symbol as safe to change or delete; do not proceed on the strength of a zero.
- **MUST use `query({search_query: "concept"})` for concepts/flows, `context({name: "symbolName"})` for a named symbol, or `impact` for blast radius, on read-only callers, dependencies, imports, or execution flow.** Graph first; text search only for empty/`UNKNOWN`/literals.
- For security review, `explain({target: "fileOrSymbol"})` lists taint findings (source→sink flows; needs `analyze --pdg`).

## Never Do

- NEVER edit a function, class, or method before MCP/CLI impact analysis.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis, and never read `UNKNOWN` as an all-clear — it means the walk could not answer, which is the one verdict that requires confirming by other means.
- NEVER rename symbols with find-and-replace — use `rename` which understands the call graph.
- NEVER commit before MCP/CLI graph change analysis.

## Resources

| Resource | Use for |
| --- | --- |
| `gitnexus://repo/nanofaas/context` | Codebase overview, check index freshness |
| `gitnexus://repo/nanofaas/clusters` | All functional areas |
| `gitnexus://repo/nanofaas/processes` | All execution flows |
| `gitnexus://repo/nanofaas/process/{name}` | Step-by-step execution trace |

## CLI

| Task | Read this skill file |
| --- | --- |
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus-cli/SKILL.md` |

<!-- gitnexus:end -->
