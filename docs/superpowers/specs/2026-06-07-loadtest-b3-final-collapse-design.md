# Loadtest Phase B3 — Final Collapse Design

**Date:** 2026-06-07
**Status:** Approved (design). Staged plan (B3a/b/c) follows.
**Parent:** `docs/superpowers/specs/2026-06-05-loadtest-scenario-unification-design.md` (§3 Phase B3).

## 1. Context

Phases A, B1a, B1b, B2 are merged. The loadgen *sequence body* (K6Config + the 5-task
install→run→fetch→prometheus→report) is now shared (`workflow_tasks/loadtest/loadgen_sequence.py`),
and the provision prelude runs through `build_command_tasks` + a per-lifecycle
`ConnectivityStrategy` (`MultipassConnectivity`, `ProxmoxConnectivity`). What remains
un-collapsed is the **orchestration shell**: each scenario still has its own `run()`.

Post-B2 reality:
- **two-vm** (272 lines) — eager `run()`: ensure stack → `build_command_tasks` prelude →
  RegisterFunctions → ensure loadgen → shared loadgen body. Native `Workflow`/`workflow_step` events.
- **azure** (170 lines) — eager `run()` with `_skeleton()` placeholders; **no provisioning
  prelude at all** (can only run against a pre-provisioned stack nothing sets up — effectively
  non-functional today).
- **proxmox** (779 lines) — lazy `_ActionTask`/`state` driver with manual offset-based event
  emission (`_run_prelude_workflow`, `_run_tail_tasks`, `_emit`, `_tail_step`), because NAT
  endpoints aren't known until a `publish_ports` step runs mid-flow.

`ConnectivityStrategy` + `build_command_tasks` are **generic** — also used by `k3s-junit-curl`,
`cli-stack`, `helm-stack`. They must stay loadtest-agnostic.

## 2. Goal

Collapse the three loadtest scenarios into thin **`(canonical_recipe, LoadtestConnectivityAdapter)`**
instances sharing **one `run()` driver**, retiring the three bespoke `run()` bodies, proxmox's
`_ActionTask`/`state`/manual-emit machinery, and the three `_skeleton()`/static-task-id blocks.
Azure gains the canonical provisioning prelude (**new behavior; shipped argv/structurally-guarded,
flagged unvalidated** — no credentials).

## 3. Decisions (locked in brainstorming, 2026-06-07)

1. **Azure scope:** full collapse including azure (azure gains the canonical provisioning prelude).
2. **Driver model:** one ordered driver threading a shared `RunContext`, emitting **native
   Workflow/`workflow_step` events** — retiring proxmox's bespoke `_ActionTask` + manual offset emit.
3. **Adapter shape:** `LoadtestConnectivityAdapter` **composes** a generic `ConnectivityStrategy`
   (untouched) plus loadtest-only members. `ConnectivityStrategy` is NOT extended with loadtest concerns.

## 4. Target Architecture

### 4.1 `RunContext` — shared mutable state
A dataclass threaded through the flow, fields populated as steps run (replaces proxmox's
ad-hoc `state: dict`):

```
stack_info        # VmInfo from ensure_stack
stack_host        # guest/NAT host for control-plane + prometheus URL resolution
loadgen_info      # VmInfo from ensure_loadgen (has .home)
control_plane_url # resolved after stack ready (+ proxmox control-plane port publish)
prometheus_url    # resolved after prometheus port known (proxmox: after NAT publish)
run_dir           # local results dir (TwoVmLoadtestRunner._create_run_dir)
remote_paths      # two_vm_remote_paths(loadgen_info.home, payload_name=...)
```

Adapter resolver methods take a `RunContext` and read what earlier steps populated. Fields
are `None` until their producing step runs; resolvers are only called after their inputs exist.

### 4.2 `LoadtestConnectivityAdapter` (composition, one per lifecycle)
Exposes the generic `ConnectivityStrategy` for the prelude PLUS loadtest-only members:

```
connectivity: ConnectivityStrategy          # for build_command_tasks (prelude host-ops/vm-ops)
stack_lifecycle:   VmLifecycleAdapter        # ensure/destroy stack VM
loadgen_lifecycle: VmLifecycleAdapter        # ensure/destroy loadgen VM

loadgen_install_endpoint(ctx) -> InstallEndpoint   # (host, user, private_key, port|None)
loadgen_runner(ctx)  -> VmCommandRunner            # OrchestratorVmRunner for the loadgen VM
fetcher(ctx)         -> RemoteFileFetcher          # VmFileFetcher for the loadgen VM
control_plane_url(ctx) -> str
prometheus_url(ctx)    -> str

extra_steps(phase: FlowPhase, ctx) -> list[Task]   # lifecycle-specific injected steps
```

`InstallEndpoint` is a small dataclass mapping to `install_k6_task` args
(`host`, `user`, `private_key: Path|None`, `port: int|None`). Multipass returns `port=None`;
proxmox returns the published NAT port.

Three implementations:
- **`MultipassLoadtestAdapter`** — `MultipassConnectivity`; install endpoint = loadgen IP +
  `_find_ssh_private_key_path(find_ssh_public_key())`, no port; URLs via `two_vm_control_plane_url`/
  `two_vm_prometheus_url(host=stack_info.host)`; `extra_steps` empty.
- **`ProxmoxLoadtestAdapter`** — `ProxmoxConnectivity`; install endpoint from
  `proxmox_orch.ssh_endpoint(loadgen_request)` + key (with port); URLs from the published NAT
  host/ports; `extra_steps(AFTER_STACK_READY)` publishes the control-plane port and sets
  `ctx.control_plane_url`/`ctx.stack_host`; `extra_steps(BEFORE_LOADGEN)` publishes the
  prometheus NodePort and sets `ctx.prometheus_url`.
- **`AzureLoadtestAdapter`** — Azure connectivity (public host); install endpoint via
  `azure_orch.connection_host`/`ssh_private_key_path`, no port; URLs via the two_vm helpers with
  the public host; `extra_steps` empty. Uses the canonical prelude (NEW; unvalidated).

### 4.3 `run_loadtest_flow` — the unified driver
Executes the canonical ordered sequence, threading `RunContext`, emitting native events. Realized
as **one ordered driver threading one context** (not a flat pre-built list — later steps depend on
earlier outputs, so later phases are built when reached, from the now-populated context; this is the
same "lazy relative to earlier phases" the scenarios already rely on):

```
FlowPhase order:
1. ENSURE_STACK      EnsureVmRunning(stack)              -> ctx.stack_info, ctx.stack_host
2. PRELUDE           build_command_tasks(recipe, adapter.connectivity)
                     + adapter.extra_steps(AFTER_STACK_READY, ctx)   # proxmox: publish CP port
3. REGISTER          RegisterFunctions(REST, ctx.control_plane_url, selected functions)
4. ENSURE_LOADGEN    EnsureVmRunning(loadgen)            -> ctx.loadgen_info, ctx.remote_paths, ctx.run_dir
5. PRE_LOADGEN       adapter.extra_steps(BEFORE_LOADGEN, ctx)        # proxmox: publish prom port -> ctx.prometheus_url
6. LOADGEN_BODY      build_loadgen_body_tasks(LoadgenBodyInputs(...resolved from ctx + adapter...))
7. CLEANUP           DestroyVm(loadgen), DestroyVm(stack)   # guarded by request.cleanup_vm
```

Each phase's tasks run through `Workflow`/`workflow_step` so events are emitted uniformly with
correct sequential indices — no manual offset bookkeeping. The 5 loadgen-body steps remain
**distinct events** (built when phase 6 is reached, after ensure/publish populated ctx).

### 4.4 Static plan / dry-run (single source of truth)
The driver (or a sibling pure function) derives `task_ids` and `phase_titles` from the canonical
recipe + adapter, replacing all three of `_skeleton()`, `_TWO_VM_STATIC_TASK_IDS`, and proxmox's
`_display_prelude_tasks`/`_SkeletonStep`. The per-lifecycle title suffixes ("(Azure)"/"(Proxmox)")
are supplied by the adapter so dry-run output stays identical per lifecycle.

### 4.5 Canonical recipe
The proxmox superset (from the parent spec §2.1): provision prelude (ensure handled separately) →
function setup (REST register) → loadgen sequence → teardown. Expressed via the recipe fragments
introduced in Phase A.

## 5. Testing Strategy

- **Structural (CI):**
  - Per-lifecycle argv goldens stay green by construction — `test_two_vm_stack_prelude_argv.py`
    and `test_proxmox_prelude_argv.py` must not change.
  - New `LoadtestConnectivityAdapter` resolution unit tests (endpoint/URLs/extra_steps per lifecycle).
  - `run_loadtest_flow` phase-ordering test (correct step sequence + RunContext population) with fakes.
  - Dry-run `task_ids`/`phase_titles` parity test per lifecycle (output identical to today).
  - Full `controlplane` + `workflow_tasks` suites green.
- **Real-VM (NOT in CI — required gate, parent spec §4):**
  - multipass `two-vm-loadtest` end-to-end — **always required** (B3a).
  - proxmox — argv-golden-guarded; validate if hardware available, else flag unvalidated (B3b).
  - azure — **gains provisioning; ships unvalidated** (no creds); flag prominently in the PR (B3c).

## 6. Staged Delivery (one spec, staged plan — each its own PR)

- **B3a** — Introduce `RunContext`, `LoadtestConnectivityAdapter` (+ `MultipassLoadtestAdapter`),
  and `run_loadtest_flow`. Route **two-vm (multipass)** through it; behavior byte-identical
  (argv golden + dry-run parity green). Real-VM validate on multipass. The other two scenarios
  keep their current `run()` until their stage.
- **B3b** — `ProxmoxLoadtestAdapter`; route proxmox through `run_loadtest_flow`, retiring its
  `_ActionTask`/`state`/`_run_prelude_workflow`/`_run_tail_tasks`/manual-emit machinery and
  `_skeleton`. argv golden + dry-run parity green.
- **B3c** — `AzureLoadtestAdapter`; route azure through `run_loadtest_flow`, adding the canonical
  provisioning prelude (NEW behavior). Remove azure `_skeleton`. Flag unvalidated.

After B3c the three scenario files are thin builders returning `(recipe, adapter)` for the shared
driver; the parent unification roadmap is complete.

## 7. Risks

- **No CI e2e** — structural tests prove task_ids/argv/ordering, not a live run. Each stage gated on
  real-VM validation (multipass always; proxmox/azure as available).
- **Azure provisioning is new and unvalidated** — full collapse adds a real behavior change for azure
  with no creds to test. Mitigation: argv/structural guards + prominent PR flag; treat azure as
  "expected-to-work-by-construction" until validated.
- **proxmox is the hard lifecycle** — fold it in (B3b) only after multipass (B3a) proves the driver;
  keep its argv golden untouched as the byte-identity guarantee.
- **Event/dry-run parity** — the TUI consumes `phase_titles`/`task_ids`/`event_listener`; the unified
  driver must reproduce them per lifecycle. Dry-run parity tests are the guard.

## 8. Out of Scope

- helm/namespace/cleanup → ansible (separate future item).
- Removing the deprecated bash `InstallK6` (after B3, separate).
- Non-loadtest scenarios (`k3s-junit-curl`, `helm-stack`, `cli-stack`) — they already run
  recipe-driven via `build_command_tasks`; untouched except that `ConnectivityStrategy` stays generic.
- The `_skeleton()` placeholder collapse for a scenario happens in that scenario's stage, not before.
