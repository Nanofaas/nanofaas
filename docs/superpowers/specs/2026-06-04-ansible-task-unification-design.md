# Ansible Task Unification — Design

**Date:** 2026-06-04
**Status:** Approved (design), pending implementation plan

## 1. Context & Problem

nanofaas scenarios (e2e, loadtest, cli) are assembled from **components**. Each
component has a *planner* producing `ScenarioOperation`s, executed through one of
four mechanisms:

| Mechanism | Meaning | Runs on |
|---|---|---|
| **ANSIBLE** | `ansible-playbook X.yml` (playbooks in `workflow_tasks/infra/ansible_assets/`) | host → VM via SSH |
| **VM-cmd** | tool invocation (`helm`/`docker`/`gradlew`/cli) with `execution_target="vm"` | on the VM via runner |
| **HOST-cmd** | host CLI (`multipass`/`rsync`/`ssh`/`python -m`) | on the host |
| **bash-task** | hand-built Python `Task` running a bash one-liner | on the VM via orchestrator |

The provisioning path already standardised on **ansible** (provision_base,
registry, k3s install, k3s registry config, k6 install — as recipe components).
The remaining divergence is concentrated and duplicative:

- The **loadgen k6 install** is hand-built as a **bash `InstallK6` task** in three
  scenarios (`two-vm-loadtest`, `azure-vm-loadtest`, `proxmox-vm-loadtest`),
  *re-implementing* what the existing `loadgen.install_k6` ansible component
  (`install-k6.yml`) already does.
- A **second, divergent bash impl** (apt-based `InstallK6`) lives in
  `controlplane_tool/scenario/tasks/loadtest.py` (referenced only by tests).
- Scenarios that are nearly identical duplicate their entire component list
  (`ScenarioRecipe` is a flat tuple — no composition). The three loadtest
  recipes are ~22 near-identical `component_ids`; k3s/helm share an 11-component
  prelude, copied.

**Goal:** establish one way to install k6 (ansible), via a reusable, TUI-aware
ansible task, additively (without deleting the old bash tasks yet), and lay the
foundation to collapse near-identical scenarios. Accurate scenario↔component
mapping is a first-class deliverable.

## 2. Authoritative Mapping

### 2.1 Components × execution mechanism

| Component | Mechanism today | Ansible candidate? |
|---|---|---|
| `vm.ensure_running` / `vm.down` | HOST-cmd (`multipass launch`/`delete`) | ❌ lifecycle CLI, not ansible |
| `vm.provision_base` | ✅ **ANSIBLE** (`provision-base.yml`) | done |
| `repo.sync_to_vm` | HOST-cmd (rsync) | ⚠️ possible (`synchronize`); low value |
| `registry.ensure_container` | ✅ **ANSIBLE** (`ensure-registry.yml`) | done |
| `k3s.install` | ✅ **ANSIBLE** (`provision-k3s.yml`) | done |
| `k3s.configure_registry` | ✅ **ANSIBLE** (`configure-k3s-registry.yml`) | done |
| `loadtest.install_k6` / `loadgen.install_k6` | ✅ **ANSIBLE** (`install-k6.yml`) — *as component* | done |
| `images.build_core.*` / `images.build_selected_functions.*` | VM-cmd (`gradlew`/`docker`) | ⚠️ build logic — low value / high risk |
| `helm.deploy_control_plane` / `_function_runtime` | VM-cmd (`helm`) | ⚠️ **Phase 3 candidate** (ansible `helm` module) |
| `namespace.install` / `namespace.uninstall` | VM-cmd (`helm`) | ⚠️ **Phase 3 candidate** |
| `cleanup.uninstall_*` | VM-cmd (`helm`) | ⚠️ **Phase 3 candidate** |
| `cli.*` (8 components) | VM-cmd (cli binary) | ❌ this is the SUT |
| `tests.run_k8s_junit` / `tests.run_k3s_curl_checks` | VM/host-cmd (script / injected) | ❌ verification |
| `loadgen.run_k6` | HOST/VM-cmd (`k6 run`) | ❌ the test execution itself |
| `metrics.prometheus_snapshot` / `loadtest.write_report` | HOST-cmd (`python -m`) | ❌ application logic |

### 2.2 Scenarios × execution

| Scenario | Provisioning | Install k6 | Note |
|---|---|---|---|
| `k3s-junit-curl` | recipe → `build_command_tasks` (ansible where applicable) | n/a | consistent |
| `helm-stack` | recipe → `build_command_tasks` | ✅ **ansible** (`loadtest.install_k6` in recipe) | **already unified** |
| `two-vm-loadtest` | stack: `build_command_tasks` (ansible) | ❌ **bash-task** `InstallK6` (hand-built loadgen Workflow) | diverges |
| `proxmox-vm-loadtest` | prelude: ansible with `_rewrite_ansible` (SSH port) | ❌ **bash-task** `InstallK6` (in `_ActionTask`) | diverges |
| `azure-vm-loadtest` | ⚠️ **no provisioning in `run()`** (only ensure VM + k6 workflow) | ❌ **bash-task** `InstallK6` | incomplete/divergent |

**Takeaway:** the only real bash→ansible conversion is the loadgen k6 install,
duplicated across 3 scenarios (plus the dead apt impl). helm/namespace/cleanup
are legitimate tool calls documented as Phase-3 ansible candidates.

## 3. Goals / Non-Goals

**Goals**
- One reusable, generic, TUI-aware ansible task (`RunPlaybook`).
- Migrate the loadgen k6 install in all 3 loadtest scenarios to `RunPlaybook`.
- Keep `task_id="loadgen.install_k6"` so dry-run/TUI/task-id assertions are unaffected.
- Additive: old bash `InstallK6` tasks remain (deprecated, not deleted).
- Authoritative mapping committed as documentation.
- Design the bricks (parametric connectivity + recipe fragments) that enable
  collapsing near-identical scenarios later.

**Non-Goals (this spec)**
- Deleting the bash tasks.
- Converting helm/namespace/cleanup to ansible.
- Fixing azure's missing provisioning.
- Fully collapsing two-vm/azure/proxmox into one scenario (Phase 3).

## 4. Design

### 4.1 `RunPlaybook` — generic ansible task

New module: `workflow_tasks/infra/ansible/` (new directory, additive).

Signature (illustrative):

```python
@dataclass
class RunPlaybook:
    task_id: str
    title: str
    playbook: str                       # e.g. "install-k6.yml" (bundled)
    host: str                           # resolved IP / hostname (no placeholder)
    user: str
    shell: ShellBackend                 # workflow-aware SubprocessShell (host)
    private_key: Path | None = None
    port: int | None = None             # proxmox uses a mapped SSH port
    extra_vars: Mapping[str, str] | None = None

    def run(self) -> None: ...
```

Behavior:
- Builds argv:
  `ansible-playbook -i "<host>," -u <user> [--private-key <key>]
  [-e ansible_port=<port>] [-e k=v ...] <bundled_ansible_root>/playbooks/<playbook>`
  with `ANSIBLE_CONFIG=<bundled_ansible_root>/ansible.cfg`.
- Runs **on the host** via the workflow-aware `SubprocessShell`.
- Raises on non-zero exit (stderr/stdout in the message).
- Reuses `bundled_ansible_root()` and the existing playbooks. No new playbooks.

**Connectivity is parametric** (`host/user/key/port`). This is deliberate: it
turns the per-lifecycle difference (multipass IP / proxmox host+port+key / azure
host+key) into constructor arguments rather than separate task classes.

### 4.2 TUI integration

`RunPlaybook` runs on `SubprocessShell`, which already routes each output line to
`workflow_log` when a workflow sink is active → **live ansible output streams to
the TUI** out of the box (better than the current bash-on-VM task, which goes
through the orchestrator).

For **phase-level granularity** (one TUI sub-step per ansible task), `RunPlaybook`
configures a machine-readable ansible callback (e.g. `ANSIBLE_STDOUT_CALLBACK`)
and maps each parsed ansible task to a nested `step()`/`success()` emitted under
the parent task (`workflow_step` supports `parent_task_id` nesting). The exact
granularity (live-log baseline vs full task-level sub-steps) is a plan-level
detail; the design supports both, with task-level sub-steps as the target.

### 4.3 Scenario migration (loadgen install_k6)

In `two-vm`/`azure`/`proxmox` `run()`, replace the hand-built bash `InstallK6`
with `RunPlaybook(playbook="install-k6.yml", ...)` instantiated from values each
scenario already computes:

- **two-vm (multipass):** `host` = resolved loadgen IP (`resolve_multipass_ipv4`
  of the loadgen VM), `user`/`key` from the loadgen request, no `port`.
- **proxmox:** `host`/`port`/`key` from the loadgen VM's published SSH endpoint
  (the same values `_rewrite_ansible` already computes for the stack).
- **azure:** `host` = `loadgen_info.host`, `user`/`key` from the azure orchestrator.

`task_id` stays `loadgen.install_k6`; titles preserved. The bash `InstallK6`
classes (workflow_tasks + the dead apt one in controlplane) remain in place,
deprecated.

### 4.4 Managing near-identical scenarios (foundation for Phase 3)

A scenario is conceptually **`(composed_recipe, connectivity_adapter)`**.

**Axis A — *what* tasks (recipe composition).** Replace flat tuples with named
fragments composed into recipes:

```
PROVISION_PRELUDE = (vm.ensure_running, vm.provision_base, repo.sync_to_vm,
                     registry.ensure_container, images.build_core,
                     images.build_selected_functions, k3s.install,
                     k3s.configure_registry, namespace.install,
                     helm.deploy_control_plane, helm.deploy_function_runtime)
LOADGEN_SEQUENCE  = (loadgen.ensure_running, loadgen.provision_base,
                     loadgen.install_k6, loadgen.run_k6,
                     metrics.prometheus_snapshot, loadtest.write_report, loadgen.down)
```

- `k3s-junit-curl = PROVISION_PRELUDE + (curl, junit) + cleanup + (vm.down)`
- `helm-stack = PROVISION_PRELUDE + (loadtest.install_k6, loadtest.run, autoscaling)`
- `two-vm = azure = proxmox = PROVISION_PRELUDE + CLI + LOADGEN_SEQUENCE + (vm.down)`
  → **one shared recipe**

A new "similar except 2 tasks" scenario = shared fragment ± a couple entries, not
a duplicated file.

**Axis B — *how* tasks execute (connectivity).** The three loadtest scenarios
have the **same** recipe and differ only in SSH connectivity. Because
`RunPlaybook` takes connectivity as parameters, that difference collapses to a
small per-lifecycle **connectivity adapter** that yields `(host, user, key, port)`.

**This spec ships the two bricks** (parametric `RunPlaybook`; documented
fragments). The full collapse (single recipe + connectivity adapters, replacing
the hand-built loadtest `run()` duplication) is **Phase 3**.

## 5. Phasing

- **Phase 1:** authoritative mapping (this doc) + recipe fragments documented +
  `RunPlaybook` task with parametric connectivity and TUI integration.
- **Phase 2:** migrate the loadgen `install_k6` of all 3 loadtest scenarios to
  `RunPlaybook`; update argv-oracle tests; bash `InstallK6` deprecated (kept).
- **Phase 3 (documented, not now):** recipe composition + connectivity adapters →
  collapse near-identical scenarios; evaluate helm/namespace/cleanup via ansible
  `helm` module; address azure's missing provisioning; remove deprecated bash.

## 6. Testing Strategy

- Unit tests for `RunPlaybook`: argv construction (with/without key/port/extra_vars),
  `ANSIBLE_CONFIG` env, non-zero-exit raises, TUI event emission.
- Per-scenario tests: the migrated workflow contains a `RunPlaybook`-backed
  `loadgen.install_k6` with the correct connectivity; task_ids/titles unchanged.
- Update existing argv-oracle tests (e.g. proxmox plan) to expect the
  `ansible-playbook ... install-k6.yml` command.
- Full controlplane Python suite must pass (incl. milestone/wrapper-doc gates).

## 7. Risks

- **Behavioral change:** install moves from on-VM bash to host-side ansible.
  Mitigated: multipass + proxmox-stack already do host-ansible today; azure VMs
  are reachable via public SSH.
- **Azure connectivity:** verify `AzureVmOrchestrator` exposes user + key path for
  the loadgen VM before migrating azure (Phase 2 prerequisite).
- **Proxmox SSH port:** loadgen endpoint (host/port/key) must be resolved the same
  way the stack endpoint is (`_rewrite_ansible` equivalent for the loadgen VM).
- **TUI callback parsing:** task-level sub-steps depend on a stable ansible
  callback format; fall back to live-log streaming if parsing is brittle.
