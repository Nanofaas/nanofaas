# Phase B3c — Fold azure into the unified flow (azure gains provisioning) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route the azure loadtest scenario through the unified `run_loadtest_flow` driver via a new `AzureLoadtestAdapter` + `AzureConnectivity`, so azure GAINS the canonical provisioning prelude + in-prelude function registration it lacks today; retire azure's `_skeleton`. After this, all three loadtest scenarios are thin `(recipe, adapter)` delegators — the unification roadmap is complete.

**Architecture:** Mirror the merged proxmox fold (B3b), simpler: azure has a public host (no NAT, no publish-port), so `AzureConnectivity` is the simplest strategy and `AzureLoadtestAdapter` has no `extra_steps`. The canonical loadtest recipe (`STACK_PRELUDE + LOADTEST_TAIL`) is already azure's recipe — azure's old `run()` simply bypassed it. Routing azure through the driver naturally gives it provisioning + register.

**Tech Stack:** Python 3.12, `uv`, pytest. `controlplane_tool.scenario.loadtest_flow`/`loadtest_adapter`/`connectivity`, `AzureVmOrchestrator` (`connection_host`/`ssh_private_key_path`/`remote_project_dir`).

**This is a deliberate, unvalidatable behavior CHANGE.** azure-vm-loadtest is non-functional today (no stack provisioning). B3c makes it provision like proxmox/two-vm. There are NO azure credentials to validate a real run, so the existing azure tests (which assert the no-provisioning skeleton) are REPLACED by the new canonical behavior, pinned structurally (an azure prelude-argv oracle + updated task_id/title tests). The PR MUST flag azure as structurally-guarded but **real-VM-unvalidated**.

**Spec:** `docs/superpowers/specs/2026-06-07-loadtest-b3-final-collapse-design.md` (§ B3c).

---

## Reference: the template (post-B3b proxmox) and azure's current state

**Template** — `proxmox_vm_loadtest.py` is now a thin delegator: `run()`/`task_ids`/`phase_titles` call `run_loadtest_flow`/`loadtest_flow_task_ids`/`loadtest_flow_phase_titles` with `recipe=self._recipe()` + `adapter=ProxmoxLoadtestAdapter(...)`, plus kept prelude-argv-oracle methods (`_build_prelude_tasks`/`prelude_tasks`) for `test_proxmox_prelude_argv.py`. `ProxmoxLoadtestAdapter` supplies connectivity/special_handler/context_selector/cleanup_on_failure/extra_steps/urls/endpoint.

**azure today** (`azure_vm_loadtest.py`): `run()` does ONLY ensure_stack → ensure_loadgen → shared loadgen body (no `build_command_tasks`, no provisioning, no register). `task_ids = LOADTEST_STATIC_TASK_IDS` (8 ids: ensure_stack, ensure_loadgen, 5 body, destroy — NO provisioning/register). `_skeleton()` builds placeholder tasks for titles. Uses `AzureVmOrchestrator` (public host via `connection_host`, key via `ssh_private_key_path`, `remote_project_dir`; NO `ssh_endpoint`/`publish_port`).

**Canonical recipe** (`recipes.py`): `_LOADTEST_COMPONENT_IDS = STACK_PRELUDE + LOADTEST_TAIL`. The prelude subset (what `_recipe()` must yield, mirroring proxmox) = `STACK_PRELUDE` minus `vm.ensure_running` + `("cli.build_install_dist", "cli.fn_apply_selected")`. i.e. `vm.provision_base, repo.sync_to_vm, registry.ensure_container, images.build_core, images.build_selected_functions, k3s.install, k3s.configure_registry, namespace.install, helm.deploy_control_plane, helm.deploy_function_runtime, cli.build_install_dist, cli.fn_apply_selected`. (The loadgen tail `loadgen.*`/`metrics.*`/`loadtest.write_report`/`vm.down` is built by the driver's body + cleanup, NOT the prelude recipe — same as proxmox.)

---

## File Structure

- **Modify** `tools/controlplane/src/controlplane_tool/scenario/connectivity.py` — add `AzureConnectivity` (public host + key; ansible/rsync rewrite; vm_runner; remote_dir). Mirrors `ProxmoxConnectivity` minus the NAT port.
- **Modify** `tools/controlplane/src/controlplane_tool/scenario/loadtest_adapter.py` — add `AzureLoadtestAdapter`.
- **Modify** `tools/controlplane/src/controlplane_tool/scenario/scenarios/azure_vm_loadtest.py` — thin delegator (`_recipe()`/`_adapter()` + delegate run/task_ids/phase_titles + prelude-argv oracle); retire `_skeleton`.
- **Tests** — `test_loadtest_adapter.py` (azure adapter), a new `tools/controlplane/tests/test_azure_prelude_argv.py` (pin the NEW provisioning prelude argv), and update `test_azure_vm_loadtest_components.py`/`test_azure_vm_loadtest_runner.py` to the canonical task_ids/titles.

---

## Task 1: `AzureConnectivity`

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/connectivity.py`
- Test: `tools/controlplane/tests/test_connectivity.py` (create if absent, else append)

`AzureConnectivity` rewrites host-ops (ansible inventory, repo rsync) onto the azure PUBLIC host + key, runs vm-ops via the azure orchestrator, and supplies `remote_dir`. No NAT port (azure uses default SSH). Mirror `ProxmoxConnectivity` (read it in `connectivity.py`) but drop the `ansible_port`/`port` plumbing.

- [ ] **Step 1: Write the failing test**

```python
# tools/controlplane/tests/test_connectivity.py  (append or create)
from __future__ import annotations

from pathlib import Path
from types import SimpleNamespace

from workflow_tasks.components.operations import RemoteCommandOperation

from controlplane_tool.scenario.connectivity import AzureConnectivity


def _op(argv, operation_id="x"):
    return RemoteCommandOperation(operation_id=operation_id, summary="s", argv=tuple(argv), env={}, execution_target="host")


def test_azure_connectivity_rewrites_ansible_inventory_to_public_host() -> None:
    conn = AzureConnectivity(
        orchestrator=SimpleNamespace(),
        request=SimpleNamespace(user="azureuser"),
        host="20.1.2.3",
        key=Path("/k.pem"),
        repo_root=Path("/repo"),
        remote_dir_value="/home/azureuser/nanofaas",
    )
    op = _op(["ansible-playbook", "-i", "OLD,", "play.yml"])
    out = conn.resolve_host_operation(op)
    assert "20.1.2.3," in out.argv
    assert "--private-key" in out.argv and "/k.pem" in out.argv
    # No NAT port flag for azure:
    assert "ansible_port" not in " ".join(out.argv)


def test_azure_connectivity_remote_dir() -> None:
    conn = AzureConnectivity(
        orchestrator=SimpleNamespace(), request=SimpleNamespace(user="azureuser"),
        host="20.1.2.3", key=None, repo_root=Path("/repo"), remote_dir_value="/home/azureuser/nanofaas",
    )
    assert conn.remote_dir(object()) == "/home/azureuser/nanofaas"
```

- [ ] **Step 2: Run → fail.** `uv run --project tools/controlplane pytest tools/controlplane/tests/test_connectivity.py -q` → ImportError.

- [ ] **Step 3: Implement** in `connectivity.py` (mirror `ProxmoxConnectivity`, drop port):

```python
@dataclass
class AzureConnectivity:
    """Azure: rewrite host-ops onto the public host + key (no NAT port).

    Ansible inventory -> public host + key; repo.sync rsync over host with the key.
    vm-ops run via the azure orchestrator. Constructed with a resolved (or
    placeholder, for display) host/key by the azure plan.
    """

    orchestrator: object
    request: object
    host: str
    key: "Path | None"
    repo_root: Path
    remote_dir_value: str

    def _rewrite_ansible(self, argv: tuple[str, ...]) -> list[str]:
        rewritten = list(argv)
        if "-i" in rewritten:
            rewritten[rewritten.index("-i") + 1] = f"{self.host},"
        if self.key is not None:
            if "--private-key" in rewritten:
                rewritten[rewritten.index("--private-key") + 1] = str(self.key)
            else:
                rewritten.extend(["--private-key", str(self.key)])
        return rewritten

    def _repo_sync_command(self) -> list[str]:
        return repo_rsync_command(
            source=self.repo_root,
            user=self.request.user,
            host=self.host,
            destination=self.remote_dir_value,
            ssh_rsh=repo_sync_ssh_rsh(self.key),
        )

    def resolve_host_operation(self, operation: RemoteCommandOperation) -> RemoteCommandOperation:
        if operation.argv and operation.argv[0] == "ansible-playbook":
            new_argv: list[str] = self._rewrite_ansible(operation.argv)
        elif operation.operation_id == "repo.sync_to_vm":
            new_argv = self._repo_sync_command()
        else:
            return operation
        return RemoteCommandOperation(
            operation_id=operation.operation_id,
            summary=operation.summary,
            argv=tuple(new_argv),
            env=operation.env,
            execution_target=operation.execution_target,
        )

    def vm_runner(self, request: object) -> OrchestratorVmRunner:
        return OrchestratorVmRunner(self.orchestrator, self.request)

    def remote_dir(self, request: object) -> str:
        return self.remote_dir_value
```

NOTE: verify `repo_sync_ssh_rsh` accepts being called with NO port (read its signature in `workflow_tasks.vm.multipass`; proxmox calls `repo_sync_ssh_rsh(self.key, port=self.port)`). If `port` is required, pass the azure default (22) or omit per the signature. Adapt + report.

- [ ] **Step 4: Run → pass.** ruff clean.

- [ ] **Step 5: Commit** `feat(connectivity): add AzureConnectivity (public host, no NAT)`.

---

## Task 2: `AzureLoadtestAdapter`

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/loadtest_adapter.py`
- Test: `tools/controlplane/tests/test_loadtest_adapter.py`

Mirror `ProxmoxLoadtestAdapter` (read it), simpler. READ proxmox's adapter for the exact shape of `connectivity_for`/`prelude_special_handler`/`prelude_context_selector`/`cleanup_on_failure`/urls/endpoint.

`AzureLoadtestAdapter` fields: `runner`, `request`; lazily build `AzureVmOrchestrator(repo_root=runner.paths.workspace_root)`; `stack_request=request.vm`, `loadgen_request=request.loadgen_vm`. `title_suffix = " (Azure)"`.
- `connectivity_for(ctx, *, resolve_host)` → `AzureConnectivity(orchestrator=azure_orch, request=stack_request, host, key, repo_root, remote_dir_value)`. resolve_host=True: `host = azure_orch.connection_host(stack_request)`, `key = azure_orch.ssh_private_key_path(stack_request)`, `remote_dir = azure_orch.remote_project_dir(stack_request)`. resolve_host=False: placeholders `host="<azure-host>"`, `key=None`, `remote_dir=f"/home/{stack_request.user or 'azureuser'}/nanofaas"`.
- `prelude_special_handler(ctx, *, resolve_host=True)` → the `cli.fn_apply_selected`→`CallableTask("functions.register", ...)` substitution (only when `request.scenario in LOADTEST_SCENARIOS`), action calls `RegisterFunctions(control_plane_url=<azure CP url>, specs=[...]).run()`. Azure CP url = `two_vm_control_plane_url(stack_request, host=azure_orch.connection_host(stack_request))` (public host + nodeport — NO publish_port). Use the same `registered={"done":False}` guard pattern as proxmox.
- `prelude_context_selector(ctx, *, resolve_host=True)` → maps `cli.*` → `CliComponentContext` (built from the resolved environment + remote_dir), others → base context (mirror proxmox).
- `register_functions(ctx)` → no-op (in prelude). `emits_step_events()` → True. `extra_steps(phase, ctx)` → []; `extra_step_ids(phase)` → []; `extra_step_titles(phase)` → [].
- `cleanup_on_failure(error)` → teardown loadgen then stack via `azure_orch.teardown` (respect `cleanup_vm`), return error strings.
- `loadgen_install_endpoint(ctx)` → `InstallEndpoint(host=azure_orch.connection_host(loadgen_request), user=loadgen_request.user, private_key=azure_orch.ssh_private_key_path(loadgen_request), port=None)`.
- `loadgen_runner(ctx)` → `OrchestratorVmRunner(azure_orch, loadgen_request)`; `fetcher(ctx)` → `VmFileFetcher(vm=azure_orch, request=loadgen_request)`.
- `control_plane_url(ctx)` → `two_vm_control_plane_url(stack_request, host=ctx.stack_host)`; `prometheus_url(ctx)` → `two_vm_prometheus_url(stack_request, host=ctx.stack_host)`.
- `prepare_loadgen(ctx)` → no-op (body built directly, like proxmox). `create_run_dir()` → `TwoVmLoadtestRunner(repo_root=..., vm=azure_orch)._create_run_dir()`.
- `stack_lifecycle()`/`loadgen_lifecycle()` → `AzureVmAdapter(azure_orch)`.

- [ ] **Step 1: Write failing tests** (with a fake AzureVmOrchestrator): `title_suffix == " (Azure)"`, `emits_step_events() is True`, `extra_step_ids(...) == []`, `connectivity_for(None, resolve_host=False)` is an `AzureConnectivity` with `host == "<azure-host>"`, `loadgen_install_endpoint(ctx).port is None`, `cleanup_on_failure(RuntimeError())` calls teardown for loadgen+stack. Inject the fake orchestrator (constructor param or monkeypatch).

- [ ] **Step 2: Run → fail.**

- [ ] **Step 3: Implement** `AzureLoadtestAdapter` per above.

- [ ] **Step 4: Run → pass.** ruff clean. Confirm no two-vm/proxmox breakage: `uv run --project tools/controlplane pytest tools/controlplane/tests/ -q -k "loadtest or two_vm or proxmox"` → 0 failures.

- [ ] **Step 5: Commit** `feat(loadtest): add AzureLoadtestAdapter`.

---

## Task 3: Route azure through the driver (azure gains provisioning); retire `_skeleton`

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/scenarios/azure_vm_loadtest.py`
- Tests: create `tools/controlplane/tests/test_azure_prelude_argv.py`; update `test_azure_vm_loadtest_components.py`, `test_azure_vm_loadtest_runner.py`

- [ ] **Step 1: baseline** — `uv run --project tools/controlplane pytest tools/controlplane/tests/ -q -k azure` → note current pass set (these will be UPDATED).

- [ ] **Step 2: Rewrite the plan as a thin delegator** mirroring `proxmox_vm_loadtest.py` (read it):
  - `_adapter()` → `AzureLoadtestAdapter(runner=self.runner, request=self.request)`.
  - `_recipe()` → `build_scenario_recipe("azure-vm-loadtest")` rebuilt with `component_ids = STACK_PRELUDE_MINUS_ENSURE + ("cli.build_install_dist", "cli.fn_apply_selected")` — i.e. the canonical prelude minus `vm.ensure_running` and minus the loadgen tail (the driver builds ensure + body + cleanup). Concretely filter `_LOADTEST_COMPONENT_IDS` to the components from `vm.provision_base` through `cli.fn_apply_selected` (drop `vm.ensure_running` and everything from `loadgen.ensure_running` onward). Verify against proxmox's `_recipe()` filter approach.
  - `run()` → delegate to `run_loadtest_flow(runner, request, setup=build_setup(...), recipe=self._recipe(), adapter=self._adapter(), event_listener=event_listener)`.
  - `task_ids`/`phase_titles` → delegate to `loadtest_flow_task_ids`/`loadtest_flow_phase_titles`.
  - Add a prelude-argv-oracle (`_build_prelude_tasks`/`prelude_tasks`) mirroring proxmox's kept methods, using `AzureConnectivity` + the adapter's special_handler/context_selector, so a new azure argv golden can pin the NEW provisioning prelude.
  - DELETE `_skeleton`. Remove now-unused imports (`InstallK6`/`RunK6`/`FetchVmResults`/`CapturePrometheusSnapshot`/`WriteK6Report`/`make_loadtest_k6_config`/`build_loadgen_body_tasks`/`LoadgenBodyInputs`/`workflow_step`/`Workflow`/`OrchestratorVmRunner`/`VmFileFetcher`/`HttpPrometheusClient`/the two_vm_* helpers used only by the old run()).

- [ ] **Step 3: New azure prelude-argv golden** — create `test_azure_prelude_argv.py` mirroring `test_proxmox_prelude_argv.py` (read it): build the plan with a fake/real-ish azure runner, call `plan.prelude_tasks` (or `_build_prelude_tasks(..., resolve_host=False/True)`), and assert the ordered prelude task_ids include the provisioning + `functions.register` components, and (for a couple of representative tasks) the argv shape (ansible inventory rewritten to the azure host, repo rsync to the host). This PINS the new provisioning behavior structurally (the only guard, since no real azure).

- [ ] **Step 4: Update the existing azure tests to the canonical task_ids/titles.** `test_azure_vm_loadtest_components.py` / `test_azure_vm_loadtest_runner.py` currently assert the no-provisioning skeleton (8 ids). Update them to assert the canonical ids the driver now produces: `vm.stack.ensure_running` + the prelude provisioning ids + `functions.register` + `vm.loadgen.ensure_running` + 5 loadgen body ids + `vm.loadgen.destroy` (+ `vm.stack.destroy` if azure cleanup destroys both — match what the driver+adapter produce; azure today destroyed only loadgen, but the unified cleanup destroys loadgen+stack — DECIDE: the unified `_destroy_tasks` destroys both; that's the new canonical, update the test accordingly and note the behavior change). Use the real recipe derivation (like two-vm's de-circularized task-id test) — do NOT stub the id-deriving path. If a source-inspection guard exists (`..._uses_runplaybook_not_bash`), retarget it to the driver module (B2/B3a/B3b style), preserving `InstallK6(`-absent.

- [ ] **Step 5: Verify** — `uv run --project tools/controlplane pytest tools/controlplane/tests/ -q -k "azure or loadtest"` → PASS. ruff clean on azure_vm_loadtest.py.

- [ ] **Step 6: Commit** `refactor(azure): route run() through run_loadtest_flow; azure gains provisioning; retire _skeleton`.

---

## Task 4: Full-suite verify + sweep

- [ ] `uv run --project tools/controlplane pytest tools/controlplane/tests -q` → PASS.
- [ ] two-vm + proxmox UNTOUCHED: `git diff main --stat -- tools/controlplane/src/controlplane_tool/scenario/scenarios/two_vm_loadtest.py tools/controlplane/src/controlplane_tool/scenario/scenarios/proxmox_vm_loadtest.py` → EMPTY.
- [ ] `LOADTEST_STATIC_TASK_IDS` — if azure was its only consumer, delete it (grep); else leave.
- [ ] Confirm azure file shrank / is a thin delegator: `wc -l azure_vm_loadtest.py`.
- [ ] ruff clean on all touched files.
- [ ] Commit any cleanup.

---

## Real-VM Validation (post-merge — NOT possible for azure)

- [ ] azure: NO credentials/hardware — ships structurally-guarded (prelude-argv oracle + canonical task_id tests), **flagged real-VM-unvalidated in the PR**. The provisioning is NEW behavior "expected-to-work-by-construction" (it reuses the exact recipe/components that two-vm runs on real multipass).
- [ ] two-vm/proxmox: unaffected.

---

## Self-Review Notes

- **Spec coverage:** azure gains canonical provisioning + register (§ B3c) → Tasks 1-3; thin `(recipe, adapter)` delegation matching proxmox → Task 3; remove `_skeleton` → Task 3.
- **No characterization-to-preserve:** unlike B3b, azure's current behavior is the *gap* being fixed, so the existing skeleton tests are intentionally REPLACED (not preserved); the new prelude-argv oracle + canonical task-id tests are the structural guard.
- **two-vm + proxmox byte-identity:** Tasks 2/4 gate via untouched-diff + their goldens staying green (the driver/adapter additions for azure must not change multipass/proxmox paths — `AzureLoadtestAdapter` is additive; `AzureConnectivity` is new).
- **Behavior changes to flag in the PR:** (1) azure gains provisioning/register (was non-functional); (2) azure cleanup now destroys BOTH VMs (was loadgen-only) — confirm against the unified `_destroy_tasks` and state it; (3) azure now emits `ScenarioStepEvent`s (emits_step_events=True) like proxmox.
- **Type consistency:** `AzureLoadtestAdapter` implements the same `LoadtestConnectivityAdapter` members as Multipass/Proxmox (`connectivity_for`/`prelude_special_handler`/`prelude_context_selector`/`register_functions`/`emits_step_events`/`cleanup_on_failure`/`extra_steps`/`extra_step_ids`/`extra_step_titles`/`loadgen_install_endpoint`/`loadgen_runner`/`fetcher`/`control_plane_url`/`prometheus_url`/`prepare_loadgen`/`create_run_dir`/`stack_lifecycle`/`loadgen_lifecycle`/`title_suffix`), consumed unchanged by the driver.
