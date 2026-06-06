# Connectivity Strategy — Phase B1b (Proxmox) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **EXECUTION GATE:** Do not execute this plan until Phase B1a has been validated on real multipass VMs (`two-vm-loadtest` end to end). B1b builds on B1a's seam; the multipass foundation must be confirmed first. This phase itself is **not** real-VM validatable (no Proxmox available); its safety net is an **argv golden** pinning the exact commands proxmox produces today.

**Goal:** Route the proxmox loadtest prelude through the unified `build_command_tasks` + a new `ProxmoxConnectivity`, retiring proxmox's bespoke `_rewrite_ansible` / `_repo_sync_command` inline machinery, while producing **byte-for-byte identical** prelude command argv.

**Architecture:** Extend `ConnectivityStrategy` with `remote_dir(request)` (so `build_command_tasks` no longer hard-codes the multipass orchestrator's remote dir). Add `ProxmoxConnectivity` whose `resolve_host_operation` rewrites ansible inventory/port/key and rebuilds the `repo.sync_to_vm` rsync over the NAT SSH endpoint. Reimplement `ProxmoxVmLoadtestPlan._build_prelude_tasks` to delegate to `build_command_tasks(connectivity=ProxmoxConnectivity(...), special_handler=<functions.register substitution>, context_selector=<cli context>)`. An argv golden (built with a fake orchestrator) guards behavior preservation.

**Tech Stack:** Python 3.12, `pytest`, `uv`, `workflow_tasks`, `controlplane_tool.scenario`.

**Spec:** `docs/superpowers/specs/2026-06-05-connectivity-strategy-b1-design.md` (Phase B1b).

**Scope:** proxmox only. Azure remains untouched/deferred. `build_command_tasks`'s `remote_dir` change is behavior-preserving for the multipass callers.

---

## File Structure

- `tools/controlplane/src/controlplane_tool/scenario/connectivity.py` — add `remote_dir` to `ConnectivityStrategy` + `MultipassConnectivity`; add `ProxmoxConnectivity`.
- `tools/controlplane/src/controlplane_tool/scenario/scenarios/_workflow_assembly.py` — `build_command_tasks` sources `remote_dir` from the strategy.
- `tools/controlplane/src/controlplane_tool/scenario/scenarios/proxmox_vm_loadtest.py` — reimplement `_build_prelude_tasks` to delegate; delete the inline `_rewrite_ansible` / `_repo_sync_command` / `_host_task`.
- `tools/controlplane/tests/test_connectivity.py` — add `ProxmoxConnectivity` unit tests.
- `tools/controlplane/tests/test_proxmox_prelude_argv.py` (new) — argv golden.

---

## Task 1: `remote_dir` on the strategy (behavior-preserving)

`build_command_tasks` currently computes `remote_dir = vm_orch.remote_project_dir(vm_request)` where `vm_orch = runner.vm` (always multipass). Proxmox needs its own remote dir. Move remote-dir sourcing onto the strategy.

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/connectivity.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario/scenarios/_workflow_assembly.py`
- Test: `tools/controlplane/tests/test_connectivity.py`

- [ ] **Step 1: Write the failing test**

Append to `tools/controlplane/tests/test_connectivity.py`:

```python
def test_multipass_connectivity_remote_dir_matches_orchestrator() -> None:
    runner = _runner()
    conn = MultipassConnectivity(runner=runner, request=_request())
    vm_request = VmRequest(lifecycle="multipass", name="nanofaas-e2e")

    assert conn.remote_dir(vm_request) == runner.vm.remote_project_dir(vm_request)
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd /Users/micheleciavotta/Downloads/mcFaas && uv run --project tools/controlplane pytest tools/controlplane/tests/test_connectivity.py::test_multipass_connectivity_remote_dir_matches_orchestrator -v --no-cov`
Expected: FAIL with `AttributeError: 'MultipassConnectivity' object has no attribute 'remote_dir'`.

- [ ] **Step 3: Add `remote_dir` to the Protocol and `MultipassConnectivity`**

In `connectivity.py`, add to the `ConnectivityStrategy` Protocol:

```python
    def remote_dir(self, request: object) -> str: ...
```

and to `MultipassConnectivity`:

```python
    def remote_dir(self, request: object) -> str:
        return self.runner.vm.remote_project_dir(request)
```

- [ ] **Step 4: Use it in `build_command_tasks`**

In `_workflow_assembly.py` `build_command_tasks`, replace:

```python
    vm_orch = runner.vm
    remote_dir = vm_orch.remote_project_dir(vm_request)

    if connectivity is None:
        connectivity = MultipassConnectivity(runner=runner, request=request)
```

with:

```python
    if connectivity is None:
        connectivity = MultipassConnectivity(runner=runner, request=request)

    remote_dir = connectivity.remote_dir(vm_request)
```

(Remove the now-unused `vm_orch` local. The `VmOrchestrator` import stays — `host_command_task_from_step` still references types from it if present; if `vm_orch` was the only use of `runner.vm` here, that's fine.)

- [ ] **Step 5: Run the connectivity tests + the argv goldens (behavior unchanged)**

Run: `cd /Users/micheleciavotta/Downloads/mcFaas && uv run --project tools/controlplane pytest tools/controlplane/tests/test_connectivity.py tools/controlplane/tests/test_two_vm_stack_prelude_argv.py tools/controlplane/tests/test_cli_stack_workflow.py -v --no-cov`
Expected: PASS (multipass `remote_dir` returns the same value, so produced vm-op `remote_dir` is unchanged).

- [ ] **Step 6: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario/connectivity.py tools/controlplane/src/controlplane_tool/scenario/scenarios/_workflow_assembly.py tools/controlplane/tests/test_connectivity.py
git commit -m "feat(connectivity): source remote_dir from ConnectivityStrategy"
```

---

## Task 2: Proxmox prelude argv golden (safety net)

Pin the exact argv the current `_build_prelude_tasks` produces, using a fake orchestrator with a deterministic SSH endpoint. This passes NOW and must stay green through the refactor — it is the only guard since there is no real Proxmox.

**Files:**
- Create: `tools/controlplane/tests/test_proxmox_prelude_argv.py`

- [ ] **Step 1: Write the golden (passes now)**

Create `tools/controlplane/tests/test_proxmox_prelude_argv.py`:

```python
from __future__ import annotations

from pathlib import Path

from workflow_tasks.shell import RecordingShell

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.e2e.e2e_runner import E2eRunner
from controlplane_tool.infra.vm.vm_models import VmRequest
from controlplane_tool.scenario.catalog import resolve_scenario
from controlplane_tool.scenario.scenarios import proxmox_vm_loadtest as proxmox_plan


class FakeProxmoxOrch:
    """Deterministic proxmox orchestrator stand-in for argv characterization."""

    def __init__(self, repo_root):
        self.repo_root = repo_root

    def remote_project_dir(self, request):
        return f"/home/{request.user or 'ubuntu'}/nanofaas"

    def ssh_endpoint(self, request):
        return "203.0.113.7", 2222

    def ssh_private_key_path(self, request):
        return Path("/keys/proxmox_ed25519")


def _plan():
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell())
    request = E2eRequest(
        scenario="proxmox-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="proxmox", name="proxmox-stack", user="ubuntu"),
        loadgen_vm=VmRequest(lifecycle="proxmox", name="proxmox-loadgen", user="ubuntu"),
    )
    return proxmox_plan.ProxmoxVmLoadtestPlan(
        scenario=resolve_scenario("proxmox-vm-loadtest"),
        request=request,
        steps=[],
        runner=runner,
    )


def _prelude_argv() -> dict[str, list[str]]:
    plan = _plan()
    orch = FakeProxmoxOrch(Path("/repo"))
    stack_request = plan._requests()[0]  # noqa: SLF001
    tasks = plan._build_prelude_tasks(orch, stack_request, resolve_host=True)  # noqa: SLF001
    return {t.task_id: list(t.spec.argv) for t in tasks if getattr(t, "spec", None) is not None}


def test_proxmox_prelude_ansible_targets_nat_endpoint() -> None:
    by_id = _prelude_argv()
    provision = by_id["vm.provision_base"]
    assert provision[0] == "ansible-playbook"
    assert "203.0.113.7," in provision           # NAT host as inventory
    assert "ansible_port=2222" in provision       # mapped SSH port
    assert "/keys/proxmox_ed25519" in provision   # proxmox key
    assert provision[-1].endswith("provision-base.yml")


def test_proxmox_prelude_repo_sync_uses_nat_rsync() -> None:
    by_id = _prelude_argv()
    rsync = by_id["repo.sync_to_vm"]
    assert rsync[0] == "rsync"
    assert any("203.0.113.7" in arg for arg in rsync)
    assert any("2222" in arg for arg in rsync)


def test_proxmox_prelude_registers_functions_via_rest_not_cli() -> None:
    by_id = _prelude_argv()
    # functions.register is a CallableTask (no spec.argv) — it must be present as a task_id,
    # and the cli.fn_apply_selected.* CLI commands must NOT appear.
    plan = _plan()
    orch = FakeProxmoxOrch(Path("/repo"))
    tasks = plan._build_prelude_tasks(orch, plan._requests()[0], resolve_host=True)  # noqa: SLF001
    ids = [t.task_id for t in tasks]
    assert "functions.register" in ids
    assert not any(i.startswith("cli.fn_apply_selected") for i in ids)
```

- [ ] **Step 2: Run it to verify it PASSES now (characterization)**

Run: `cd /Users/micheleciavotta/Downloads/mcFaas && uv run --project tools/controlplane pytest tools/controlplane/tests/test_proxmox_prelude_argv.py -v --no-cov`
Expected: PASS.

> If `_requests()` / `_build_prelude_tasks` signatures differ, inspect `proxmox_vm_loadtest.py` and adjust. The contract: the resolved proxmox prelude rewrites ansible to the NAT host+port+key, rsyncs over that endpoint, and registers functions via REST (`functions.register`), not CLI.

- [ ] **Step 3: Commit**

```bash
git add tools/controlplane/tests/test_proxmox_prelude_argv.py
git commit -m "test(proxmox): golden argv for the loadtest prelude before unification"
```

---

## Task 3: `ProxmoxConnectivity`

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/connectivity.py`
- Test: `tools/controlplane/tests/test_connectivity.py`

Context: the rewrite logic is moved verbatim from proxmox's current inline `_rewrite_ansible` / `_repo_sync_command` (proxmox_vm_loadtest.py:270-289). `repo_rsync_command` / `repo_sync_ssh_rsh` come from `workflow_tasks.vm.multipass` (same imports proxmox uses today).

- [ ] **Step 1: Write the failing test**

Append to `tools/controlplane/tests/test_connectivity.py`:

```python
def test_proxmox_connectivity_rewrites_ansible_to_nat_endpoint() -> None:
    from controlplane_tool.scenario.connectivity import ProxmoxConnectivity

    class _Orch:
        def remote_project_dir(self, request):
            return "/home/ubuntu/nanofaas"

    conn = ProxmoxConnectivity(
        orchestrator=_Orch(),
        request=VmRequest(lifecycle="proxmox", name="proxmox-stack", user="ubuntu"),
        host="203.0.113.7",
        port=2222,
        key=Path("/keys/proxmox_ed25519"),
        repo_root=Path("/repo"),
        remote_dir_value="/home/ubuntu/nanofaas",
    )
    op = RemoteCommandOperation(
        operation_id="vm.provision_base",
        summary="Provision",
        argv=("ansible-playbook", "-i", "<multipass-ip:proxmox-stack>,", "provision-base.yml"),
    )

    out = conn.resolve_host_operation(op)

    assert "203.0.113.7," in out.argv
    assert "ansible_port=2222" in out.argv
    assert "/keys/proxmox_ed25519" in out.argv
    assert "<multipass-ip:proxmox-stack>," not in out.argv


def test_proxmox_connectivity_rewrites_repo_sync_to_rsync() -> None:
    from controlplane_tool.scenario.connectivity import ProxmoxConnectivity

    class _Orch:
        def remote_project_dir(self, request):
            return "/home/ubuntu/nanofaas"

    conn = ProxmoxConnectivity(
        orchestrator=_Orch(),
        request=VmRequest(lifecycle="proxmox", name="proxmox-stack", user="ubuntu"),
        host="203.0.113.7",
        port=2222,
        key=Path("/keys/proxmox_ed25519"),
        repo_root=Path("/repo"),
        remote_dir_value="/home/ubuntu/nanofaas",
    )
    op = RemoteCommandOperation(
        operation_id="repo.sync_to_vm",
        summary="Sync",
        argv=("rsync", "placeholder"),
    )

    out = conn.resolve_host_operation(op)

    assert out.argv[0] == "rsync"
    assert any("203.0.113.7" in arg for arg in out.argv)
```

Add `from pathlib import Path` to the test imports if not present.

- [ ] **Step 2: Run it to verify it fails**

Run: `cd /Users/micheleciavotta/Downloads/mcFaas && uv run --project tools/controlplane pytest tools/controlplane/tests/test_connectivity.py -k proxmox_connectivity -v --no-cov`
Expected: FAIL with `ImportError: cannot import name 'ProxmoxConnectivity'`.

- [ ] **Step 3: Implement `ProxmoxConnectivity`**

Append to `connectivity.py` (add imports `from pathlib import Path` and
`from workflow_tasks.vm.multipass import repo_rsync_command, repo_sync_ssh_rsh` at the top):

```python
@dataclass
class ProxmoxConnectivity:
    """Proxmox: rewrite host-ops onto the published NAT SSH endpoint.

    Ansible inventory -> NAT host + ansible_port + key; repo.sync rsync rebuilt over
    host:port with the key. vm-ops run via the proxmox orchestrator. Constructed with
    a resolved (or placeholder, for display) endpoint by the proxmox plan.
    """

    orchestrator: object
    request: object
    host: str
    port: int
    key: "Path | None"
    repo_root: Path
    remote_dir_value: str

    def _rewrite_ansible(self, argv: tuple[str, ...]) -> list[str]:
        rewritten = list(argv)
        if "-i" in rewritten:
            rewritten[rewritten.index("-i") + 1] = f"{self.host},"
        rewritten.extend(["-e", f"ansible_port={self.port}"])
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
            ssh_rsh=repo_sync_ssh_rsh(self.key, port=self.port),
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
        return OrchestratorVmRunner(self.orchestrator, request)

    def remote_dir(self, request: object) -> str:
        return self.remote_dir_value
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd /Users/micheleciavotta/Downloads/mcFaas && uv run --project tools/controlplane pytest tools/controlplane/tests/test_connectivity.py -v --no-cov`
Expected: PASS (all connectivity tests).

- [ ] **Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario/connectivity.py tools/controlplane/tests/test_connectivity.py
git commit -m "feat(connectivity): add ProxmoxConnectivity (NAT endpoint rewrite)"
```

---

## Task 4: Route the proxmox prelude through `build_command_tasks`

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/scenarios/proxmox_vm_loadtest.py` (`_build_prelude_tasks`, lines ~223-342)
- Test: `tools/controlplane/tests/test_proxmox_prelude_argv.py` (must stay green) + full suite

- [ ] **Step 1: Reimplement `_build_prelude_tasks` to delegate**

Replace the body of `_build_prelude_tasks` (everything after the docstring, lines ~235-342) with:

```python
        repo_root = self.runner.paths.workspace_root
        request = self.request

        context = resolve_scenario_environment(
            repo_root, request, manifest_root=self.runner.manifest_root
        )

        if resolve_host:
            remote_dir = proxmox_orch.remote_project_dir(stack_request)
            host, port = proxmox_orch.ssh_endpoint(stack_request)
            key = proxmox_orch.ssh_private_key_path(stack_request)
        else:
            remote_dir = f"/home/{stack_request.user or 'ubuntu'}/nanofaas"
            host, port = "<proxmox-host>", 0
            key = None

        cli_context = CliComponentContext(
            repo_root=Path(remote_dir),
            release=cast(str, context.release),
            namespace=cast(str, context.namespace),
            local_registry=context.local_registry,
            resolved_scenario=context.resolved_scenario,
            control_plane_endpoint=None,
        )

        # Recipe = the proxmox prelude minus vm.ensure_running (run() does that separately).
        prelude_components = tuple(
            cid for cid in _PROXMOX_LOADTEST_PRELUDE_COMPONENTS if cid != "vm.ensure_running"
        )
        recipe = build_scenario_recipe("proxmox-vm-loadtest")
        recipe = recipe.__class__(
            name=recipe.name,
            component_ids=prelude_components,
            requires_managed_vm=recipe.requires_managed_vm,
        )

        connectivity = ProxmoxConnectivity(
            orchestrator=proxmox_orch,
            request=stack_request,
            host=host,
            port=port,
            key=key,
            repo_root=repo_root,
            remote_dir_value=remote_dir,
        )

        # _Setup carries only context + vm_request for build_command_tasks; its lifecycle
        # field is unused by build_command_tasks, so the default (multipass) setup is fine.
        setup = build_setup(self.runner, request)

        registered = {"done": False}

        def special_handler(operation):
            if operation.operation_id.startswith("cli.fn_apply_selected") and request.scenario in LOADTEST_SCENARIOS:
                if not registered["done"]:
                    registered["done"] = True
                    return CallableTask(
                        task_id="functions.register",
                        title="Register selected functions via REST API",
                        action=self._register_functions_action(proxmox_orch, stack_request, context),
                    )
                return HANDLED
            return None

        def context_selector(component):
            return cli_context if component.component_id.startswith("cli.") else context

        return build_command_tasks(
            self.runner,
            request,
            setup,
            recipe,
            special_handler=special_handler,
            context_selector=context_selector,
            connectivity=connectivity,
            resolve_host=True,
        )
```

- [ ] **Step 2: Update imports / delete dead helpers**

In `proxmox_vm_loadtest.py`:
- Add to the `_workflow_assembly` import block: `build_command_tasks`, `build_setup`, `HANDLED` (and keep `CallableTask`, `_SUMMARY_OVERRIDES` if still used elsewhere — `CallableTask` is used above).
- Add: `from controlplane_tool.scenario.connectivity import ProxmoxConnectivity`.
- The old inline `_rewrite_ansible`, `_repo_sync_command`, `_host_task`, and the `host_executor`/`vm_executor` locals are now gone (they were inside the replaced body). If `repo_rsync_command` / `repo_sync_ssh_rsh` / `OrchestratorVmRunner` / `HostCommandTaskExecutor` / `VmCommandTaskExecutor` / `CommandTask` / `CommandTaskSpec` imports are now unused in this file, remove them. Verify with: `cd /Users/micheleciavotta/Downloads/mcFaas && uv run --project tools/controlplane ruff check tools/controlplane/src/controlplane_tool/scenario/scenarios/proxmox_vm_loadtest.py` (or `python -c "import ast..."`); remove only imports ruff flags as unused.

- [ ] **Step 3: Run the proxmox argv golden (must stay green)**

Run: `cd /Users/micheleciavotta/Downloads/mcFaas && uv run --project tools/controlplane pytest tools/controlplane/tests/test_proxmox_prelude_argv.py -v --no-cov`
Expected: PASS — the unified path produces identical argv (NAT host+port+key ansible, NAT rsync, functions.register via REST).

- [ ] **Step 4: Run the proxmox plan suite**

Run: `cd /Users/micheleciavotta/Downloads/mcFaas && uv run --project tools/controlplane pytest tools/controlplane/tests/test_proxmox_vm_loadtest_plan.py tools/controlplane/tests/test_proxmox_prelude_workflow.py tools/controlplane/tests/test_connectivity.py -v --no-cov`
Expected: PASS (task_ids/ordering/tail-events still hold; `_build_prelude_tasks` produces the same tasks).

> The existing `test_proxmox_vm_loadtest_tail_events_start_after_prelude` monkeypatches `_build_prelude_tasks` to a fake, so it is unaffected. If any task_id/ordering oracle in `test_proxmox_vm_loadtest_plan.py` fails, the unified path dropped or reordered a step — STOP and report BLOCKED with the diff; do not edit the oracle to force green.

- [ ] **Step 5: Run the full controlplane suite**

Run: `cd /Users/micheleciavotta/Downloads/mcFaas && uv run --project tools/controlplane pytest tools/controlplane/tests -q`
Expected: PASS (1134+ passed).

- [ ] **Step 6: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario/scenarios/proxmox_vm_loadtest.py
git commit -m "refactor(proxmox): route loadtest prelude via build_command_tasks + ProxmoxConnectivity"
```

---

## Self-Review

- **Spec coverage (B1 §3.2/§3.3 B1b):** `ProxmoxConnectivity` extracts `_rewrite_ansible` + `_repo_sync_command` (Task 3); `build_command_tasks` sources `remote_dir` from the strategy so proxmox's remote dir is honored (Task 1); `_build_prelude_tasks` delegates to `build_command_tasks` with the proxmox connectivity + a `special_handler` for `functions.register` + a `context_selector` for `cli.*` (Task 4), retiring the bespoke inline machinery; argv golden guards behavior preservation (Task 2). Azure untouched.
- **Placeholder scan:** no TBD/TODO; full code for `ProxmoxConnectivity` and the reimplemented `_build_prelude_tasks` provided; conditional notes (`_requests()`/`_build_prelude_tasks` signatures; unused-import cleanup) give concrete contracts.
- **Type consistency:** `ConnectivityStrategy` now has `resolve_host_operation`, `vm_runner`, `remote_dir` — all three implemented by both `MultipassConnectivity` and `ProxmoxConnectivity`; `build_command_tasks` calls `connectivity.remote_dir(vm_request)` and `connectivity.vm_runner(vm_request)` (matches B1a's existing `connectivity` param). `ProxmoxConnectivity(orchestrator, request, host, port, key, repo_root, remote_dir_value)` constructor used identically in Task 3 tests and the Task 4 call site. `special_handler(operation)` keeps the existing single-arg signature (no change to cli-stack/helm/k3s callers).
- **Behavior preservation:** the rewrite logic is moved verbatim; the recipe is the same minus `vm.ensure_running` (which the old loop skipped); `functions.register` substitution + `cli_context` reproduce the old special-cases; the argv golden + the proxmox plan oracle + the full suite are the safety net (no real Proxmox).
