# Phase B3a — Unified Loadtest Flow Driver (multipass) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce `RunContext`, `LoadtestConnectivityAdapter` (+ `MultipassLoadtestAdapter`), and `run_loadtest_flow`, then route the two-vm (multipass) scenario through them with byte-identical behavior (argv golden + dry-run task_id/title parity stay green).

**Architecture:** A single ordered driver (`run_loadtest_flow`) threads a shared mutable `RunContext` through the canonical phases (ensure stack → prelude → register → ensure loadgen → prepare → loadgen body → cleanup), emitting the same native `Workflow`/`workflow_step` events two-vm uses today. Per-lifecycle differences are supplied by a `LoadtestConnectivityAdapter` that composes the existing generic `ConnectivityStrategy`. B3a wires only the multipass adapter; proxmox (B3b) and azure (B3c) follow.

**Tech Stack:** Python 3.12, `uv`, pytest. `controlplane_tool` scenarios + `workflow_tasks` primitives (`EnsureVmRunning`, `DestroyVm`, `Workflow`, `workflow_step`, `build_loadgen_body_tasks`).

**Scope (B3a only):** Route ONLY two-vm/multipass through the driver. azure_vm_loadtest.py and proxmox_vm_loadtest.py are UNTOUCHED this stage. Behavior must be byte-identical for two-vm — `test_two_vm_loadtest_plan.py` and `test_two_vm_stack_prelude_argv.py` are the safety net and MUST stay green by construction.

**Spec:** `docs/superpowers/specs/2026-06-07-loadtest-b3-final-collapse-design.md`.

**Validation gate:** After B3a lands and structural tests are green, validate `two-vm-loadtest` end-to-end on a real multipass VM (the design's always-required gate) before considering B3a done.

---

## Reference: what two-vm's run() does today (the behavior to preserve)

From `tools/controlplane/src/controlplane_tool/scenario/scenarios/two_vm_loadtest.py` `run()`:
1. `EnsureVmRunning("vm.stack.ensure_running", lifecycle=setup.lifecycle, config=setup.vm_config)` inside `workflow_step(...)` → `stack_info`.
2. prelude: `build_command_tasks(runner, request, setup, recipe)` (recipe = `_TWO_VM_STACK_PRELUDE_COMPONENTS`); `Workflow(tasks=stack_tasks).run()`.
3. `EnsureVmRunning("vm.loadgen.ensure_running", lifecycle=setup.lifecycle, config=loadgen_config)` inside `workflow_step(...)` → `loadgen_info`.
4. `TwoVmLoadtestRunner(repo_root=...)`; `remote_paths = two_vm_remote_paths(loadgen_info.home, payload_name=...)`; `vm_runner_impl.prepare_loadgen(request, remote_paths)` (uploads k6 script — side effect, NO event); `run_dir = vm_runner_impl._create_run_dir()`; `control_plane_url = two_vm_control_plane_url(request.vm, host=stack_info.host)`.
5. `RegisterFunctions("functions.register", control_plane_url, specs=[...selected_functions...]).run()` — inline, NO `workflow_step`, NOT in the static task list.
6. `make_loadtest_k6_config(...)`; build `loadgen_runner`/`fetcher`/`prom_client`; `build_loadgen_body_tasks(LoadgenBodyInputs(...))`.
7. `cleanup = [DestroyVm("vm.loadgen.destroy"), DestroyVm("vm.stack.destroy")]` if `request.cleanup_vm`; `Workflow(tasks=body, cleanup_tasks=cleanup).run()`.

Static plan today: `_TWO_VM_STATIC_TASK_IDS` (19 ids: ensure_stack, 10 prelude components, ensure_loadgen, 5 loadgen body, destroy loadgen, destroy stack — NO functions.register) and `phase_titles` (ensure stack title + prelude titles + the 8 fixed loadgen/destroy titles, NO suffix for multipass).

`run(event_listener=None)` currently IGNORES `event_listener` (events flow through the global `workflow_step`/`Workflow` mechanism). B3a preserves that — the driver accepts `event_listener` but multipass does not need to wire it (proper per-lifecycle event_listener integration is B3b).

---

## File Structure

- **Create** `tools/controlplane/src/controlplane_tool/scenario/loadtest_flow.py` — `RunContext` dataclass, `FlowPhase` enum, `run_loadtest_flow(...)` driver, and the static-plan functions `loadtest_flow_task_ids(...)` / `loadtest_flow_phase_titles(...)`. One responsibility: the unified loadtest flow + its static plan.
- **Create** `tools/controlplane/src/controlplane_tool/scenario/loadtest_adapter.py` — `InstallEndpoint` dataclass, `LoadtestConnectivityAdapter` Protocol, and `MultipassLoadtestAdapter`. (Proxmox/Azure adapters added in B3b/B3c.)
- **Modify** `tools/controlplane/src/controlplane_tool/scenario/scenarios/two_vm_loadtest.py` — `run()` delegates to `run_loadtest_flow`; `task_ids`/`phase_titles` delegate to the static-plan functions.
- **Create tests** `tools/controlplane/tests/test_loadtest_adapter.py`, `tools/controlplane/tests/test_loadtest_flow.py`.

These two new modules live in `controlplane_tool.scenario` (not the library) because they orchestrate controlplane-side pieces (`E2eRunner`, `_Setup`, `TwoVmLoadtestRunner`, `build_command_tasks`) — they are scenario glue, not reusable library primitives.

---

## Task 1: `RunContext` + `FlowPhase`

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/scenario/loadtest_flow.py`
- Test: `tools/controlplane/tests/test_loadtest_flow.py`

- [ ] **Step 1: Write the failing test**

```python
# tools/controlplane/tests/test_loadtest_flow.py
from __future__ import annotations

from controlplane_tool.scenario.loadtest_flow import FlowPhase, RunContext


def test_run_context_starts_empty_and_is_mutable() -> None:
    ctx = RunContext()
    assert ctx.stack_info is None
    assert ctx.loadgen_info is None
    assert ctx.control_plane_url is None
    assert ctx.prometheus_url is None
    assert ctx.run_dir is None
    assert ctx.remote_paths is None
    assert ctx.stack_host is None
    ctx.stack_host = "10.0.0.5"
    assert ctx.stack_host == "10.0.0.5"


def test_flow_phase_members() -> None:
    assert {p.name for p in FlowPhase} >= {"AFTER_STACK_READY", "BEFORE_LOADGEN"}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_loadtest_flow.py -q`
Expected: FAIL with `ModuleNotFoundError: No module named 'controlplane_tool.scenario.loadtest_flow'`.

- [ ] **Step 3: Write minimal implementation**

```python
# tools/controlplane/src/controlplane_tool/scenario/loadtest_flow.py
"""Unified loadtest flow driver.

One ordered driver threads a shared RunContext through the canonical loadtest
phases (ensure stack -> prelude -> register -> ensure loadgen -> prepare ->
loadgen body -> cleanup), emitting the same native Workflow/workflow_step events
the per-scenario run()s use today. Per-lifecycle differences are supplied by a
LoadtestConnectivityAdapter (see loadtest_adapter.py).
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum, auto
from pathlib import Path
from typing import Any


class FlowPhase(Enum):
    """Insertion points for adapter-supplied extra steps."""
    AFTER_STACK_READY = auto()   # after stack ensured + provisioned (proxmox: publish CP port)
    BEFORE_LOADGEN = auto()      # after loadgen ensured, before the loadgen body (proxmox: publish prom port)


@dataclass
class RunContext:
    """Shared mutable state threaded through run_loadtest_flow.

    Fields are None until the step that produces them runs; adapter resolvers are
    only called after their inputs exist.
    """
    stack_info: Any = None
    stack_host: str | None = None
    loadgen_info: Any = None
    control_plane_url: str | None = None
    prometheus_url: str | None = None
    run_dir: Path | None = None
    remote_paths: Any = None
```

- [ ] **Step 4: Run test to verify it passes**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_loadtest_flow.py -q`
Expected: PASS (2 passed).

- [ ] **Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario/loadtest_flow.py \
        tools/controlplane/tests/test_loadtest_flow.py
git commit -m "feat(loadtest): add RunContext + FlowPhase for the unified flow"
```

---

## Task 2: `InstallEndpoint` + `LoadtestConnectivityAdapter` + `MultipassLoadtestAdapter`

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/scenario/loadtest_adapter.py`
- Test: `tools/controlplane/tests/test_loadtest_adapter.py`

The adapter composes the generic `ConnectivityStrategy` and supplies the loadtest-only pieces. `MultipassLoadtestAdapter` reproduces exactly what two-vm's `run()` does for each piece.

- [ ] **Step 1: Write the failing test**

```python
# tools/controlplane/tests/test_loadtest_adapter.py
from __future__ import annotations

from pathlib import Path
from types import SimpleNamespace

from controlplane_tool.scenario.loadtest_adapter import (
    InstallEndpoint,
    MultipassLoadtestAdapter,
)
from controlplane_tool.scenario.loadtest_flow import RunContext


def test_install_endpoint_fields() -> None:
    ep = InstallEndpoint(host="1.2.3.4", user="ubuntu", private_key=Path("/k"), port=None)
    assert (ep.host, ep.user, ep.private_key, ep.port) == ("1.2.3.4", "ubuntu", Path("/k"), None)


def test_multipass_adapter_title_suffix_is_empty() -> None:
    adapter = MultipassLoadtestAdapter(runner=SimpleNamespace(), request=SimpleNamespace())
    assert adapter.title_suffix == ""


def test_multipass_adapter_extra_steps_are_empty() -> None:
    from controlplane_tool.scenario.loadtest_flow import FlowPhase
    adapter = MultipassLoadtestAdapter(runner=SimpleNamespace(), request=SimpleNamespace())
    ctx = RunContext()
    assert adapter.extra_steps(FlowPhase.AFTER_STACK_READY, ctx) == []
    assert adapter.extra_steps(FlowPhase.BEFORE_LOADGEN, ctx) == []
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_loadtest_adapter.py -q`
Expected: FAIL with `ModuleNotFoundError`.

- [ ] **Step 3: Write minimal implementation**

```python
# tools/controlplane/src/controlplane_tool/scenario/loadtest_adapter.py
"""Per-lifecycle loadtest connectivity adapter.

A LoadtestConnectivityAdapter composes a generic ConnectivityStrategy (for the
provision prelude via build_command_tasks) and supplies the loadtest-only pieces
that differ between multipass/proxmox/azure: VM lifecycles, the loadgen install
endpoint, runner/fetcher, control-plane/prometheus URLs, an optional script-upload
hook, lifecycle-specific extra steps, and the display title suffix.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import TYPE_CHECKING, Protocol

from workflow_tasks.loadtest.ports import PrometheusClient, RemoteFileFetcher
from workflow_tasks.tasks.executors import VmCommandRunner
from workflow_tasks.vm.multipass import _find_ssh_private_key_path

from multipass import find_ssh_public_key

from controlplane_tool.infra.vm_lifecycle_adapters import MultipassVmAdapter
from controlplane_tool.loadtest.loadtest_adapters import (
    HttpPrometheusClient,
    OrchestratorVmRunner,
    VmFileFetcher,
)
from controlplane_tool.scenario.connectivity import ConnectivityStrategy, MultipassConnectivity
from controlplane_tool.scenario.loadtest_flow import FlowPhase, RunContext
from controlplane_tool.scenario.two_vm_loadtest_config import (
    two_vm_control_plane_url,
    two_vm_prometheus_url,
)

if TYPE_CHECKING:
    from controlplane_tool.e2e.e2e_models import E2eRequest
    from controlplane_tool.e2e.e2e_runner import E2eRunner


@dataclass
class InstallEndpoint:
    """SSH endpoint for install_k6_task. ``port`` is None when the lifecycle uses the default."""
    host: str
    user: str
    private_key: Path | None
    port: int | None = None


class LoadtestConnectivityAdapter(Protocol):
    title_suffix: str
    connectivity: ConnectivityStrategy

    def stack_lifecycle(self): ...
    def loadgen_lifecycle(self): ...
    def loadgen_install_endpoint(self, ctx: RunContext) -> InstallEndpoint: ...
    def loadgen_runner(self, ctx: RunContext) -> VmCommandRunner: ...
    def fetcher(self, ctx: RunContext) -> RemoteFileFetcher: ...
    def control_plane_url(self, ctx: RunContext) -> str: ...
    def prometheus_url(self, ctx: RunContext) -> str: ...
    def prepare_loadgen(self, ctx: RunContext) -> None: ...
    def extra_steps(self, phase: FlowPhase, ctx: RunContext) -> list: ...


@dataclass
class MultipassLoadtestAdapter:
    """Multipass: reproduces two-vm's run() connectivity exactly."""

    runner: "E2eRunner"
    request: "E2eRequest"
    title_suffix: str = ""
    connectivity: ConnectivityStrategy = field(init=False)
    _vm_runner_impl: object = field(default=None, init=False, repr=False)

    def __post_init__(self) -> None:
        self.connectivity = MultipassConnectivity(runner=self.runner, request=self.request)

    def _runner_impl(self):
        # TwoVmLoadtestRunner owns prepare_loadgen + _create_run_dir + the multipass vm handle.
        if self._vm_runner_impl is None:
            from controlplane_tool.e2e.two_vm_loadtest_runner import TwoVmLoadtestRunner
            self._vm_runner_impl = TwoVmLoadtestRunner(repo_root=self.runner.paths.workspace_root)
        return self._vm_runner_impl

    def stack_lifecycle(self):
        return MultipassVmAdapter(self.runner.vm)

    def loadgen_lifecycle(self):
        return MultipassVmAdapter(self.runner.vm)

    def loadgen_install_endpoint(self, ctx: RunContext) -> InstallEndpoint:
        return InstallEndpoint(
            host=ctx.loadgen_info.host,
            user=self.request.loadgen_vm.user,
            private_key=_find_ssh_private_key_path(find_ssh_public_key()),
            port=None,
        )

    def loadgen_runner(self, ctx: RunContext) -> VmCommandRunner:
        return OrchestratorVmRunner(self._runner_impl().vm, self.request.loadgen_vm)

    def fetcher(self, ctx: RunContext) -> RemoteFileFetcher:
        return VmFileFetcher(vm=self._runner_impl().vm, request=self.request.loadgen_vm)

    def control_plane_url(self, ctx: RunContext) -> str:
        return two_vm_control_plane_url(self.request.vm, host=ctx.stack_info.host)

    def prometheus_url(self, ctx: RunContext) -> str:
        return two_vm_prometheus_url(self.request.vm, host=ctx.stack_info.host)

    def prepare_loadgen(self, ctx: RunContext) -> None:
        # Uploads the k6 script + creates remote run dirs (side effect, no event) —
        # without this `k6 run` exits immediately with "script.js: No such file".
        self._runner_impl().prepare_loadgen(self.request, ctx.remote_paths)

    def create_run_dir(self) -> Path:
        return self._runner_impl()._create_run_dir()  # noqa: SLF001

    def extra_steps(self, phase: FlowPhase, ctx: RunContext) -> list:
        return []
```

NOTE: `create_run_dir()` is a multipass concrete method (not on the Protocol); the driver gets the run_dir via the adapter — Task 3 decides the exact call. Verify the imports resolve (especially `two_vm_loadtest_runner`, `loadtest_adapters`, `two_vm_loadtest_config`) by reading those modules; if a name differs, adapt and note it. If `MultipassVmAdapter`/`OrchestratorVmRunner`/`VmFileFetcher` signatures differ from what two-vm uses, mirror two-vm exactly.

- [ ] **Step 4: Run test to verify it passes**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_loadtest_adapter.py -q`
Expected: PASS (3 passed).

- [ ] **Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario/loadtest_adapter.py \
        tools/controlplane/tests/test_loadtest_adapter.py
git commit -m "feat(loadtest): add LoadtestConnectivityAdapter + MultipassLoadtestAdapter"
```

---

## Task 3: `run_loadtest_flow` driver

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/loadtest_flow.py`
- Test: `tools/controlplane/tests/test_loadtest_flow.py`

The driver transcribes two-vm's `run()` into a lifecycle-generic shape using the adapter. It threads `RunContext`, calls the adapter for each lifecycle-specific piece, and uses the same native event mechanism (`EnsureVmRunning` inside `workflow_step`; prelude + body via `Workflow(...).run()`). The loadgen body task_ids are fixed; titles carry `adapter.title_suffix`.

- [ ] **Step 1: Write the failing test (phase ordering with fakes)**

```python
# append to tools/controlplane/tests/test_loadtest_flow.py
def test_run_loadtest_flow_orders_phases_and_populates_context(monkeypatch) -> None:
    """The driver runs ensure-stack -> prelude -> register -> ensure-loadgen ->
    prepare -> loadgen body -> cleanup, threading RunContext."""
    from controlplane_tool.scenario import loadtest_flow as mod

    events: list[str] = []

    class FakeInfo:
        host = "10.0.0.9"
        home = "/home/ubuntu"

    class FakeAdapter:
        title_suffix = " (Fake)"
        def stack_lifecycle(self): return "stack-lc"
        def loadgen_lifecycle(self): return "loadgen-lc"
        def loadgen_install_endpoint(self, ctx):
            from controlplane_tool.scenario.loadtest_adapter import InstallEndpoint
            return InstallEndpoint(host="1.1.1.1", user="ubuntu", private_key=None, port=None)
        def loadgen_runner(self, ctx): return object()
        def fetcher(self, ctx): return object()
        def control_plane_url(self, ctx): return "http://cp:8080"
        def prometheus_url(self, ctx): return "http://prom:9090"
        def prepare_loadgen(self, ctx): events.append("prepare")
        def create_run_dir(self): return __import__("pathlib").Path("/tmp/run")
        def extra_steps(self, phase, ctx): events.append(f"extra:{phase.name}"); return []

    # Stub the heavy collaborators the driver calls.
    monkeypatch.setattr(mod, "_ensure_vm", lambda task_id, title, lifecycle, config: events.append(task_id) or FakeInfo())
    monkeypatch.setattr(mod, "_build_prelude_tasks", lambda runner, request, setup, recipe, connectivity: [])
    monkeypatch.setattr(mod, "_run_workflow", lambda tasks, cleanup_tasks=None: events.append("workflow"))
    monkeypatch.setattr(mod, "_register_functions", lambda runner, request, setup, ctx: events.append("register"))
    monkeypatch.setattr(mod, "_build_loadgen_body", lambda runner, request, adapter, ctx: events.append("body") or [])
    monkeypatch.setattr(mod, "_two_vm_remote_paths_for", lambda request, ctx: object())

    setup = type("S", (), {"vm_config": object(), "context": object()})()
    request = type("R", (), {"cleanup_vm": False, "loadgen_vm": type("L", (), {"name": "lg", "cpus": 1, "memory": "1G", "disk": "5G", "user": "ubuntu"})()})()

    mod.run_loadtest_flow(runner=object(), request=request, setup=setup, recipe=object(), adapter=FakeAdapter())

    # ensure_stack before prelude(workflow) before register before ensure_loadgen before prepare before body
    assert events.index("vm.stack.ensure_running") < events.index("workflow")
    assert events.index("register") < events.index("vm.loadgen.ensure_running")
    assert events.index("vm.loadgen.ensure_running") < events.index("prepare") < events.index("body")
```

This test pins the *ordering contract*. It requires the driver to delegate its heavy collaborators to small module-level helpers (`_ensure_vm`, `_build_prelude_tasks`, `_run_workflow`, `_register_functions`, `_build_loadgen_body`, `_two_vm_remote_paths_for`) so they can be monkeypatched. Implement those helpers as thin wrappers around the real calls.

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_loadtest_flow.py::test_run_loadtest_flow_orders_phases_and_populates_context -q`
Expected: FAIL (`run_loadtest_flow` undefined).

- [ ] **Step 3: Write the implementation**

Append to `loadtest_flow.py` (add imports at top as needed). The helpers wrap the real collaborators; `run_loadtest_flow` is the ordered driver:

```python
from workflow_tasks import (
    DestroyVm,
    EnsureVmRunning,
    Workflow,
    workflow_step,
)


def _ensure_vm(task_id: str, title: str, lifecycle, config):
    task = EnsureVmRunning(task_id=task_id, title=title, lifecycle=lifecycle, config=config)
    with workflow_step(task_id=task.task_id, title=task.title):
        return task.run()


def _build_prelude_tasks(runner, request, setup, recipe, connectivity) -> list:
    from controlplane_tool.scenario.scenarios._workflow_assembly import build_command_tasks
    return build_command_tasks(runner, request, setup, recipe, connectivity=connectivity)


def _run_workflow(tasks: list, cleanup_tasks: list | None = None) -> None:
    Workflow(tasks=tasks, cleanup_tasks=cleanup_tasks or []).run()


def _two_vm_remote_paths_for(request, ctx: "RunContext"):
    from controlplane_tool.scenario.two_vm_loadtest_config import two_vm_remote_paths
    return two_vm_remote_paths(
        ctx.loadgen_info.home,
        payload_name=request.k6_payload.name if request.k6_payload is not None else None,
    )


def _register_functions(runner, request, setup, ctx: "RunContext") -> None:
    from workflow_tasks.components.function_tasks import FunctionSpec, RegisterFunctions
    from controlplane_tool.scenario.scenario_helpers import function_image, selected_functions

    runtime_image_default = f"{setup.context.local_registry}/nanofaas/function-runtime:e2e"
    RegisterFunctions(
        task_id="functions.register",
        title="Register functions",
        control_plane_url=ctx.control_plane_url,
        specs=[
            FunctionSpec(
                name=fn_key,
                image=function_image(fn_key, request.resolved_scenario, runtime_image_default),
            )
            for fn_key in selected_functions(request.resolved_scenario)
        ],
    ).run()


def _build_loadgen_body(runner, request, adapter, ctx: "RunContext") -> list:
    from workflow_tasks import (
        HttpPrometheusClient,  # noqa: F401  (re-exported? else import from loadtest_adapters)
    )
    from controlplane_tool.loadtest.loadtest_adapters import HttpPrometheusClient as _Prom
    from workflow_tasks import LoadgenBodyInputs, build_loadgen_body_tasks, make_loadtest_k6_config
    from controlplane_tool.scenario.two_vm_loadtest_config import (
        LOADTEST_PROMETHEUS_QUERIES,
        two_vm_load_stages,
        two_vm_target_function,
    )

    s = adapter.title_suffix
    ep = adapter.loadgen_install_endpoint(ctx)
    k6_config = make_loadtest_k6_config(
        remote_paths=ctx.remote_paths,
        control_plane_url=ctx.control_plane_url,
        target_function=two_vm_target_function(request),
        stages=two_vm_load_stages(request),
        vus=request.k6_vus,
        duration=request.k6_duration,
    )
    return build_loadgen_body_tasks(
        LoadgenBodyInputs(
            task_ids=("loadgen.install_k6", "loadgen.run_k6", "loadgen.fetch_results",
                      "metrics.prometheus_snapshot", "loadtest.write_report"),
            titles=(f"Install k6 on loadgen VM{s}", f"Run k6 loadtest{s}",
                    f"Fetch k6 results from loadgen VM{s}", f"Capture Prometheus snapshots{s}",
                    f"Write loadtest report{s}"),
            runner=adapter.loadgen_runner(ctx),
            fetcher=adapter.fetcher(ctx),
            prometheus_client=_Prom(url=adapter.prometheus_url(ctx)),
            prometheus_queries=LOADTEST_PROMETHEUS_QUERIES,
            k6_config=k6_config,
            remote_dir=ctx.loadgen_info.home,
            remote_summary_path=ctx.remote_paths.summary_path,
            run_dir=ctx.run_dir,
            repo_root=runner.paths.workspace_root,
            shell=runner.shell,
            install_host=ep.host,
            install_user=ep.user,
            install_private_key=ep.private_key,
            install_port=ep.port,
        )
    )


def _loadgen_vm_config(request):
    from workflow_tasks.vm.models import VmConfig
    lg = request.loadgen_vm
    return VmConfig(name=lg.name or "", cpus=lg.cpus, memory=lg.memory, disk=lg.disk)


def run_loadtest_flow(*, runner, request, setup, recipe, adapter, event_listener=None) -> None:
    s = adapter.title_suffix
    ctx = RunContext()

    # 1. ensure stack
    ctx.stack_info = _ensure_vm("vm.stack.ensure_running", f"Ensure stack VM running{s}",
                                adapter.stack_lifecycle(), setup.vm_config)
    ctx.stack_host = getattr(ctx.stack_info, "host", None)

    # 2. provision prelude + AFTER_STACK_READY extra steps
    prelude = _build_prelude_tasks(runner, request, setup, recipe, adapter.connectivity)
    prelude += adapter.extra_steps(FlowPhase.AFTER_STACK_READY, ctx)
    _run_workflow(prelude)

    # 3. register functions (REST, inline, no event, not in static plan)
    ctx.control_plane_url = adapter.control_plane_url(ctx)
    _register_functions(runner, request, setup, ctx)

    # 4. ensure loadgen
    ctx.loadgen_info = _ensure_vm("vm.loadgen.ensure_running", f"Ensure loadgen VM running{s}",
                                  adapter.loadgen_lifecycle(), _loadgen_vm_config(request))
    ctx.remote_paths = _two_vm_remote_paths_for(request, ctx)
    ctx.run_dir = adapter.create_run_dir()

    # 5. before-loadgen extra steps (proxmox: publish prometheus port) + prepare (script upload)
    pre = adapter.extra_steps(FlowPhase.BEFORE_LOADGEN, ctx)
    if pre:
        _run_workflow(pre)
    adapter.prepare_loadgen(ctx)
    ctx.prometheus_url = adapter.prometheus_url(ctx)

    # 6. loadgen body + 7. cleanup
    body = _build_loadgen_body(runner, request, adapter, ctx)
    cleanup: list = []
    if getattr(request, "cleanup_vm", True):
        cleanup = [
            DestroyVm(task_id="vm.loadgen.destroy", title=f"Destroy loadgen VM{s}",
                      lifecycle=adapter.loadgen_lifecycle(), info=ctx.loadgen_info),
            DestroyVm(task_id="vm.stack.destroy", title=f"Destroy stack VM{s}",
                      lifecycle=adapter.stack_lifecycle(), info=ctx.stack_info),
        ]
    _run_workflow(body, cleanup_tasks=cleanup)
```

IMPORTANT preservation notes the implementer must check against two-vm:
- two-vm builds the loadgen body AFTER `prepare_loadgen` + `create_run_dir` + register. The driver order matches.
- two-vm's prometheus client uses `two_vm_prometheus_url(request.vm, host=stack_info.host)` — `adapter.prometheus_url(ctx)` returns exactly that for multipass.
- The `HttpPrometheusClient` import: use `controlplane_tool.loadtest.loadtest_adapters.HttpPrometheusClient` (the same class two-vm uses). Remove the dead `from workflow_tasks import HttpPrometheusClient` stub if it doesn't exist there.
- Multipass `stack_lifecycle()`/`loadgen_lifecycle()` both return a fresh `MultipassVmAdapter(runner.vm)` — two-vm used the SAME `setup.lifecycle` for stack and `lifecycle = setup.lifecycle` for loadgen/destroy. Functionally equivalent (stateless adapter), but if any test asserts identity, reuse one instance — verify against `test_two_vm_loadtest_plan.py`.

- [ ] **Step 4: Run the ordering test**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_loadtest_flow.py -q`
Expected: PASS (3 passed).

- [ ] **Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario/loadtest_flow.py \
        tools/controlplane/tests/test_loadtest_flow.py
git commit -m "feat(loadtest): add run_loadtest_flow unified driver"
```

---

## Task 4: Static-plan functions (`task_ids` / `phase_titles`)

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/loadtest_flow.py`
- Test: `tools/controlplane/tests/test_loadtest_flow.py`

These derive the dry-run static plan from the recipe + adapter, so two-vm's `task_ids`/`phase_titles` properties can delegate to them and stay identical. `functions.register` is NOT included (matches two-vm). The prelude task_ids/titles come from `build_command_tasks(..., resolve_host=False)`. Adapter `extra_steps` are NOT in the multipass static plan (multipass returns none); proxmox's publish steps will appear here in B3b because its `extra_steps` are non-empty.

- [ ] **Step 1: Write the failing test (parity with two-vm's current constants)**

```python
# append to tools/controlplane/tests/test_loadtest_flow.py
_EXPECTED_TWO_VM_TASK_IDS = [
    "vm.stack.ensure_running",
    "vm.provision_base", "repo.sync_to_vm", "registry.ensure_container",
    "images.build_core", "images.build_selected_functions", "k3s.install",
    "k3s.configure_registry", "namespace.install", "helm.deploy_control_plane",
    "helm.deploy_function_runtime",
    "vm.loadgen.ensure_running",
    "loadgen.install_k6", "loadgen.run_k6", "loadgen.fetch_results",
    "metrics.prometheus_snapshot", "loadtest.write_report",
    "vm.loadgen.destroy", "vm.stack.destroy",
]


def test_static_task_ids_match_two_vm(monkeypatch) -> None:
    from controlplane_tool.scenario import loadtest_flow as mod

    # Fake the prelude id derivation to the 10 component ids (no live VM).
    prelude_ids = _EXPECTED_TWO_VM_TASK_IDS[1:11]
    monkeypatch.setattr(mod, "_prelude_static_ids", lambda runner, request, setup, recipe, connectivity: prelude_ids)

    class FakeAdapter:
        title_suffix = ""
        connectivity = object()
        def extra_step_ids(self, phase): return []

    ids = mod.loadtest_flow_task_ids(runner=object(), request=object(), setup=object(),
                                     recipe=object(), adapter=FakeAdapter())
    assert ids == _EXPECTED_TWO_VM_TASK_IDS
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_loadtest_flow.py::test_static_task_ids_match_two_vm -q`
Expected: FAIL (`loadtest_flow_task_ids` undefined).

- [ ] **Step 3: Write the implementation**

Append to `loadtest_flow.py`:

```python
def _prelude_static_ids(runner, request, setup, recipe, connectivity) -> list[str]:
    return [t.task_id for t in _prelude_static_tasks(runner, request, setup, recipe, connectivity)]


def _prelude_static_tasks(runner, request, setup, recipe, connectivity) -> list:
    from controlplane_tool.scenario.scenarios._workflow_assembly import build_command_tasks
    return build_command_tasks(runner, request, setup, recipe, connectivity=connectivity, resolve_host=False)


_LOADGEN_BODY_IDS = ("loadgen.install_k6", "loadgen.run_k6", "loadgen.fetch_results",
                     "metrics.prometheus_snapshot", "loadtest.write_report")
_LOADGEN_BODY_BASE_TITLES = ("Install k6 on loadgen VM", "Run k6 loadtest",
                             "Fetch k6 results from loadgen VM", "Capture Prometheus snapshots",
                             "Write loadtest report")


def loadtest_flow_task_ids(*, runner, request, setup, recipe, adapter) -> list[str]:
    ids = ["vm.stack.ensure_running"]
    ids += _prelude_static_ids(runner, request, setup, recipe, adapter.connectivity)
    ids += [t.task_id for t in adapter.extra_step_ids(FlowPhase.AFTER_STACK_READY)] \
        if hasattr(adapter, "extra_step_ids") else []
    ids += ["vm.loadgen.ensure_running"]
    ids += [t.task_id for t in adapter.extra_step_ids(FlowPhase.BEFORE_LOADGEN)] \
        if hasattr(adapter, "extra_step_ids") else []
    ids += list(_LOADGEN_BODY_IDS)
    ids += ["vm.loadgen.destroy", "vm.stack.destroy"]
    return ids


def loadtest_flow_phase_titles(*, runner, request, setup, recipe, adapter) -> list[str]:
    s = adapter.title_suffix
    titles = [f"Ensure stack VM running{s}"]
    titles += [t.title for t in _prelude_static_tasks(runner, request, setup, recipe, adapter.connectivity)]
    titles += [f"Ensure loadgen VM running{s}"]
    titles += [f"{base}{s}" for base in _LOADGEN_BODY_BASE_TITLES]
    titles += [f"Destroy loadgen VM{s}", f"Destroy stack VM{s}"]
    return titles
```

NOTE on `extra_step_ids`: the multipass test's `FakeAdapter` returns `[]`. For B3a the real `MultipassLoadtestAdapter` does not need `extra_step_ids` (multipass has no static extra steps) — guard with `hasattr` so multipass works without it; proxmox (B3b) will add `extra_step_ids` returning its publish steps and the static plan will include them. **Simplify if cleaner:** add a no-op `extra_step_ids(self, phase) -> list: return []` to `MultipassLoadtestAdapter` and to the Protocol, and drop the `hasattr` guards. The implementer should pick the cleaner of the two and keep it consistent — verify the chosen form against the parity test.

- [ ] **Step 4: Run the parity test**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_loadtest_flow.py -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario/loadtest_flow.py \
        tools/controlplane/tests/test_loadtest_flow.py
git commit -m "feat(loadtest): derive static task_ids/phase_titles in the flow module"
```

---

## Task 5: Route two-vm through the driver (behavior-identical)

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/scenarios/two_vm_loadtest.py`
- Test: existing `tools/controlplane/tests/test_two_vm_loadtest_plan.py`, `test_two_vm_stack_prelude_argv.py`, `test_two_vm_loadtest_components.py` (the safety net)

Replace two-vm's bespoke `run()` body with a call to `run_loadtest_flow`, and delegate `task_ids`/`phase_titles` to the static-plan functions. The recipe (`_TWO_VM_STACK_PRELUDE_COMPONENTS`) and `build_setup` stay.

- [ ] **Step 1: Baseline (must pass before)**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_two_vm_loadtest_plan.py tools/controlplane/tests/test_two_vm_stack_prelude_argv.py tools/controlplane/tests/test_two_vm_loadtest_components.py -q`
Expected: PASS (baseline).

- [ ] **Step 2: Rewrite `run()` to delegate**

In `two_vm_loadtest.py`, replace the entire `run()` body (and the now-redundant local imports it used) with:

```python
    def run(self, event_listener=None) -> None:
        from controlplane_tool.scenario.loadtest_adapter import MultipassLoadtestAdapter
        from controlplane_tool.scenario.loadtest_flow import run_loadtest_flow
        from workflow_tasks.components.models import ScenarioRecipe

        setup = self._build_setup()
        recipe = ScenarioRecipe(
            name="two-vm-loadtest-stack",
            component_ids=_TWO_VM_STACK_PRELUDE_COMPONENTS,
            requires_managed_vm=True,
        )
        run_loadtest_flow(
            runner=self.runner,
            request=self.request,
            setup=setup,
            recipe=recipe,
            adapter=MultipassLoadtestAdapter(runner=self.runner, request=self.request),
            event_listener=event_listener,
        )
```

- [ ] **Step 3: Delegate `task_ids` / `phase_titles`**

Replace the `task_ids` property (currently `return list(_TWO_VM_STATIC_TASK_IDS)`) and the `phase_titles` property with delegations:

```python
    @property
    def task_ids(self) -> list[str]:
        from controlplane_tool.scenario.loadtest_adapter import MultipassLoadtestAdapter
        from controlplane_tool.scenario.loadtest_flow import loadtest_flow_task_ids
        from workflow_tasks.components.models import ScenarioRecipe

        recipe = ScenarioRecipe(
            name="two-vm-loadtest-stack",
            component_ids=_TWO_VM_STACK_PRELUDE_COMPONENTS,
            requires_managed_vm=True,
        )
        return loadtest_flow_task_ids(
            runner=self.runner, request=self.request, setup=self._build_setup(),
            recipe=recipe, adapter=MultipassLoadtestAdapter(runner=self.runner, request=self.request),
        )

    @property
    def phase_titles(self) -> list[str]:
        from controlplane_tool.scenario.loadtest_adapter import MultipassLoadtestAdapter
        from controlplane_tool.scenario.loadtest_flow import loadtest_flow_phase_titles
        from workflow_tasks.components.models import ScenarioRecipe

        recipe = ScenarioRecipe(
            name="two-vm-loadtest-stack",
            component_ids=_TWO_VM_STACK_PRELUDE_COMPONENTS,
            requires_managed_vm=True,
        )
        return loadtest_flow_phase_titles(
            runner=self.runner, request=self.request, setup=self._build_setup(),
            recipe=recipe, adapter=MultipassLoadtestAdapter(runner=self.runner, request=self.request),
        )
```

You may keep a small private `_recipe()` helper to avoid repeating the `ScenarioRecipe(...)` literal three times (DRY). `_TWO_VM_STATIC_TASK_IDS` can be DELETED if nothing else references it (check the test file — if `test_two_vm_loadtest_plan.py` imports it, keep it as a module constant and assert the delegation equals it; otherwise delete). The now-unused imports (`EnsureVmRunning`, `DestroyVm`, `Workflow`, `RunK6`-era names, `make_loadtest_k6_config`, `build_loadgen_body_tasks`, `LoadgenBodyInputs`, `TimeWindow`, `OrchestratorVmRunner`, `VmFileFetcher`, `HttpPrometheusClient`, `RegisterFunctions`, etc.) move into the driver/adapter — remove whatever ruff flags as unused.

- [ ] **Step 4: Run the golden + parity tests**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_two_vm_loadtest_plan.py tools/controlplane/tests/test_two_vm_stack_prelude_argv.py tools/controlplane/tests/test_two_vm_loadtest_components.py -q`
Expected: PASS, unchanged. If a test asserts on `_TWO_VM_STATIC_TASK_IDS` directly, keep that constant and additionally assert `TwoVmLoadtestPlan(...).task_ids == list(_TWO_VM_STATIC_TASK_IDS)`.

- [ ] **Step 5: Lint**

Run: `uv run --project tools/controlplane ruff check tools/controlplane/src/controlplane_tool/scenario/scenarios/two_vm_loadtest.py`
Expected: clean (remove F401s).

- [ ] **Step 6: Broader safety**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/ -q -k "two_vm or loadtest"`
Expected: 0 failures.

- [ ] **Step 7: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario/scenarios/two_vm_loadtest.py
git commit -m "refactor(two-vm): route run() + static plan through run_loadtest_flow"
```

---

## Task 6: Full-suite verification + sweep

**Files:** none expected (verification).

- [ ] **Step 1: Full controlplane suite**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests -q`
Expected: PASS (1143-level count; the new flow/adapter tests add a few).

- [ ] **Step 2: Confirm azure/proxmox untouched this stage**

Run: `git diff main --stat -- tools/controlplane/src/controlplane_tool/scenario/scenarios/azure_vm_loadtest.py tools/controlplane/src/controlplane_tool/scenario/scenarios/proxmox_vm_loadtest.py`
Expected: EMPTY (B3a touches neither).

- [ ] **Step 3: Lint the new modules**

Run: `uv run --project tools/controlplane ruff check tools/controlplane/src/controlplane_tool/scenario/loadtest_flow.py tools/controlplane/src/controlplane_tool/scenario/loadtest_adapter.py`
Expected: clean.

- [ ] **Step 4: Commit any final cleanup** (skip if nothing changed)

```bash
git add -A
git commit -m "chore(loadtest): B3a cleanup"
```

---

## Real-VM Validation (post-merge gate — NOT in CI)

- [ ] Run `two-vm-loadtest` end-to-end on a real multipass VM (TUI or CLI). Confirm: stack provisions, functions register, k6 installs+runs, results fetched, Prometheus snapshot has data, report written, VMs destroyed. This is the always-required B3a gate (the structural tests prove task-id/title/argv identity, not a live run).

---

## Self-Review Notes

- **Spec coverage:** §4.1 RunContext → Task 1; §4.2 adapter → Task 2; §4.3 driver → Task 3; §4.4 static plan → Task 4; routing multipass (§6 B3a) → Task 5. azure/proxmox adapters + routing are explicitly B3b/B3c, not this plan.
- **Refinement vs spec:** the adapter gained two members not spelled out in §4.2 — `prepare_loadgen(ctx)` (multipass k6-script upload, a non-emitting side effect two-vm does today) and `create_run_dir()` (the local results dir). Both are faithful to the spec's intent ("adapter supplies lifecycle-specific pieces") and necessary for behavior identity. `extra_step_ids(phase)` is the static-plan counterpart of `extra_steps(phase, ctx)`.
- **Behavior identity is the gate:** Task 5's success criterion is the unchanged two-vm golden + argv + components tests. If any diverges, the driver/adapter is wrong — fix the driver, never weaken the golden.
- **Type consistency:** `RunContext` fields, `InstallEndpoint`, `LoadtestConnectivityAdapter` members, and the `loadtest_flow_*`/`run_loadtest_flow` signatures are used identically across Tasks 2–5. The loadgen body task_ids/base-titles constants are defined once in Task 4 and reused conceptually by the driver in Task 3 (the driver builds titles with the same base strings + suffix — keep them in sync; consider importing `_LOADGEN_BODY_IDS`/`_LOADGEN_BODY_BASE_TITLES` into the driver to avoid duplication).
