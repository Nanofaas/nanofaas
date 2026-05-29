from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import TYPE_CHECKING, Any, Callable, cast

from workflow_tasks import (
    CapturePrometheusSnapshot,
    DestroyVm,
    EnsureVmRunning,
    FetchVmResults,
    InstallK6,
    RunK6,
    TimeWindow,
    Workflow,
    WriteK6Report,
    workflow_step,
)
from workflow_tasks.loadtest.models import K6Config, K6Stage
from workflow_tasks.vm.models import VmConfig

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.infra.vm.vm_models import VmRequest
from controlplane_tool.infra.vm_lifecycle_adapters import ProxmoxVmAdapter, VmLifecycleAdapter
from controlplane_tool.loadtest.loadtest_adapters import (
    HttpPrometheusClient,
    OrchestratorVmRunner,
    VmFileFetcher,
)
from controlplane_tool.scenario.catalog import ScenarioDefinition
from controlplane_tool.scenario.components.executor import ScenarioPlanStep
from controlplane_tool.scenario.two_vm_loadtest_config import (
    LOADTEST_PROMETHEUS_QUERIES,
    LOADTEST_STATIC_TASK_IDS,
    TWO_VM_PROMETHEUS_NODE_PORT,
    two_vm_control_plane_url,
    two_vm_load_stages,
    two_vm_remote_paths,
    two_vm_target_function,
)

if TYPE_CHECKING:
    from controlplane_tool.e2e.e2e_runner import E2eRunner


_PROXMOX_LOADTEST_PRELUDE_COMPONENTS = (
    "vm.ensure_running",
    "vm.provision_base",
    "repo.sync_to_vm",
    "registry.ensure_container",
    "images.build_core",
    "images.build_selected_functions",
    "k3s.install",
    "k3s.configure_registry",
    "namespace.install",
    "helm.deploy_control_plane",
    "helm.deploy_function_runtime",
    "cli.build_install_dist",
    "cli.fn_apply_selected",
)


@dataclass(frozen=True)
class _SkeletonStep:
    task_id: str
    title: str


@dataclass(frozen=True)
class _ActionTask:
    task_id: str
    title: str
    action: Callable[[], Any]

    def run(self) -> Any:
        return self.action()


@dataclass
class ProxmoxVmLoadtestPlan:
    scenario: ScenarioDefinition
    request: E2eRequest
    steps: list[ScenarioPlanStep]
    runner: "E2eRunner" = field(repr=False, compare=False)

    @property
    def task_ids(self) -> list[str]:
        pre, wf = self._skeleton()
        return [s.step_id for s in self.steps if s.step_id] + [t.task_id for t in pre] + wf.task_ids

    @property
    def phase_titles(self) -> list[str]:
        pre, wf = self._skeleton()
        return [s.summary for s in self.steps] + [t.title for t in pre] + wf.phase_titles

    def _skeleton(self) -> "tuple[list[EnsureVmRunning | _SkeletonStep], Workflow]":
        """Task objects with None adapters — only task_id and title are valid here."""
        r = self.request
        stack_request, loadgen_request = self._requests()
        sc = VmConfig(
            name=stack_request.name or "",
            cpus=stack_request.cpus,
            memory=stack_request.memory,
            disk=stack_request.disk,
        )
        lc = VmConfig(
            name=loadgen_request.name or "",
            cpus=loadgen_request.cpus,
            memory=loadgen_request.memory,
            disk=loadgen_request.disk,
        )
        pre = [
            EnsureVmRunning(task_id="vm.stack.ensure_running", title="Ensure stack VM running (Proxmox)", lifecycle=None, config=sc),  # type: ignore[arg-type]
            EnsureVmRunning(task_id="vm.loadgen.ensure_running", title="Ensure loadgen VM running (Proxmox)", lifecycle=None, config=lc),  # type: ignore[arg-type]
            _SkeletonStep(task_id="vm.stack.publish_ports", title="Publish Proxmox NAT ports"),
        ]
        wf = Workflow(
            tasks=[
                InstallK6(task_id="loadgen.install_k6", title="Install k6 on loadgen VM (Proxmox)", runner=None, remote_dir=None),  # type: ignore[arg-type]
                RunK6(task_id="loadgen.run_k6", title="Run k6 loadtest (Proxmox)", runner=None, config=None, remote_dir=None),  # type: ignore[arg-type]
                FetchVmResults(task_id="loadgen.fetch_results", title="Fetch k6 results from loadgen VM (Proxmox)", fetcher=None, remote_source=None, local_dest=None),  # type: ignore[arg-type]
                CapturePrometheusSnapshot(task_id="metrics.prometheus_snapshot", title="Capture Prometheus snapshots (Proxmox)", client=None, queries=None, window=None, output_dir=None),  # type: ignore[arg-type]
                WriteK6Report(task_id="loadtest.write_report", title="Write loadtest report (Proxmox)", data_dir=None, output_dir=None),  # type: ignore[arg-type]
            ],
            cleanup_tasks=(
                [
                    DestroyVm(task_id="vm.loadgen.destroy", title="Destroy loadgen VM (Proxmox)", lifecycle=None, info=None),  # type: ignore[arg-type]
                    DestroyVm(task_id="vm.stack.destroy", title="Destroy stack VM (Proxmox)", lifecycle=None, info=None),  # type: ignore[arg-type]
                ]
                if getattr(r, "cleanup_vm", True)
                else []
            ),
        )
        return pre, wf

    def _requests(self) -> tuple[VmRequest, VmRequest]:
        if self.request.vm is None:
            raise ValueError("proxmox-vm-loadtest requires a stack VM request")
        if self.request.loadgen_vm is None:
            raise ValueError("proxmox-vm-loadtest requires a loadgen VM request")
        return self.request.vm, self.request.loadgen_vm

    def run(self, event_listener=None) -> None:
        from controlplane_tool.e2e.e2e_runner import E2ePlan, ScenarioStepEvent
        from controlplane_tool.e2e.two_vm_loadtest_runner import TwoVmLoadtestRunner
        from controlplane_tool.infra.vm.proxmox_vm_adapter import ProxmoxVmOrchestrator

        request = self.request
        stack_request, _ = self._requests()
        proxmox_orch = ProxmoxVmOrchestrator(repo_root=self.runner.paths.workspace_root)
        stack_lifecycle = ProxmoxVmAdapter(proxmox_orch, credentials=stack_request)
        total_steps = len(self.task_ids)

        def _forward_prelude_event(event: ScenarioStepEvent) -> None:
            if event_listener is None:
                return
            event_listener(
                ScenarioStepEvent(
                    step_index=event.step_index,
                    total_steps=total_steps,
                    step=event.step,
                    status=event.status,
                    error=event.error,
                )
            )

        if self.steps:
            try:
                self.runner._execute_steps(
                    E2ePlan(
                        scenario=self.scenario,
                        request=self.request,
                        steps=self.steps,
                    ),
                    event_listener=_forward_prelude_event,
                )
            except Exception as exc:
                cleanup_errors = self._cleanup_proxmox_requests(proxmox_orch)
                if cleanup_errors:
                    raise RuntimeError(
                        f"{exc}\n\nCleanup failed:\n" + "\n".join(cleanup_errors)
                    ) from exc
                raise

        run_dir_creator = TwoVmLoadtestRunner(
            repo_root=self.runner.paths.workspace_root, vm=cast(Any, proxmox_orch)
        )

        try:
            tail_tasks, cleanup_tasks = self._tail_tasks(
                proxmox_orch=proxmox_orch,
                stack_lifecycle=stack_lifecycle,
                run_dir_creator=run_dir_creator,
            )
        except Exception as exc:
            cleanup_errors = self._cleanup_proxmox_requests(proxmox_orch)
            if cleanup_errors:
                raise RuntimeError(
                    f"{exc}\n\nCleanup failed:\n" + "\n".join(cleanup_errors)
                ) from exc
            raise

        self._run_tail_tasks(
            tail_tasks,
            cleanup_tasks,
            event_listener=event_listener,
            offset=len([s for s in self.steps if s.step_id]),
            total_steps=total_steps,
        )

    def _cleanup_proxmox_requests(self, proxmox_orch) -> list[str]:
        if not self.request.cleanup_vm:
            return []
        errors: list[str] = []
        for vm_request in (self.request.loadgen_vm, self.request.vm):
            if vm_request is None:
                continue
            try:
                proxmox_orch.teardown(vm_request)
            except Exception as exc:
                errors.append(str(exc))
        return errors

    def _tail_step(self, task) -> ScenarioPlanStep:
        return ScenarioPlanStep(
            summary=task.title,
            command=["python", "-c", f"# {task.task_id}"],
            step_id=task.task_id,
        )

    def _emit_tail_event(
        self,
        event_listener,
        *,
        step_index: int,
        total_steps: int,
        task,
        status: str,
        error: str | None = None,
    ) -> None:
        if event_listener is None:
            return
        from controlplane_tool.e2e.e2e_runner import ScenarioStepEvent

        event_listener(
            ScenarioStepEvent(
                step_index=step_index,
                total_steps=total_steps,
                step=self._tail_step(task),
                status=status,  # type: ignore[arg-type]
                error=error,
            )
        )

    def _run_tail_task(
        self,
        task,
        *,
        step_index: int,
        total_steps: int,
        event_listener,
    ) -> None:
        self._emit_tail_event(
            event_listener,
            step_index=step_index,
            total_steps=total_steps,
            task=task,
            status="running",
        )
        try:
            with workflow_step(task_id=task.task_id, title=task.title):
                task.run()
        except BaseException as exc:
            self._emit_tail_event(
                event_listener,
                step_index=step_index,
                total_steps=total_steps,
                task=task,
                status="failed",
                error=str(exc),
            )
            raise
        self._emit_tail_event(
            event_listener,
            step_index=step_index,
            total_steps=total_steps,
            task=task,
            status="success",
        )

    def _run_tail_tasks(
        self,
        tasks: list[_ActionTask],
        cleanup_tasks: list[_ActionTask],
        *,
        event_listener,
        offset: int,
        total_steps: int,
    ) -> None:
        main_error: BaseException | None = None

        for task_index, task in enumerate(tasks, start=offset + 1):
            try:
                self._run_tail_task(
                    task,
                    step_index=task_index,
                    total_steps=total_steps,
                    event_listener=event_listener,
                )
            except BaseException as exc:
                main_error = exc
                break

        cleanup_errors: list[str] = []
        cleanup_offset = offset + len(tasks)
        for cleanup_index, task in enumerate(cleanup_tasks, start=cleanup_offset + 1):
            try:
                self._run_tail_task(
                    task,
                    step_index=cleanup_index,
                    total_steps=total_steps,
                    event_listener=event_listener,
                )
            except Exception as exc:
                cleanup_errors.append(str(exc))

        if main_error is not None:
            if cleanup_errors:
                combined = f"{main_error}\n\nCleanup errors:\n" + "\n".join(cleanup_errors)
                raise RuntimeError(combined) from main_error
            raise main_error

        if cleanup_errors:
            raise RuntimeError("Cleanup failed:\n" + "\n".join(cleanup_errors))

    def _tail_tasks(
        self,
        *,
        proxmox_orch,
        stack_lifecycle: VmLifecycleAdapter,
        run_dir_creator,
    ) -> tuple[list[_ActionTask], list[_ActionTask]]:
        request = self.request
        stack_request, loadgen_request = self._requests()
        loadgen_lifecycle = ProxmoxVmAdapter(proxmox_orch, credentials=loadgen_request)
        [s_ensure_stack, s_ensure_loadgen, s_publish_ports], s_wf = self._skeleton()
        [s_install_k6, s_run_k6, s_fetch, s_prom, s_report] = s_wf.tasks
        s_destroy_loadgen, s_destroy_stack = s_wf.cleanup_tasks if s_wf.cleanup_tasks else (None, None)

        state: dict[str, Any] = {}

        stack_config = VmConfig(
            name=stack_request.name or "",
            cpus=stack_request.cpus,
            memory=stack_request.memory,
            disk=stack_request.disk,
        )
        loadgen_config = VmConfig(
            name=loadgen_request.name or "",
            cpus=loadgen_request.cpus,
            memory=loadgen_request.memory,
            disk=loadgen_request.disk,
        )

        ensure_stack = EnsureVmRunning(
            task_id=s_ensure_stack.task_id,
            title=s_ensure_stack.title,
            lifecycle=stack_lifecycle,
            config=stack_config,
        )
        ensure_loadgen = EnsureVmRunning(
            task_id=s_ensure_loadgen.task_id,
            title=s_ensure_loadgen.title,
            lifecycle=loadgen_lifecycle,
            config=loadgen_config,
        )

        def _ensure_stack() -> None:
            state["stack_info"] = ensure_stack.run()

        def _ensure_loadgen() -> None:
            state["loadgen_info"] = ensure_loadgen.run()

        def _publish_ports() -> None:
            stack_guest_host = state["stack_info"].host
            prometheus_host, prometheus_port = proxmox_orch.publish_port(
                stack_request,
                service="PROMETHEUS",
                guest_port=TWO_VM_PROMETHEUS_NODE_PORT,
            )
            state["stack_guest_host"] = stack_guest_host
            state["prometheus_host"] = prometheus_host
            state["prometheus_port"] = prometheus_port

        def _remote_paths():
            if "remote_paths" not in state:
                state["remote_paths"] = two_vm_remote_paths(
                    state["loadgen_info"].home,
                    payload_name=request.k6_payload.name if request.k6_payload is not None else None,
                )
            return state["remote_paths"]

        def _run_dir():
            if "run_dir" not in state:
                state["run_dir"] = run_dir_creator._create_run_dir()  # noqa: SLF001
            return state["run_dir"]

        def _control_plane_url() -> str:
            return two_vm_control_plane_url(stack_request, host=state["stack_guest_host"])

        def _loadgen_runner():
            if "loadgen_runner" not in state:
                state["loadgen_runner"] = OrchestratorVmRunner(proxmox_orch, loadgen_request)
            return state["loadgen_runner"]

        def _k6_config() -> K6Config:
            remote_paths = _remote_paths()
            control_plane_url = _control_plane_url()
            return K6Config(
                script_path=Path(remote_paths.script_path),
                target_url=control_plane_url,
                summary_output_path=Path(remote_paths.summary_path),
                stages=tuple(
                    K6Stage(duration=d, target=t)
                    for d, t in two_vm_load_stages(request)
                ),
                env={
                    "NANOFAAS_URL": control_plane_url,
                    "NANOFAAS_FUNCTION": two_vm_target_function(request),
                    **(
                        {"NANOFAAS_PAYLOAD": str(remote_paths.payload_path)}
                        if remote_paths.payload_path
                        else {}
                    ),
                },
                vus=request.k6_vus,
                duration=request.k6_duration,
                payload_path=Path(remote_paths.payload_path) if remote_paths.payload_path else None,
            )

        def _install_k6() -> None:
            InstallK6(
                task_id=s_install_k6.task_id,
                title=s_install_k6.title,
                runner=cast(Any, _loadgen_runner()),
                remote_dir=state["loadgen_info"].home,
            ).run()

        def _run_k6() -> None:
            k6_task = RunK6(
                task_id=s_run_k6.task_id,
                title=s_run_k6.title,
                runner=cast(Any, _loadgen_runner()),
                config=_k6_config(),
                remote_dir=state["loadgen_info"].home,
            )
            state["k6_task"] = k6_task
            k6_task.run()

        def _fetch_results() -> None:
            FetchVmResults(
                task_id=s_fetch.task_id,
                title=s_fetch.title,
                fetcher=VmFileFetcher(vm=proxmox_orch, request=loadgen_request),
                remote_source=_remote_paths().summary_path,
                local_dest=_run_dir(),
            ).run()

        def _capture_prometheus() -> None:
            k6_task = state["k6_task"]
            CapturePrometheusSnapshot(
                task_id=s_prom.task_id,
                title=s_prom.title,
                client=HttpPrometheusClient(
                    url=f"http://{state['prometheus_host']}:{state['prometheus_port']}"
                ),
                queries=LOADTEST_PROMETHEUS_QUERIES,
                window=lambda: TimeWindow(start=k6_task.result.started_at, end=k6_task.result.ended_at),
                output_dir=_run_dir(),
            ).run()

        def _write_report() -> None:
            WriteK6Report(
                task_id=s_report.task_id,
                title=s_report.title,
                data_dir=_run_dir(),
                output_dir=_run_dir(),
            ).run()

        def _destroy_loadgen() -> None:
            if s_destroy_loadgen is not None and "loadgen_info" in state:
                DestroyVm(
                    task_id=s_destroy_loadgen.task_id,
                    title=s_destroy_loadgen.title,
                    lifecycle=loadgen_lifecycle,
                    info=state["loadgen_info"],
                ).run()
            else:
                proxmox_orch.teardown(loadgen_request)

        def _destroy_stack() -> None:
            if s_destroy_stack is not None and "stack_info" in state:
                DestroyVm(
                    task_id=s_destroy_stack.task_id,
                    title=s_destroy_stack.title,
                    lifecycle=stack_lifecycle,
                    info=state["stack_info"],
                ).run()
            else:
                proxmox_orch.teardown(stack_request)

        tasks = [
            _ActionTask(s_ensure_stack.task_id, s_ensure_stack.title, _ensure_stack),
            _ActionTask(s_ensure_loadgen.task_id, s_ensure_loadgen.title, _ensure_loadgen),
            _ActionTask(s_publish_ports.task_id, s_publish_ports.title, _publish_ports),
            _ActionTask(s_install_k6.task_id, s_install_k6.title, _install_k6),
            _ActionTask(s_run_k6.task_id, s_run_k6.title, _run_k6),
            _ActionTask(s_fetch.task_id, s_fetch.title, _fetch_results),
            _ActionTask(s_prom.task_id, s_prom.title, _capture_prometheus),
            _ActionTask(s_report.task_id, s_report.title, _write_report),
        ]
        cleanup_tasks = (
            [
                _ActionTask(s_destroy_loadgen.task_id, s_destroy_loadgen.title, _destroy_loadgen),
                _ActionTask(s_destroy_stack.task_id, s_destroy_stack.title, _destroy_stack),
            ]
            if request.cleanup_vm and s_destroy_loadgen is not None and s_destroy_stack is not None
            else []
        )
        return tasks, cleanup_tasks


def build_proxmox_vm_loadtest_plan(
    runner: "E2eRunner",
    request: E2eRequest,
) -> ProxmoxVmLoadtestPlan:
    from controlplane_tool.e2e.e2e_runner import plan_recipe_steps
    from controlplane_tool.scenario.catalog import resolve_scenario

    scenario = resolve_scenario("proxmox-vm-loadtest")
    steps = plan_recipe_steps(
        runner.paths.workspace_root,
        request,
        "proxmox-vm-loadtest",
        shell=runner.shell,
        manifest_root=runner.manifest_root,
        host_resolver=runner._host_resolver,
        multipass_client=runner._multipass_client,
        component_ids=_PROXMOX_LOADTEST_PRELUDE_COMPONENTS,
    )
    return ProxmoxVmLoadtestPlan(
        scenario=scenario,
        request=request,
        steps=steps,
        runner=runner,
    )
