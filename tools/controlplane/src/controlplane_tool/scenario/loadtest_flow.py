"""Unified loadtest flow driver.

One ordered driver threads a shared RunContext through the canonical loadtest
phases (ensure stack -> prelude -> ensure loadgen -> prepare -> register ->
loadgen body -> cleanup), emitting the same native Workflow/workflow_step events
the per-scenario run()s use today. Per-lifecycle differences are supplied by a
LoadtestConnectivityAdapter (see loadtest_adapter.py).
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum, auto
from pathlib import Path
from typing import Any

from workflow_tasks import DestroyVm, EnsureVmRunning, Workflow, workflow_step


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


# ---------------------------------------------------------------------------
# Module-level thin wrappers — kept separate so tests can monkeypatch them
# without touching heavy collaborators.
# ---------------------------------------------------------------------------

def _ensure_vm(task_id: str, title: str, lifecycle, config):
    task = EnsureVmRunning(task_id=task_id, title=title, lifecycle=lifecycle, config=config)
    with workflow_step(task_id=task.task_id, title=task.title):
        return task.run()


def _build_prelude_tasks(runner, request, setup, recipe, connectivity) -> list:
    from controlplane_tool.scenario.scenarios._workflow_assembly import build_command_tasks
    return build_command_tasks(runner, request, setup, recipe, connectivity=connectivity)


def _run_workflow(tasks: list, cleanup_tasks: list | None = None) -> None:
    Workflow(tasks=tasks, cleanup_tasks=cleanup_tasks or []).run()


def _two_vm_remote_paths_for(request, ctx: RunContext):
    from controlplane_tool.scenario.two_vm_loadtest_config import two_vm_remote_paths
    return two_vm_remote_paths(
        ctx.loadgen_info.home,
        payload_name=request.k6_payload.name if request.k6_payload is not None else None,
    )


def _register_functions(runner, request, setup, ctx: RunContext) -> None:
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


_LOADGEN_BODY_IDS = (
    "loadgen.install_k6",
    "loadgen.run_k6",
    "loadgen.fetch_results",
    "metrics.prometheus_snapshot",
    "loadtest.write_report",
)
_LOADGEN_BODY_BASE_TITLES = (
    "Install k6 on loadgen VM",
    "Run k6 loadtest",
    "Fetch k6 results from loadgen VM",
    "Capture Prometheus snapshots",
    "Write loadtest report",
)


def _build_loadgen_body(runner, request, adapter, ctx: RunContext) -> list:
    from controlplane_tool.loadtest.loadtest_adapters import HttpPrometheusClient
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
            task_ids=_LOADGEN_BODY_IDS,
            titles=tuple(f"{base}{s}" for base in _LOADGEN_BODY_BASE_TITLES),
            runner=adapter.loadgen_runner(ctx),
            fetcher=adapter.fetcher(ctx),
            prometheus_client=HttpPrometheusClient(url=adapter.prometheus_url(ctx)),
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
    """7-phase lifecycle-generic loadtest driver.

    Mirrors two-vm's run() order exactly:
    1. ensure stack VM
    2. prelude (provision/deploy) workflow
    3. ensure loadgen VM
    4. prepare loadgen (remote dirs, k6 script upload) + resolve remote_paths/run_dir/urls
    5. register functions on control plane
    6. loadgen body workflow (install-k6, run-k6, fetch, prometheus, report)
    7. cleanup (destroy VMs if cleanup_vm)
    """
    s = adapter.title_suffix
    ctx = RunContext()

    # ── 1. Ensure stack VM running ──────────────────────────────────────────
    ctx.stack_info = _ensure_vm(
        "vm.stack.ensure_running",
        f"Ensure stack VM running{s}",
        adapter.stack_lifecycle(),
        setup.vm_config,
    )
    ctx.stack_host = getattr(ctx.stack_info, "host", None)

    # ── 2. Stack provisioning prelude + adapter extra steps ─────────────────
    prelude = _build_prelude_tasks(runner, request, setup, recipe, adapter.connectivity)
    prelude += adapter.extra_steps(FlowPhase.AFTER_STACK_READY, ctx)
    _run_workflow(prelude)

    # ── 3. Ensure loadgen VM running ────────────────────────────────────────
    ctx.loadgen_info = _ensure_vm(
        "vm.loadgen.ensure_running",
        f"Ensure loadgen VM running{s}",
        adapter.loadgen_lifecycle(),
        _loadgen_vm_config(request),
    )

    # ── 4. Prepare loadgen: remote paths, run dir, URLs ─────────────────────
    ctx.remote_paths = _two_vm_remote_paths_for(request, ctx)
    ctx.run_dir = adapter.create_run_dir()
    ctx.control_plane_url = adapter.control_plane_url(ctx)
    ctx.prometheus_url = adapter.prometheus_url(ctx)

    pre = adapter.extra_steps(FlowPhase.BEFORE_LOADGEN, ctx)
    if pre:
        _run_workflow(pre)
    adapter.prepare_loadgen(ctx)

    # ── 5. Register functions on control plane ──────────────────────────────
    _register_functions(runner, request, setup, ctx)

    # ── 6. Loadgen body workflow ────────────────────────────────────────────
    body = _build_loadgen_body(runner, request, adapter, ctx)
    cleanup: list = []
    if getattr(request, "cleanup_vm", True):
        cleanup = [
            DestroyVm(
                task_id="vm.loadgen.destroy",
                title=f"Destroy loadgen VM{s}",
                lifecycle=adapter.loadgen_lifecycle(),
                info=ctx.loadgen_info,
            ),
            DestroyVm(
                task_id="vm.stack.destroy",
                title=f"Destroy stack VM{s}",
                lifecycle=adapter.stack_lifecycle(),
                info=ctx.stack_info,
            ),
        ]
    _run_workflow(body, cleanup_tasks=cleanup)
