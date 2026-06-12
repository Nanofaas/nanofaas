# E2E Scenario Taxonomy Design

**Date:** 2026-06-12
**Status:** approved design (sections reviewed interactively)
**Scope:** the 13 e2e scenarios in `controlplane_tool.scenario.catalog` — canonical names, two-tier descriptions, TUI menu placement, deprecated-alias mechanics.

## Problem

Scenario names mix four naming dimensions — verification tooling (`k3s-junit-curl`), install mechanism (`helm-stack`), topology (`one-vm-helm-loadtest`, `two-vm-loadtest`), provider (`azure-vm-loadtest`) — and some say nothing at all (`docker`, `buildpack`, `cli`). Descriptions are one-liners that don't help choosing from a menu. Five loadtest scenarios sit under `Validation → platform` although they are benchmarks, while the top-level `Loadtest` menu only hosts the local mock-k8s loadtest. Every new scenario has invented a fifth naming style.

## Decisions (made with the user)

1. **Rename scenario IDs, with deprecated aliases** (not labels-only, not a hard rename). Old IDs keep working in CLI args, scenario files (`base_scenario:`), and saved profiles, with a one-line deprecation notice.
2. **Purpose-first prefix scheme**: `validate-`, `loadtest-`, `cli-`. The prefix doubles as the menu category.
3. **Loadtest scenarios move to the top-level `Loadtest` menu** (`local | vm` fork); `Validation → platform` keeps only true validations.
4. **Two-tier descriptions**: a one-line `description` (compact listings) plus a new multi-line `details` field (TUI help pane): phases, prerequisites, artifacts, indicative duration, cleanup behavior.
5. All artifacts in English.

## Canonical names

| Current ID (becomes alias) | Canonical ID |
|---|---|
| `k3s-junit-curl` | `validate-k3s` |
| `container-local` | `validate-container-local` |
| `docker` | `validate-docker-pool` |
| `buildpack` | `validate-buildpack-pool` |
| `deploy-host` | `validate-deploy-host` |
| `helm-stack` | `loadtest-helm-legacy` |
| `one-vm-helm-loadtest` | `loadtest-one-vm` |
| `two-vm-loadtest` | `loadtest-two-vm` |
| `azure-vm-loadtest` | `loadtest-azure` |
| `proxmox-vm-loadtest` | `loadtest-proxmox` |
| `cli` | `cli-suite` |
| `cli-stack` | `cli-stack` (unchanged) |
| `cli-host` | `cli-host` (unchanged) |

## Descriptions

### One-liners (`ScenarioDefinition.description`)

| Canonical ID | description |
|---|---|
| `validate-k3s` | Multipass VM + k3s + Helm stack, verified with curl probes and the JUnit K8sE2eTest suite. |
| `validate-container-local` | No-Kubernetes managed DEPLOYMENT backend, fully local, one selected function. |
| `validate-docker-pool` | Local POOL runtime regression with Docker-built images on the host. |
| `validate-buildpack-pool` | Local POOL runtime with buildpack-built images, plus managed local DEPLOYMENT coverage. |
| `validate-deploy-host` | Host-only deploy workflow against a stub control-plane (host compatibility path). |
| `loadtest-helm-legacy` | Legacy Helm install + k6 loadtest + autoscaling sequence via the experiments/ scripts. |
| `loadtest-one-vm` | Helm stack, k6, and autoscaling verification on a single Multipass VM. |
| `loadtest-two-vm` | Helm stack on one Multipass VM, dedicated k6 generator on a second; Prometheus snapshots + HTML report. |
| `loadtest-azure` | Two-VM loadtest on Azure (OpenTofu, profiles/azure.toml): stack VM with open NodePorts + k6 loadgen VM. |
| `loadtest-proxmox` | Two-VM loadtest on Proxmox VE (cloned templates, NAT-published ports, profiles/proxmox.toml). |
| `cli-suite` | Full nanofaas-cli lifecycle test suite executed inside a managed VM against k3s. |
| `cli-stack` | Canonical self-bootstrapping VM stack driven end-to-end by the CLI. |
| `cli-host` | CLI on the host driving a VM-backed platform install (compatibility route). |

### Details (`ScenarioDefinition.details`, shown in the TUI help pane)

**`validate-k3s`**
> Provisions a Multipass VM, installs k3s and a local image registry, builds and pushes the core + selected function images in-VM, deploys control-plane and function-runtime via Helm, then verifies the deployment two ways: curl probes against the NodePort API and the JUnit `K8sE2eTest` suite executed against the cluster (the resolved scenario manifest is forwarded via `-Dnanofaas.e2e.scenarioManifest`). Includes uninstall/namespace cleanup and VM teardown phases. The canonical "is the platform healthy end-to-end" check. Requires Multipass. ~15–20 min.

**`validate-container-local`**
> Runs the managed DEPLOYMENT execution mode without any Kubernetes: the container-local provider drives a Docker-compatible runtime on this machine for a single selected function, exercising registration, provisioning, invocation, and teardown of the no-k8s backend. No VM is created. Requires a running Docker daemon. Minutes, not tens of minutes.

**`validate-docker-pool`**
> Exercises the local POOL runtime path (OpenWhisk-style warm pods) using Docker-built images on the host via the Gradle e2e suite (Testcontainers + RestAssured). Regression net for warm-pool dispatch, retries, and idempotency against a real local container runtime. Requires a running Docker daemon. ~5–10 min.

**`validate-buildpack-pool`**
> Same local POOL runtime exercise as `validate-docker-pool`, but the function images are produced with Cloud Native Buildpacks (`bootBuildImage`), additionally covering the managed local DEPLOYMENT path with buildpack output. Slower than the Docker variant because buildpack builds are heavyweight. Requires a running Docker daemon. ~10–15 min.

**`validate-deploy-host`**
> Host-only workflow: builds on the host, pushes through a local registry, and validates the deploy-host compatibility path against a stub control-plane — no VM, no k3s. Catches regressions in host-side build/registration behavior cheaply. Requires Docker for the local registry. Minutes.

**`loadtest-helm-legacy`**
> The historical Helm path kept for parity: installs the stack, then drives the legacy `experiments/` scripts for k6 load and autoscaling checks (`loadtest.run`, `experiments.autoscaling`) on a Multipass VM. Superseded by `loadtest-one-vm` for new work; run this when comparing against historical results or validating the legacy script path itself. Requires Multipass. ~25–35 min from scratch.

**`loadtest-one-vm`**
> Single Multipass VM hosts everything: Helm stack, k6, and the autoscaling probe. After the standard k6 run + Prometheus snapshot + HTML report, a tail re-registers the target function with an INTERNAL scaling config (min 0 / max 5), runs a dedicated k6 ramp while a background watcher samples deployment replicas, and verifies scale-up >1 *during* load and scale-down to 0 after. The cheapest way to exercise the full loadtest + autoscaling path — no second VM, no cloud credentials. Artifacts under `tools/controlplane/runs/` (k6 + autoscaling summaries, snapshot, report). Requires Multipass. ~20 min.

**`loadtest-two-vm`**
> Helm stack on one Multipass VM; a second, dedicated VM generates k6 load so the stack VM's resources are not polluted by the generator. Invokes the selected function through the control-plane NodePort, captures Prometheus snapshots, and writes `k6-summary.json`, `metrics/prometheus-snapshot.json` and `report.html` under `tools/controlplane/runs/`. Default function selection is the lean `demo-java` pair; pass `--function-preset demo-loadtest` for the full 8-image matrix. Requires Multipass. ~25–35 min from scratch.

**`loadtest-azure`**
> Provisions two Azure VMs via OpenTofu (stack: k3s + Helm stack, NodePorts 30080/30081/30090 opened in the NSG; loadgen: k6). Reads defaults from `profiles/azure.toml` (local file — copy from `azure.toml.example`). Registers the selected functions (default: demo-java), runs k6 against the control-plane public endpoint, captures Prometheus snapshots, writes the usual artifacts under `tools/controlplane/runs/`. Requires `az login` with access to the configured resource group, and tofu. VM sizes/cost: see the profile (default D4s_v5 + B1s). ~25–35 min; VMs destroyed at the end unless cleanup is declined.

**`loadtest-proxmox`**
> Two-VM loadtest on a Proxmox VE cluster: clones a VM template for the stack node and the k6 loadgen node, publishes the control-plane and Prometheus NodePorts through NAT rules, runs the same Helm/k6 workflow as `loadtest-two-vm`, and tears the clones down on completion. Reads connection and template settings from `profiles/proxmox.toml` (local file — copy from `proxmox.toml.example`). Requires reachable Proxmox VE credentials and a prepared template. ~25–35 min.

**`cli-suite`**
> Executes the full nanofaas-cli lifecycle test suite inside a managed VM against a k3s-backed platform: function init, build, deploy, invoke, logs, update, and removal flows. The broadest CLI regression net; significantly longer than `cli-stack`. Requires Multipass. Use `--no-cleanup-vm` to keep the VM for debugging.

**`cli-stack`**
> The canonical CLI evaluation flow: a self-bootstrapping VM stack where the CLI itself drives platform install and validation over k3s. Faster and more focused than `cli-suite`; the default choice for "does the CLI still work end-to-end". Requires Multipass.

**`cli-host`**
> Compatibility route where the CLI binary stays on the host machine and targets a VM-backed platform install — catches host/cluster drift (paths, kubeconfig, registry addressing) that in-VM runs can't see. Requires Multipass.

## TUI menu map

```
Main
├── Validation
│   ├── platform: validate-k3s, validate-container-local,
│   │             validate-docker-pool, validate-buildpack-pool
│   ├── cli:      cli-stack, cli-suite, cli-host
│   └── host:     validate-deploy-host
├── Loadtest
│   ├── local — k6 vs local mock control-plane (existing run/plan/new-profile, unchanged)
│   └── vm — full-stack loadtests on real VMs:
│             loadtest-one-vm, loadtest-two-vm, loadtest-azure,
│             loadtest-proxmox, loadtest-helm-legacy (last, labeled legacy)
└── Building, Profiles, Environment, VM … unchanged
```

Movements: the 5 loadtest scenarios leave `Validation → platform`; docker/buildpack join `Validation → platform`; `cli-suite` joins `Validation → cli`; ordering is canonical-first, legacy-last.

## Alias mechanics

**Normalize at the boundaries; canonical everywhere inside.**

- `ScenarioDefinition` gains `aliases: tuple[str, ...] = ()` and the catalog gains
  `canonical_scenario_name(name: str) -> str` (alias → canonical; unknown names returned unchanged so existing validation errors stay intact).
- Called at exactly three entry points: CLI argument parsing (`e2e run`, `e2e all --only/--skip`, `cli-test run`), the scenario-file loader (`base_scenario:`, which also covers saved profiles), and TUI dispatch.
- Alias use prints one stderr line: `note: '<old>' is deprecated, use '<new>'`.
- Everything downstream switches to canonical names only — recipes, `scenario_defaults._DEFAULTS`, `LOADTEST_SCENARIOS`, `_default_selection_for`, plan branches, `scenario ==` checks, golden tests. No dual lookups inside.

**Known blast radius** (inventoried, not guessed): `scenario/catalog.py`; `core/models.py` (VALID_SCENARIOS); `scenario/components/recipes.py` + recipe/catalog golden tests; `scenario/scenario_defaults.py` (`_DEFAULTS`); `workflow_tasks/loadtest/two_vm.py` (`LOADTEST_SCENARIOS`); `e2e/e2e_runner.py` (`plan`/`plan_all` branches); `scenario/scenario_flows.py` branches; `scenario/loadtest_adapter.py` + `one_vm_loadtest_adapter.py` scenario checks; `cli/e2e_commands.py` (`_default_selection_for`, stack-name defaults, `_AZURE_STACK_NODE_PORTS` guard); TUI choices + the two dispatch membership sets; docs (`README.md`, `tools/controlplane/README.md`, `docs/testing.md`, `CLAUDE.md`). Sample manifests under `tools/controlplane/scenarios/*.toml` keep their old `base_scenario` values **on purpose** — they double as alias regression fixtures.

## Testing

- **Alias contract test (keystone)**: parametrized over all old→new pairs — `canonical_scenario_name(old) == new`, `resolve_scenario(old).name == new`, and `e2e run <old> --dry-run` produces the same plan as the canonical name.
- Golden/recipe/catalog tests move to canonical names (deliberate one-time update).
- TUI tests: choice lists show canonical IDs; dispatch accepts both forms.
- **No-stale-names guard**: a test asserting no old ID appears under `tools/*/src/` outside the alias tables.
- Full controlplane + workflow-tasks suites are the gate (not in CI).

## Out of scope

- Renaming function presets, profiles, or the `Loadtest → local` actions.
- Changing scenario behavior, recipes, or defaults (the lean `demo-java` default landed separately in PR #128).
- Removing the aliases (a future major cleanup once scripts/docs have migrated).
