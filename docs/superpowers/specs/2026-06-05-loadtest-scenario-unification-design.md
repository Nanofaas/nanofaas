# Loadtest Scenario Unification — Design

**Date:** 2026-06-05
**Status:** Approved (design), phased; Phase A goes to a plan first.

## 1. Context & Problem

The three VM loadtest scenarios — `two-vm-loadtest` (multipass), `azure-vm-loadtest`,
`proxmox-vm-loadtest` — are conceptually the same e2e flow (provision a k3s stack,
deploy nanofaas, register functions, run a k6 load test from a separate loadgen VM,
capture Prometheus, report) but are implemented three different ways. Investigation
(2026-06-05) shows the divergence is **accidental drift, not principled difference**:

| Aspect | two-vm | proxmox | azure |
|---|---|---|---|
| Stack prelude | 10 components (`provision_base`→`helm.deploy_function_runtime`), hand-built | same 10 + `vm.ensure_running` | **none** (no k3s/helm/images at all) |
| Function registration | **none** in `run()` (recipe lists `cli.*` but execution skips it) | `cli.fn_apply_selected` substituted by REST `functions.register` for loadtest | none |
| Loadgen sequence | `Workflow([install_k6, run_k6, fetch, prom, report])` + cleanup | same steps but wrapped in `_ActionTask` + lazy `state` dict | identical to two-vm |
| Connectivity | multipass IP (`<multipass-ip:NAME>` via `CommandResolver`) | proxmox NAT host + published SSH port (`_rewrite_ansible`) | public host |
| Execution machinery | `build_command_tasks` (recipe→CommandTasks) for stack; hand-built loadgen | bespoke `_build_prelude_tasks` + `_rewrite_ansible`; hand-built loadgen | fully hand-built; no recipe execution |

**Key findings:**
- **two-vm silently skips function registration** — its `run()` registers no functions
  even though its recipe lists `cli.build_install_dist` + `cli.fn_apply_selected`. This is
  a drift/bug, not a design choice.
- **azure has no provisioning at all** — it can only work against a pre-provisioned
  stack that nothing sets up. A gap.
- The **only legitimate difference is connectivity** (multipass IP / proxmox NAT / azure
  public host) — exactly what the k6 work (PR #97) already abstracted as parameters.
- `recipes.py` duplicates the 11-component prelude across 6 recipes (flat tuples, no
  composition); the three loadtest recipes are ~21–22 near-identical `component_ids`.

**Goal:** collapse the three loadtest scenarios into instances of **one** flow that
differs only by a per-lifecycle connectivity adapter, fixing the drift (two-vm gains
function registration; azure gains provisioning) and removing the duplication.

## 2. Target Architecture

A loadtest scenario = **`(canonical_recipe, ConnectivityAdapter)`**.

### 2.1 Canonical loadtest recipe (composed from fragments)
Adopt the proxmox superset as canonical:

```
PROVISION_PRELUDE = (vm.ensure_running, vm.provision_base, repo.sync_to_vm,
                     registry.ensure_container, images.build_core,
                     images.build_selected_functions, k3s.install,
                     k3s.configure_registry, namespace.install,
                     helm.deploy_control_plane, helm.deploy_function_runtime)
FUNCTION_SETUP    = (cli.build_install_dist, cli.fn_apply_selected)   # see note
LOADGEN_SEQUENCE  = (loadgen.ensure_running, loadgen.install_k6, loadgen.run_k6,
                     metrics.prometheus_snapshot, loadtest.write_report, loadgen.down)
TEARDOWN          = (vm.down,)

LOADTEST_RECIPE = PROVISION_PRELUDE + FUNCTION_SETUP + LOADGEN_SEQUENCE + TEARDOWN
```

**Note on function registration:** in loadtest scenarios, `cli.fn_apply_selected` is
executed as a REST `functions.register` step (proxmox's existing behavior), not a CLI
call. The canonical flow keeps this substitution. `loadgen.provision_base` is included
where the lifecycle needs it (multipass loadgen is reachable for ansible without it; kept
optional per adapter).

### 2.2 `ConnectivityAdapter` (the one legitimate difference)
A per-lifecycle adapter supplying everything that differs between multipass/azure/proxmox:

- `lifecycle` — the `VmLifecycleAdapter` (ensure/destroy) for stack and loadgen.
- `vm` / runner+fetcher — the orchestrator for `OrchestratorVmRunner` / `VmFileFetcher`.
- `stack_host(stack_request, stack_info)` and `loadgen_endpoint(loadgen_request, loadgen_info) -> (host, user, key, port)` — host/port/key resolution for ansible/k6 (multipass IP; proxmox NAT host+port; azure public host).
- `control_plane_url(...)` / `prometheus_url(...)` — URL sources (proxmox publishes a Prometheus NodePort).
- `prepare()` / per-step hooks — e.g. proxmox `publish_ports` (NAT) before loadgen.

### 2.3 One execution machinery
`build_command_tasks` / host-operation resolution is extended to take a **connectivity
strategy** instead of hard-coding multipass `<multipass-ip:NAME>` resolution. The same
recipe then runs through the same machinery for all three lifecycles. The live loadgen
tasks (`RunK6`/`FetchVmResults`/`CapturePrometheusSnapshot`/`WriteK6Report`) move into a
**shared loadgen-sequence builder** parametrized by the adapter, replacing the three
hand-built `run()` blocks. proxmox's bespoke `_build_prelude_tasks` + `_rewrite_ansible`
and its `_ActionTask`/lazy-`state` pattern are retired in favor of the shared path.

## 3. Phased Roadmap

Too large for one plan. Each phase is its own plan/implementation cycle. Phase A first.

- **Phase A — Recipe-fragment composition** *(low risk, behavior-preserving)*
  Introduce named fragments and rebuild `recipes.py` (and the scenarios' hand-built
  prelude tuples) as `fragment + delta`. The produced `component_ids` for every scenario
  stay **identical** to today, so existing task-id/ordering/recipe tests are the safety
  net. Delivers the dedup of §1's recipe duplication and makes the canonical recipe
  expressible. No execution changes.

- **Phase B1 — Connectivity strategy in the execution machinery**
  Extend `build_command_tasks` / `resolve_host_operation` to accept a per-lifecycle
  connectivity strategy (multipass / proxmox / azure). Prove behavior-preserving on
  two-vm (multipass) first; then route proxmox through it (retiring `_build_prelude_tasks`
  + `_rewrite_ansible`); then azure (**new behavior:** gains the standard provisioning).

- **Phase B2 — Shared loadgen sequence**
  Extract the `install_k6 → run_k6 → fetch → prometheus → report` (+ ensure/destroy
  loadgen) sequence into one builder parametrized by the `ConnectivityAdapter`; collapse
  the three hand-built loadgen blocks.

- **Phase B3 — Final collapse**
  The three scenarios become thin `(canonical_recipe, ConnectivityAdapter)` instances;
  remove the per-scenario duplication; one `run()` path.

## 4. Testing Strategy

- **Unit/structural (CI):** recipe `component_ids`/ordering/task-id tests (Phase A must keep
  them green by construction); adapter resolution unit tests (host/port/key per lifecycle);
  argv-oracle tests for the unified machinery; shared loadgen-sequence builder tests.
- **Real-VM validation (NOT in CI — required for Phase B):** because the CI has no e2e VM
  coverage and Phase B is behavior-changing, each B phase MUST be validated on real VMs:
  - multipass (`two-vm-loadtest`) — always.
  - azure / proxmox — when credentials are available; otherwise the behavior change for
    those lifecycles ships unvalidated and must be flagged in the PR.

## 5. Risks

- **No CI e2e coverage** — the unit tests prove structure (task_ids, argv), not that the
  real flow works. Behavior changes (azure provisioning, two-vm function registration)
  can pass all unit tests yet break a real run. Mitigation: §4 real-VM validation gates.
- **proxmox is the hard lifecycle** — NAT publish + lazy endpoint resolution + its
  bespoke prelude machinery. Fold it in only after two-vm proves the shared path.
- **two-vm behavior change** — gaining function registration (REST) is a fix but changes
  what the flow does; validate the k6 target function is actually registered/served.
- **Scope creep** — keep helm/namespace → ansible and bash `InstallK6` removal OUT (their
  own future items); this spec is only the loadtest unification.

## 6. Out of Scope
- helm/namespace/cleanup → ansible (`helm` module) — separate future item.
- Removing the deprecated bash `InstallK6` — after B3, separate.
- The non-loadtest scenarios (`k3s-junit-curl`, `helm-stack`, `cli-stack`) — they already
  execute recipe-driven via `build_command_tasks`; Phase A dedups their recipes, but they
  are not part of the loadtest collapse.
