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

# Run all tests (unit + integration; excludes k8s E2E by @Tag)
./gradlew test --no-parallel

# E2E scenarios (run from NanoLab checkout)
# Container validation (requires Docker)
(cd ../nanolab && ./nanolab.sh run packages/nanolab/scenarios-v2/validate-container.yaml --environment packages/nanolab/environments/local.yaml)
# Kubernetes validation (requires Multipass; provisions k3s VM, deploys Helm, runs curl + K8sE2eTest)
(cd ../nanolab && ./nanolab.sh run packages/nanolab/scenarios-v2/validate-k8s.yaml --environment packages/nanolab/environments/multipass.yaml)
# Plan (dry-run) to preview the workflow without executing
(cd ../nanolab && ./nanolab.sh plan packages/nanolab/scenarios-v2/validate-k8s.yaml --environment packages/nanolab/environments/local.yaml)

# Run only K8sE2eTest against an already-prepared cluster
NANOFAAS_RUN_K8S_E2E=true \
KUBECONFIG=/path/to/kubeconfig \
NANOFAAS_E2E_NAMESPACE=nanofaas-e2e \
./gradlew \
  :control-plane-modules:k8s-deployment-provider:test \
  -PrunE2e \
  --tests 'it.unimib.datai.nanofaas.modules.k8s.e2e.K8sE2eTest' \
  --no-parallel

# Build OCI images
./gradlew :control-plane:bootBuildImage
./gradlew :services:java:warm-echo:bootBuildImage

# Control-plane optional module selection
./gradlew :control-plane:bootJar -PcontrolPlaneModules=all

# Native build (GraalVM via SDKMAN)
./scripts/native-build.sh

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
- **PoolDispatcher** - Dispatches to warm Deployment+Service pods (supports DEPLOYMENT and POOL execution modes)
- **ExecutionStore** - Tracks execution lifecycle with TTL eviction

Core provides no-op defaults for:
- **InvocationEnqueuer**
- **ScalingMetricsSource**
- **SyncQueueGateway**
- **ImageValidator**

Optional control-plane modules (loaded via `ControlPlaneModule` SPI from `platform/modules/`):
- **async-queue** - Per-function queues + scheduler for async enqueue path
- **sync-queue** - Sync admission/backpressure queue
- **autoscaler** - Internal scaler and scaling metrics integration
- **runtime-config** - Hot runtime config service and admin API (`/v1/admin/runtime-config`) when `nanofaas.admin.runtime-config.enabled=true`
- **image-validator** - Kubernetes-backed image validation
- **build-metadata** - `/modules/build-metadata` diagnostics endpoint
- **k8s-deployment-provider** - Kubernetes managed deployment backend for `DEPLOYMENT`
- **container-deployment-provider** - Local managed deployment backend using a Docker-compatible runtime CLI
- **offload** - Conditional transparent proxy of sync invocations to a remote nanofaas instance (eager per-function policy, or on sync-queue DEPTH/EST_WAIT rejection); single hop, no local fallback (remote failure → 502/504)

Execution Modes:
- **DEPLOYMENT** - Managed deployment intent resolved through a backend provider (`k8s`, `container-local`, ...)
- **POOL** - OpenWhisk-style warm pool mode
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
5. Dispatcher forwards request to runtime endpoint (LOCAL/POOL/DEPLOYMENT mode)
6. Control plane updates execution state and returns result/status

## Key Configuration

`platform/control-plane/src/main/resources/application.yml`:
- `nanofaas.defaults.timeoutMs` (30000), `concurrency` (4), `queueSize` (100), `maxRetries` (3)
- `nanofaas.rate.maxPerSecond` (1000000)
- `nanofaas.deployment.default-backend`
- `nanofaas.k8s.namespace`, `callbackUrl`
- `nanofaas.container-local.runtime-adapter`, `bind-host`, `readiness-timeout`, `readiness-poll-interval`
- `nanofaas.offload.enabled` (true), `target-url` (global default; a per-function `offload.targetUrl` also activates), `pressure-enabled` (true)

## Testing

- JUnit 5, tests named `*Test.java`
- E2E uses Testcontainers + RestAssured
- K8s E2E requires `KUBECONFIG` environment variable
- Fabric8 mock server for K8s unit tests

## Project Constraints

- Single control-plane pod (no HA/distributed mode)
- In-memory state (and in-memory queues when queue modules are enabled)
- No authentication/authorization
- Performance and latency prioritized over features
- Java 21 toolchain, 4-space indentation, `com.nanofaas` package root

<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **mcFaas** (15282 symbols, 40055 relationships, 300 execution flows). Use the GitNexus MCP tools to understand code, assess impact, and navigate safely.

> If any GitNexus tool warns the index is stale, run `npx gitnexus analyze` in terminal first.

## Always Do

- **MUST run impact analysis before editing any symbol.** Before modifying a function, class, or method, run `gitnexus_impact({target: "symbolName", direction: "upstream"})` and report the blast radius (direct callers, affected processes, risk level) to the user.
- **MUST run `gitnexus_detect_changes()` before committing** to verify your changes only affect expected symbols and execution flows.
- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `gitnexus_query({query: "concept"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol — callers, callees, which execution flows it participates in — use `gitnexus_context({name: "symbolName"})`.

## When Debugging

1. `gitnexus_query({query: "<error or symptom>"})` — find execution flows related to the issue
2. `gitnexus_context({name: "<suspect function>"})` — see all callers, callees, and process participation
3. `READ gitnexus://repo/mcFaas/process/{processName}` — trace the full execution flow step by step
4. For regressions: `gitnexus_detect_changes({scope: "compare", base_ref: "main"})` — see what your branch changed

## When Refactoring

- **Renaming**: MUST use `gitnexus_rename({symbol_name: "old", new_name: "new", dry_run: true})` first. Review the preview — graph edits are safe, text_search edits need manual review. Then run with `dry_run: false`.
- **Extracting/Splitting**: MUST run `gitnexus_context({name: "target"})` to see all incoming/outgoing refs, then `gitnexus_impact({target: "target", direction: "upstream"})` to find all external callers before moving code.
- After any refactor: run `gitnexus_detect_changes({scope: "all"})` to verify only expected files changed.

## Never Do

- NEVER edit a function, class, or method without first running `gitnexus_impact` on it.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace — use `gitnexus_rename` which understands the call graph.
- NEVER commit changes without running `gitnexus_detect_changes()` to check affected scope.

## Tools Quick Reference

| Tool | When to use | Command |
|------|-------------|---------|
| `query` | Find code by concept | `gitnexus_query({query: "auth validation"})` |
| `context` | 360-degree view of one symbol | `gitnexus_context({name: "validateUser"})` |
| `impact` | Blast radius before editing | `gitnexus_impact({target: "X", direction: "upstream"})` |
| `detect_changes` | Pre-commit scope check | `gitnexus_detect_changes({scope: "staged"})` |
| `rename` | Safe multi-file rename | `gitnexus_rename({symbol_name: "old", new_name: "new", dry_run: true})` |
| `cypher` | Custom graph queries | `gitnexus_cypher({query: "MATCH ..."})` |

## Impact Risk Levels

| Depth | Meaning | Action |
|-------|---------|--------|
| d=1 | WILL BREAK — direct callers/importers | MUST update these |
| d=2 | LIKELY AFFECTED — indirect deps | Should test |
| d=3 | MAY NEED TESTING — transitive | Test if critical path |

## Resources

| Resource | Use for |
|----------|---------|
| `gitnexus://repo/mcFaas/context` | Codebase overview, check index freshness |
| `gitnexus://repo/mcFaas/clusters` | All functional areas |
| `gitnexus://repo/mcFaas/processes` | All execution flows |
| `gitnexus://repo/mcFaas/process/{name}` | Step-by-step execution trace |

## Self-Check Before Finishing

Before completing any code modification task, verify:
1. `gitnexus_impact` was run for all modified symbols
2. No HIGH/CRITICAL risk warnings were ignored
3. `gitnexus_detect_changes()` confirms changes match expected scope
4. All d=1 (WILL BREAK) dependents were updated

## Keeping the Index Fresh

After committing code changes, the GitNexus index becomes stale. Re-run analyze to update it:

```bash
npx gitnexus analyze
```

If the index previously included embeddings, preserve them by adding `--embeddings`:

```bash
npx gitnexus analyze --embeddings
```

To check whether embeddings exist, inspect `.gitnexus/meta.json` — the `stats.embeddings` field shows the count (0 means no embeddings). **Running analyze without `--embeddings` will delete any previously generated embeddings.**

> Claude Code users: A PostToolUse hook handles this automatically after `git commit` and `git merge`.

## CLI

| Task | Read this skill file |
|------|---------------------|
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus/gitnexus-cli/SKILL.md` |

<!-- gitnexus:end -->
