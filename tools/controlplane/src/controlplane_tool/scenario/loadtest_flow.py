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

def _ensure_vm_task(task_id: str, title: str, lifecycle, config):
    """Build (do NOT run) the EnsureVmRunning task — kept separate so the driver
    can run it via either the native workflow_step path or the event-emitting path."""
    return EnsureVmRunning(task_id=task_id, title=title, lifecycle=lifecycle, config=config)


def _ensure_vm(task_id: str, title: str, lifecycle, config):
    task = _ensure_vm_task(task_id, title, lifecycle, config)
    with workflow_step(task_id=task.task_id, title=task.title):
        return task.run()


def _build_prelude_tasks(runner, request, setup, recipe, connectivity,
                         special_handler=None, context_selector=None) -> list:
    from controlplane_tool.scenario.scenarios._workflow_assembly import build_command_tasks
    return build_command_tasks(
        runner, request, setup, recipe,
        connectivity=connectivity,
        special_handler=special_handler,
        context_selector=context_selector,
    )


def _run_workflow(tasks: list, cleanup_tasks: list | None = None) -> None:
    Workflow(tasks=tasks, cleanup_tasks=cleanup_tasks or []).run()


def _adapter_connectivity(adapter, ctx, *, resolve_host: bool):
    """adapter.connectivity_for is the generalized resolver (added in Task 5); fall
    back to the static adapter.connectivity attribute for adapters/fakes that
    predate it."""
    fn = getattr(adapter, "connectivity_for", None)
    if fn is not None:
        return fn(ctx, resolve_host=resolve_host)
    return adapter.connectivity


def _two_vm_remote_paths_for(request, ctx: RunContext):
    from controlplane_tool.scenario.two_vm_loadtest_config import two_vm_remote_paths
    return two_vm_remote_paths(
        ctx.loadgen_info.home,
        payload_name=request.k6_payload.name if request.k6_payload is not None else None,
    )


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


class _StepEmitter:
    """Emits ScenarioStepEvent(running -> success/failed) per executed task.

    Threads a sequential step_index across the whole flow; total_steps is the
    length of the static plan. Used only when adapter.emits_step_events() is True;
    the multipass path never constructs one (it uses the native workflow_step path).
    """

    def __init__(self, event_listener, total_steps: int) -> None:
        self._listener = event_listener
        self._total = total_steps
        self._index = 0

    def _step(self, task):
        from controlplane_tool.scenario.components.executor import ScenarioPlanStep
        return ScenarioPlanStep(
            summary=task.title,
            command=["python", "-c", f"# {task.task_id}"],
            step_id=task.task_id,
        )

    def _emit(self, step_index, task, status, error=None) -> None:
        if self._listener is None:
            return
        from controlplane_tool.e2e.e2e_runner import ScenarioStepEvent
        self._listener(
            ScenarioStepEvent(
                step_index=step_index,
                total_steps=self._total,
                step=self._step(task),
                status=status,
                error=error,
            )
        )

    def run_task(self, task):
        """Run a single task wrapped in running/success/failed emission."""
        self._index += 1
        idx = self._index
        self._emit(idx, task, "running")
        try:
            result = task.run()
        except Exception as exc:
            self._emit(idx, task, "failed", error=str(exc))
            # Tag the failing step's title so the path-(a) cleanup wrapper can
            # format the scenario error exactly like proxmox's _run_prelude_workflow.
            try:
                exc.loadtest_step_title = task.title  # type: ignore[attr-defined]
            except Exception:  # noqa: BLE001
                pass
            raise
        self._emit(idx, task, "success")
        return result

    def run_tasks(self, tasks: list, cleanup_tasks: list | None = None):
        """Run a list of body tasks, then cleanup tasks. On a body failure the
        cleanup tasks still run (native Workflow semantics), and the original
        error is re-raised unwrapped — matching the proxmox tail path."""
        main_error: BaseException | None = None
        for task in tasks:
            try:
                self.run_task(task)
            except BaseException as exc:  # noqa: BLE001
                main_error = exc
                break
        for task in cleanup_tasks or []:
            try:
                self.run_task(task)
            except Exception:  # noqa: BLE001
                pass
        if main_error is not None:
            raise main_error


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

    Two emission modes (selected by ``adapter.emits_step_events()``):
    - False (multipass): the historical native ``workflow_step`` / ``Workflow``
      path, byte-for-byte unchanged; ``event_listener`` is ignored and no
      failure-cleanup wrapping is applied.
    - True (proxmox et al.): each executed task is wrapped to emit
      ``ScenarioStepEvent`` running/success/failed with a sequential ``step_index``
      and constant ``total_steps``, and the ensure/prelude/register region is
      wrapped so a failure triggers ``adapter.cleanup_on_failure`` + a scenario-
      formatted error.
    """
    if adapter.emits_step_events():
        _run_loadtest_flow_emitting(
            runner=runner, request=request, setup=setup, recipe=recipe,
            adapter=adapter, event_listener=event_listener,
        )
    else:
        _run_loadtest_flow_native(
            runner=runner, request=request, setup=setup, recipe=recipe, adapter=adapter,
        )


def _run_loadtest_flow_native(*, runner, request, setup, recipe, adapter) -> None:
    """Historical two-vm/multipass path — MUST stay byte-identical to B3a."""
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
    prelude = _build_prelude_tasks(
        runner, request, setup, recipe, _adapter_connectivity(adapter, ctx, resolve_host=True)
    )
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
    adapter.register_functions(ctx)

    # ── 6. Loadgen body workflow ────────────────────────────────────────────
    body = _build_loadgen_body(runner, request, adapter, ctx)
    cleanup = _destroy_tasks(adapter, ctx, request)
    _run_workflow(body, cleanup_tasks=cleanup)


def _destroy_tasks(adapter, ctx: RunContext, request) -> list:
    s = adapter.title_suffix
    if not getattr(request, "cleanup_vm", True):
        return []
    return [
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


def _run_loadtest_flow_emitting(*, runner, request, setup, recipe, adapter, event_listener) -> None:
    """Event-emitting path (proxmox et al.): emits ScenarioStepEvents per task and
    applies failure-cleanup (path a) around the ensure/prelude/register region."""
    s = adapter.title_suffix
    ctx = RunContext()
    emitter = _StepEmitter(
        event_listener,
        total_steps=len(loadtest_flow_task_ids(
            runner=runner, request=request, setup=setup, recipe=recipe, adapter=adapter,
        )),
    )

    # ── ensure-stack -> prelude -> ensure-loadgen -> prepare -> register ─────
    # Wrapped in failure-cleanup (path a): on any exception, run
    # adapter.cleanup_on_failure() and re-raise a scenario-formatted error.
    try:
        prelude = _build_prelude_tasks(
            runner, request, setup, recipe,
            _adapter_connectivity(adapter, ctx, resolve_host=True),
            special_handler=adapter.prelude_special_handler(ctx),
            context_selector=adapter.prelude_context_selector(ctx),
        )
        for task in prelude:
            emitter.run_task(task)

        stack_task = _ensure_vm_task(
            "vm.stack.ensure_running",
            f"Ensure stack VM running{s}",
            adapter.stack_lifecycle(),
            setup.vm_config,
        )
        ctx.stack_info = emitter.run_task(stack_task)
        ctx.stack_host = getattr(ctx.stack_info, "host", None)

        for task in adapter.extra_steps(FlowPhase.AFTER_STACK_READY, ctx):
            emitter.run_task(task)

        loadgen_task = _ensure_vm_task(
            "vm.loadgen.ensure_running",
            f"Ensure loadgen VM running{s}",
            adapter.loadgen_lifecycle(),
            _loadgen_vm_config(request),
        )
        ctx.loadgen_info = emitter.run_task(loadgen_task)

        ctx.remote_paths = _two_vm_remote_paths_for(request, ctx)
        ctx.run_dir = adapter.create_run_dir()
        ctx.control_plane_url = adapter.control_plane_url(ctx)
        ctx.prometheus_url = adapter.prometheus_url(ctx)

        for task in adapter.extra_steps(FlowPhase.BEFORE_LOADGEN, ctx):
            emitter.run_task(task)
        adapter.prepare_loadgen(ctx)

        adapter.register_functions(ctx)
    except Exception as exc:
        _emitting_failure_cleanup(adapter, request, exc)
        raise  # unreachable: _emitting_failure_cleanup always raises

    # ── body + cleanup: native Workflow cleanup semantics (path b) ──────────
    body = _build_loadgen_body(runner, request, adapter, ctx)
    cleanup = _destroy_tasks(adapter, ctx, request)
    emitter.run_tasks(body, cleanup_tasks=cleanup)


def _emitting_failure_cleanup(adapter, request, exc: Exception):
    """Path (a) failure handling: run adapter.cleanup_on_failure() and raise a
    scenario-formatted error joined with any cleanup errors. Always raises."""
    title = getattr(exc, "loadtest_step_title", None)
    wrapped = (
        f"Scenario '{getattr(request, 'scenario', '?')}' failed at step "
        f"'{title}': {exc}"
        if title is not None
        else f"Scenario '{getattr(request, 'scenario', '?')}' failed: {exc}"
    )
    cleanup_errors = adapter.cleanup_on_failure(exc)
    if cleanup_errors:
        raise RuntimeError(
            f"{wrapped}\n\nCleanup failed:\n" + "\n".join(cleanup_errors)
        ) from exc
    raise RuntimeError(wrapped) from exc


# ---------------------------------------------------------------------------
# Static-plan helpers — derive the dry-run plan from recipe + adapter.
# Kept as module-level functions so tests can monkeypatch them.
# ---------------------------------------------------------------------------

def _static_prelude_hooks(adapter):
    """Resolve the adapter's prelude special_handler/context_selector for the static
    (resolve_host=False) plan. Adapters without these capabilities (multipass) yield
    (None, None) so the static path is byte-identical to before."""
    sh_fn = getattr(adapter, "prelude_special_handler", None)
    cs_fn = getattr(adapter, "prelude_context_selector", None)
    special_handler = sh_fn(None) if sh_fn is not None else None
    context_selector = cs_fn(None, resolve_host=False) if cs_fn is not None else None
    return special_handler, context_selector


def _prelude_static_tasks(runner, request, setup, recipe, connectivity,
                          special_handler=None, context_selector=None) -> list:
    from controlplane_tool.scenario.scenarios._workflow_assembly import build_command_tasks
    return build_command_tasks(
        runner, request, setup, recipe,
        connectivity=connectivity, resolve_host=False,
        special_handler=special_handler, context_selector=context_selector,
    )


def _prelude_static_ids(runner, request, setup, recipe, connectivity,
                        special_handler=None, context_selector=None) -> list:
    return [
        t.task_id
        for t in _prelude_static_tasks(
            runner, request, setup, recipe, connectivity,
            special_handler=special_handler, context_selector=context_selector,
        )
    ]


def _static_hook_kwargs(adapter) -> dict:
    """Only-non-None hooks, so the no-hook (multipass) call site stays byte-identical
    (no extra kwargs passed) and the monkeypatched 5-arg test doubles keep working."""
    special_handler, context_selector = _static_prelude_hooks(adapter)
    kwargs = {}
    if special_handler is not None:
        kwargs["special_handler"] = special_handler
    if context_selector is not None:
        kwargs["context_selector"] = context_selector
    return kwargs


def loadtest_flow_task_ids(*, runner, request, setup, recipe, adapter) -> list:
    """Return the ordered list of task_id strings for the static (dry-run) plan."""
    ids = ["vm.stack.ensure_running"]
    ids += _prelude_static_ids(
        runner, request, setup, recipe,
        _adapter_connectivity(adapter, None, resolve_host=False),
        **_static_hook_kwargs(adapter),
    )
    ids += list(adapter.extra_step_ids(FlowPhase.AFTER_STACK_READY))
    ids += ["vm.loadgen.ensure_running"]
    ids += list(adapter.extra_step_ids(FlowPhase.BEFORE_LOADGEN))
    ids += list(_LOADGEN_BODY_IDS)
    ids += ["vm.loadgen.destroy", "vm.stack.destroy"]
    return ids


def loadtest_flow_phase_titles(*, runner, request, setup, recipe, adapter) -> list:
    """Return the ordered list of phase title strings for the static (dry-run) plan."""
    s = adapter.title_suffix
    titles = [f"Ensure stack VM running{s}"]
    titles += [
        t.title
        for t in _prelude_static_tasks(
            runner, request, setup, recipe,
            _adapter_connectivity(adapter, None, resolve_host=False),
            **_static_hook_kwargs(adapter),
        )
    ]
    titles += list(_adapter_extra_titles(adapter, FlowPhase.AFTER_STACK_READY))
    titles += [f"Ensure loadgen VM running{s}"]
    titles += list(_adapter_extra_titles(adapter, FlowPhase.BEFORE_LOADGEN))
    titles += [f"{base}{s}" for base in _LOADGEN_BODY_BASE_TITLES]
    titles += [f"Destroy loadgen VM{s}", f"Destroy stack VM{s}"]
    return titles


def _adapter_extra_titles(adapter, phase: FlowPhase) -> list:
    """adapter.extra_step_titles is an optional capability (added in Task 3); fall
    back to [] for adapters/fakes that predate it."""
    fn = getattr(adapter, "extra_step_titles", None)
    return list(fn(phase)) if fn is not None else []
