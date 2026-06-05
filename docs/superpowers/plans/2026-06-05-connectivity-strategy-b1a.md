# Connectivity Strategy — Phase B1a (Multipass) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce a pluggable `ConnectivityStrategy` seam in `build_command_tasks` and provide `MultipassConnectivity` extracting the current multipass behavior, so the produced `CommandTask` argv for every existing scenario is byte-for-byte unchanged (behavior-preserving).

**Architecture:** Move the host-operation resolution + vm-runner wiring out of `build_command_tasks` into a `ConnectivityStrategy` Protocol with a `MultipassConnectivity` implementation that delegates to the existing `resolve_host_operation` (multipass IP placeholder resolution) and `OrchestratorVmRunner`. `build_command_tasks` gains a `connectivity=None` parameter defaulting to `MultipassConnectivity`, so all current callers (k3s-junit-curl, helm-stack, cli-stack, two-vm stack prelude) are unchanged. Argv-golden + existing pinned-argv tests are the safety net.

**Tech Stack:** Python 3.12, `pytest`, `uv`, `workflow_tasks`, `controlplane_tool.scenario`.

**Spec:** `docs/superpowers/specs/2026-06-05-connectivity-strategy-b1-design.md` (Phase B1a). Note: the spec listed a 3-method interface; this plan uses **2 methods** — `resolve_host_operation` already covers both ansible and rsync for multipass (both carry `<multipass-ip:NAME>` placeholders), and `repo_sync_command` is only needed by proxmox in B1b.

**Scope:** ONLY introduce the seam + multipass impl, behavior-preserving. Proxmox (B1b) and azure are out of scope. No scenario `run()` logic changes.

---

## File Structure

- `tools/controlplane/src/controlplane_tool/scenario/connectivity.py` (new) — `ConnectivityStrategy` Protocol, `MultipassConnectivity`, and the moved `resolve_host_operation`.
- `tools/controlplane/src/controlplane_tool/scenario/scenarios/_workflow_assembly.py` — re-export `resolve_host_operation` from `connectivity`; refactor `build_command_tasks` to use a `ConnectivityStrategy`.
- `tools/controlplane/tests/test_connectivity.py` (new) — unit tests for `MultipassConnectivity`.
- `tools/controlplane/tests/test_two_vm_stack_prelude_argv.py` (new) — argv golden for the two-vm stack prelude (safety net for the refactor).

---

## Task 1: `ConnectivityStrategy` + `MultipassConnectivity`

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/scenario/connectivity.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario/scenarios/_workflow_assembly.py`
- Test: `tools/controlplane/tests/test_connectivity.py`

Context: the current `resolve_host_operation` lives in `_workflow_assembly.py` (lines ~124-143):

```python
def resolve_host_operation(
    operation: RemoteCommandOperation,
    *,
    resolver: CommandResolver,
    request: E2eRequest,
    vm: VmOrchestrator,
    ip_cache: dict[str, str],
) -> RemoteCommandOperation:
    """Substitute <multipass-ip:NAME> placeholders in a host operation's argv/env."""
    argv = resolver._resolve_command(list(operation.argv), request.vm, ip_cache, vm)  # noqa: SLF001
    env = resolver._resolve_env(dict(operation.env), request.vm, ip_cache, vm)  # noqa: SLF001
    return RemoteCommandOperation(
        operation_id=operation.operation_id,
        summary=operation.summary,
        argv=tuple(argv),
        env=env,
        execution_target=operation.execution_target,
    )
```

- [ ] **Step 1: Write the failing test**

Create `tools/controlplane/tests/test_connectivity.py`:

```python
from __future__ import annotations

from pathlib import Path

from workflow_tasks.components.operations import RemoteCommandOperation
from workflow_tasks.shell import RecordingShell

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.e2e.e2e_runner import E2eRunner
from controlplane_tool.infra.vm.vm_models import VmRequest
from controlplane_tool.scenario.connectivity import MultipassConnectivity


def _runner() -> E2eRunner:
    return E2eRunner(
        repo_root=Path("/repo"),
        shell=RecordingShell(),
        host_resolver=lambda _request: "10.0.0.9",
    )


def _request() -> E2eRequest:
    return E2eRequest(
        scenario="two-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
    )


def test_multipass_connectivity_resolves_host_placeholder() -> None:
    conn = MultipassConnectivity(runner=_runner(), request=_request())
    op = RemoteCommandOperation(
        operation_id="vm.provision_base",
        summary="Provision",
        argv=("ansible-playbook", "-i", "<multipass-ip:nanofaas-e2e>,", "provision-base.yml"),
    )

    resolved = conn.resolve_host_operation(op)

    assert "10.0.0.9," in resolved.argv
    assert "<multipass-ip:nanofaas-e2e>," not in resolved.argv
    assert resolved.operation_id == "vm.provision_base"


def test_multipass_connectivity_vm_runner_wraps_orchestrator() -> None:
    runner = _runner()
    conn = MultipassConnectivity(runner=runner, request=_request())

    vm_runner = conn.vm_runner(VmRequest(lifecycle="multipass", name="nanofaas-e2e"))

    # OrchestratorVmRunner exposes run_vm_command (the VmCommandRunner protocol).
    assert hasattr(vm_runner, "run_vm_command")
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /Users/micheleciavotta/Downloads/mcFaas && uv run --project tools/controlplane pytest tools/controlplane/tests/test_connectivity.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'controlplane_tool.scenario.connectivity'`.

- [ ] **Step 3: Create `connectivity.py` (move `resolve_host_operation` here + add the strategy)**

Create `tools/controlplane/src/controlplane_tool/scenario/connectivity.py`:

```python
"""Per-lifecycle connectivity for scenario command execution.

A ConnectivityStrategy supplies the two things that differ between VM lifecycles
when turning a recipe into honest CommandTasks:
  - resolve_host_operation: rewrite a host operation's argv/env (ansible inventory,
    rsync endpoint) to target the VM's SSH endpoint for this lifecycle.
  - vm_runner: an OrchestratorVmRunner wrapping this lifecycle's orchestrator,
    used for vm-target operations (helm/docker/gradlew/cli).
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Protocol

from workflow_tasks.components.operations import RemoteCommandOperation
from workflow_tasks.vm.orchestrator import VmOrchestrator
from workflow_tasks.vm.runners import OrchestratorVmRunner

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.scenario.command_resolver import CommandResolver

if TYPE_CHECKING:
    from controlplane_tool.e2e.e2e_runner import E2eRunner


def resolve_host_operation(
    operation: RemoteCommandOperation,
    *,
    resolver: CommandResolver,
    request: E2eRequest,
    vm: VmOrchestrator,
    ip_cache: dict[str, str],
) -> RemoteCommandOperation:
    """Substitute <multipass-ip:NAME> placeholders in a host operation's argv/env."""
    argv = resolver._resolve_command(list(operation.argv), request.vm, ip_cache, vm)  # noqa: SLF001
    env = resolver._resolve_env(dict(operation.env), request.vm, ip_cache, vm)  # noqa: SLF001
    return RemoteCommandOperation(
        operation_id=operation.operation_id,
        summary=operation.summary,
        argv=tuple(argv),
        env=env,
        execution_target=operation.execution_target,
    )


class ConnectivityStrategy(Protocol):
    def resolve_host_operation(self, operation: RemoteCommandOperation) -> RemoteCommandOperation: ...
    def vm_runner(self, request: object) -> OrchestratorVmRunner: ...


@dataclass
class MultipassConnectivity:
    """Current behavior: multipass IP placeholder resolution + multipass orchestrator."""

    runner: "E2eRunner"
    request: E2eRequest
    _ip_cache: dict[str, str] = field(default_factory=dict, init=False, repr=False)

    def resolve_host_operation(self, operation: RemoteCommandOperation) -> RemoteCommandOperation:
        resolver = CommandResolver(host_resolver=self.runner._host_resolver)  # noqa: SLF001
        return resolve_host_operation(
            operation,
            resolver=resolver,
            request=self.request,
            vm=self.runner.vm,
            ip_cache=self._ip_cache,
        )

    def vm_runner(self, request: object) -> OrchestratorVmRunner:
        return OrchestratorVmRunner(self.runner.vm, request)
```

- [ ] **Step 4: Re-export from `_workflow_assembly.py` and delete its local copy**

In `tools/controlplane/src/controlplane_tool/scenario/scenarios/_workflow_assembly.py`, DELETE the local `def resolve_host_operation(...)` definition (lines ~124-143) and add, near the other imports at the top:

```python
from controlplane_tool.scenario.connectivity import (
    ConnectivityStrategy,
    MultipassConnectivity,
    resolve_host_operation,
)
```

(The re-export keeps `from ..._workflow_assembly import resolve_host_operation` working for any existing importer.)

- [ ] **Step 5: Run the unit tests + confirm no import breakage**

Run: `cd /Users/micheleciavotta/Downloads/mcFaas && uv run --project tools/controlplane pytest tools/controlplane/tests/test_connectivity.py tools/controlplane/tests/test_cli_stack_workflow.py tools/controlplane/tests/test_proxmox_vm_loadtest_plan.py -v`
Expected: PASS (new connectivity tests pass; the cli-stack and proxmox suites still import/run fine).

- [ ] **Step 6: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario/connectivity.py tools/controlplane/src/controlplane_tool/scenario/scenarios/_workflow_assembly.py tools/controlplane/tests/test_connectivity.py
git commit -m "feat(scenario): add ConnectivityStrategy + MultipassConnectivity"
```

---

## Task 2: Route `build_command_tasks` through `ConnectivityStrategy`

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/scenarios/_workflow_assembly.py` (`build_command_tasks`, lines ~242-315)
- Test: `tools/controlplane/tests/test_two_vm_stack_prelude_argv.py` (new)

- [ ] **Step 1: Write the argv-golden safety net (passes NOW, before the refactor)**

Create `tools/controlplane/tests/test_two_vm_stack_prelude_argv.py`. This is a characterization test: it pins the resolved argv of the two-vm stack prelude with the current machinery and must stay green through the refactor.

```python
from __future__ import annotations

from pathlib import Path

from workflow_tasks.shell import RecordingShell

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.e2e.e2e_runner import E2eRunner
from controlplane_tool.infra.vm.vm_models import VmRequest
from controlplane_tool.scenario.scenarios.two_vm_loadtest import build_two_vm_loadtest_plan


def _plan():
    runner = E2eRunner(
        repo_root=Path("/repo"),
        shell=RecordingShell(),
        host_resolver=lambda _request: "10.0.0.9",
    )
    request = E2eRequest(
        scenario="two-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        loadgen_vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e-loadgen"),
    )
    return build_two_vm_loadtest_plan(runner, request)


def test_two_vm_stack_prelude_argv_is_resolved_and_pinned() -> None:
    plan = _plan()
    setup = plan._build_setup()  # noqa: SLF001
    tasks = plan._build_stack_prelude_tasks(setup, resolve_host=True)  # noqa: SLF001

    by_id = {t.task_id: list(t.spec.argv) for t in tasks if getattr(t, "spec", None) is not None}

    # No unresolved multipass placeholders survive in any host command.
    for task_id, argv in by_id.items():
        assert not any("<multipass-ip:" in arg for arg in argv), task_id

    # The provision_base ansible command targets the resolved stack IP.
    provision = by_id["vm.provision_base"]
    assert provision[0] == "ansible-playbook"
    assert "10.0.0.9," in provision
    assert provision[-1].endswith("provision-base.yml")
```

- [ ] **Step 2: Run it to verify it PASSES now (characterization)**

Run: `cd /Users/micheleciavotta/Downloads/mcFaas && uv run --project tools/controlplane pytest tools/controlplane/tests/test_two_vm_stack_prelude_argv.py -v`
Expected: PASS (pins current resolved argv; regression guard for the refactor).

> If `_build_stack_prelude_tasks` / `_build_setup` have different names or signatures, inspect `tools/controlplane/src/controlplane_tool/scenario/scenarios/two_vm_loadtest.py` and adjust the test to the real API. The contract under test is: the resolved stack-prelude host commands contain the injected IP and no `<multipass-ip:` placeholders.

- [ ] **Step 3: Refactor `build_command_tasks` to use the strategy**

In `_workflow_assembly.py`, replace the `build_command_tasks` body (the part after the docstring) with a version that takes `connectivity` and routes through it. Change the signature to add `connectivity: ConnectivityStrategy | None = None` (keyword-only, after `resolve_host`), and replace the body:

```python
    context = setup.context
    vm_request = setup.vm_request
    vm_orch = runner.vm
    remote_dir = vm_orch.remote_project_dir(vm_request)

    if connectivity is None:
        connectivity = MultipassConnectivity(runner=runner, request=request)

    host_executor = HostCommandTaskExecutor(runner.shell)
    vm_executor = VmCommandTaskExecutor(connectivity.vm_runner(vm_request))

    tasks: list = []
    for component in compose_recipe(recipe):
        ctx = context_selector(component) if context_selector is not None else context
        for operation in component.planner(ctx):
            if special_handler is not None:
                result = special_handler(operation)
                if result is HANDLED:
                    continue
                if result is not None:
                    tasks.append(result)
                    continue
            title = _SUMMARY_OVERRIDES.get(operation.operation_id, operation.summary)
            if operation.execution_target == "vm":
                tasks.append(
                    command_task_from_operation(
                        operation, vm_executor, title=title, remote_dir=remote_dir
                    )
                )
            else:
                host_op = (
                    connectivity.resolve_host_operation(operation)
                    if resolve_host
                    else operation
                )
                tasks.append(
                    command_task_from_operation(host_op, host_executor, title=title)
                )
    return tasks
```

Also update the signature line to:

```python
def build_command_tasks(
    runner: "E2eRunner",
    request: E2eRequest,
    setup: _Setup,
    recipe,
    *,
    special_handler: SpecialHandler | None = None,
    context_selector: Callable[[object], object] | None = None,
    resolve_host: bool = True,
    connectivity: ConnectivityStrategy | None = None,
) -> list:
```

Remove the now-unused inline `CommandResolver`/`OrchestratorVmRunner`/`resolve_host_operation` usage and the `ip_cache` local from the old body. Keep the existing `from ...command_resolver import CommandResolver` import only if still referenced elsewhere in the file (it is used by `host_command_task_from_step`); leave that import in place.

- [ ] **Step 4: Run the golden + the pinned-argv cli-stack test (must stay green)**

Run: `cd /Users/micheleciavotta/Downloads/mcFaas && uv run --project tools/controlplane pytest tools/controlplane/tests/test_two_vm_stack_prelude_argv.py tools/controlplane/tests/test_cli_stack_workflow.py tools/controlplane/tests/test_connectivity.py -v`
Expected: PASS (resolved argv unchanged; `test_workflow_command_tasks_are_resolved_and_pinned` still holds).

- [ ] **Step 5: Run the scenario plan suites**

Run: `cd /Users/micheleciavotta/Downloads/mcFaas && uv run --project tools/controlplane pytest tools/controlplane/tests/test_two_vm_loadtest_plan.py tools/controlplane/tests/test_proxmox_vm_loadtest_plan.py tools/controlplane/tests/test_helm_stack_workflow.py tools/controlplane/tests/test_scenario_flows.py -v`
Expected: PASS.

- [ ] **Step 6: Run the full controlplane suite**

Run: `cd /Users/micheleciavotta/Downloads/mcFaas && uv run --project tools/controlplane pytest tools/controlplane/tests -q`
Expected: PASS (1131+ passed; behavior unchanged — multipass path is the default).

- [ ] **Step 7: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario/scenarios/_workflow_assembly.py tools/controlplane/tests/test_two_vm_stack_prelude_argv.py
git commit -m "refactor(scenario): build_command_tasks routes via ConnectivityStrategy"
```

---

## Real-VM validation (user, after merge of the branch)

B1a is behavior-preserving for multipass, but the only true proof is a real run. Before
trusting B1 end to end, run `two-vm-loadtest` on real multipass VMs (e.g.
`./scripts/controlplane.sh e2e run two-vm-loadtest` or the project's documented entrypoint)
and confirm it provisions + runs k6 as before. This is not a CI gate; it is the manual
validation the spec requires for the multipass lifecycle.

---

## Self-Review

- **Spec coverage (B1 §3.1/§3.2/§3.3 B1a):** `ConnectivityStrategy` Protocol (Task 1); `MultipassConnectivity` extracting current behavior via the moved `resolve_host_operation` + `OrchestratorVmRunner` (Task 1); `build_command_tasks` takes the strategy, defaulting to multipass so all callers are unchanged (Task 2). Proxmox/azure explicitly out of scope (stated). Interface simplified to 2 methods vs the spec's 3 — documented in the header (repo_sync_command deferred to B1b).
- **Placeholder scan:** no TBD/TODO; full code for `connectivity.py` and the refactored `build_command_tasks` body provided; the one conditional note (Task 2 Step 2) gives the behavioral contract if the two-vm plan API differs.
- **Type consistency:** `ConnectivityStrategy.resolve_host_operation(op) -> RemoteCommandOperation` and `.vm_runner(request) -> OrchestratorVmRunner` used identically in `MultipassConnectivity` and in `build_command_tasks`; the moved module-level `resolve_host_operation(operation, *, resolver, request, vm, ip_cache)` keeps its original signature so `MultipassConnectivity` and any re-export consumer call it the same way.
- **Behavior preservation:** guaranteed by (a) `MultipassConnectivity` delegating to the unchanged `resolve_host_operation`, (b) the default making every existing caller use multipass, (c) the existing pinned-argv `test_cli_stack_workflow` + the new two-vm argv golden, (d) the full suite, (e) the user's real-multipass run.
