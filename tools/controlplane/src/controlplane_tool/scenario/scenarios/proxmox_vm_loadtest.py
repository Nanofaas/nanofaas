from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import TYPE_CHECKING

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
from controlplane_tool.infra.vm_lifecycle_adapters import ProxmoxVmAdapter
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
    two_vm_control_plane_url,
    two_vm_load_stages,
    two_vm_prometheus_url,
    two_vm_remote_paths,
    two_vm_target_function,
)

if TYPE_CHECKING:
    from controlplane_tool.e2e.e2e_runner import E2eRunner


@dataclass
class ProxmoxVmLoadtestPlan:
    scenario: ScenarioDefinition
    request: E2eRequest
    steps: list[ScenarioPlanStep]
    runner: "E2eRunner" = field(repr=False, compare=False)

    @property
    def task_ids(self) -> list[str]:
        return list(LOADTEST_STATIC_TASK_IDS)

    @property
    def phase_titles(self) -> list[str]:
        pre, wf = self._skeleton()
        return [t.title for t in pre] + wf.phase_titles

    def _skeleton(self) -> "tuple[list[EnsureVmRunning], Workflow]":
        """Task objects with None adapters — only task_id and title are valid here."""
        r = self.request
        sc = VmConfig(name=r.vm.name or "", cpus=r.vm.cpus, memory=r.vm.memory, disk=r.vm.disk)
        lc = VmConfig(name=r.loadgen_vm.name or "", cpus=r.loadgen_vm.cpus, memory=r.loadgen_vm.memory, disk=r.loadgen_vm.disk)
        pre = [
            EnsureVmRunning(task_id="vm.stack.ensure_running", title="Ensure stack VM running (Proxmox)", lifecycle=None, config=sc),  # type: ignore[arg-type]
            EnsureVmRunning(task_id="vm.loadgen.ensure_running", title="Ensure loadgen VM running (Proxmox)", lifecycle=None, config=lc),  # type: ignore[arg-type]
        ]
        wf = Workflow(
            tasks=[
                InstallK6(task_id="loadgen.install_k6", title="Install k6 on loadgen VM (Proxmox)", runner=None, remote_dir=None),  # type: ignore[arg-type]
                RunK6(task_id="loadgen.run_k6", title="Run k6 loadtest (Proxmox)", runner=None, config=None, remote_dir=None),  # type: ignore[arg-type]
                FetchVmResults(task_id="loadgen.fetch_results", title="Fetch k6 results from loadgen VM (Proxmox)", fetcher=None, remote_source=None, local_dest=None),  # type: ignore[arg-type]
                CapturePrometheusSnapshot(task_id="metrics.prometheus_snapshot", title="Capture Prometheus snapshots (Proxmox)", client=None, queries=None, window=None, output_dir=None),  # type: ignore[arg-type]
                WriteK6Report(task_id="loadtest.write_report", title="Write loadtest report (Proxmox)", data_dir=None, output_dir=None),  # type: ignore[arg-type]
            ],
            cleanup_tasks=[
                DestroyVm(task_id="vm.loadgen.destroy", title="Destroy loadgen VM (Proxmox)", lifecycle=None, info=None),  # type: ignore[arg-type]
            ],
        )
        return pre, wf

    def run(self, event_listener=None) -> None:
        from controlplane_tool.e2e.two_vm_loadtest_runner import TwoVmLoadtestRunner
        from controlplane_tool.infra.vm.proxmox_vm_adapter import ProxmoxVmOrchestrator

        request = self.request
        proxmox_orch = ProxmoxVmOrchestrator(repo_root=self.runner.paths.workspace_root)
        lifecycle = ProxmoxVmAdapter(proxmox_orch, credentials=request.vm)
        run_dir_creator = TwoVmLoadtestRunner(
            repo_root=self.runner.paths.workspace_root, vm=proxmox_orch
        )

        [s_ensure_stack, s_ensure_loadgen], s_wf = self._skeleton()
        [s_install_k6, s_run_k6, s_fetch, s_prom, s_report] = s_wf.tasks
        [s_destroy] = s_wf.cleanup_tasks

        stack_config = VmConfig(name=request.vm.name, cpus=request.vm.cpus, memory=request.vm.memory, disk=request.vm.disk)
        loadgen_config = VmConfig(name=request.loadgen_vm.name, cpus=request.loadgen_vm.cpus, memory=request.loadgen_vm.memory, disk=request.loadgen_vm.disk)

        ensure_stack = EnsureVmRunning(task_id=s_ensure_stack.task_id, title=s_ensure_stack.title, lifecycle=lifecycle, config=stack_config)
        ensure_loadgen = EnsureVmRunning(task_id=s_ensure_loadgen.task_id, title=s_ensure_loadgen.title, lifecycle=lifecycle, config=loadgen_config)

        with workflow_step(task_id=ensure_stack.task_id, title=ensure_stack.title):
            stack_info = ensure_stack.run()
        with workflow_step(task_id=ensure_loadgen.task_id, title=ensure_loadgen.title):
            loadgen_info = ensure_loadgen.run()

        remote_home = loadgen_info.home
        remote_paths = two_vm_remote_paths(
            remote_home,
            payload_name=request.k6_payload.name if request.k6_payload is not None else None,
        )
        run_dir = run_dir_creator._create_run_dir()  # noqa: SLF001
        control_plane_url = two_vm_control_plane_url(request.vm, host=stack_info.host)

        k6_config = K6Config(
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

        loadgen_runner = OrchestratorVmRunner(proxmox_orch, request.loadgen_vm)
        fetcher = VmFileFetcher(vm=proxmox_orch, request=request.loadgen_vm)
        prom_client = HttpPrometheusClient(
            url=two_vm_prometheus_url(request.vm, host=stack_info.host)
        )

        k6_task = RunK6(task_id=s_run_k6.task_id, title=s_run_k6.title, runner=loadgen_runner, config=k6_config, remote_dir=remote_home)

        workflow = Workflow(
            tasks=[
                InstallK6(task_id=s_install_k6.task_id, title=s_install_k6.title, runner=loadgen_runner, remote_dir=remote_home),
                k6_task,
                FetchVmResults(task_id=s_fetch.task_id, title=s_fetch.title, fetcher=fetcher, remote_source=remote_paths.summary_path, local_dest=run_dir),
                CapturePrometheusSnapshot(
                    task_id=s_prom.task_id, title=s_prom.title,
                    client=prom_client,
                    queries=LOADTEST_PROMETHEUS_QUERIES,
                    window=lambda: TimeWindow(start=k6_task.result.started_at, end=k6_task.result.ended_at),
                    output_dir=run_dir,
                ),
                WriteK6Report(task_id=s_report.task_id, title=s_report.title, data_dir=run_dir, output_dir=run_dir),
            ],
            cleanup_tasks=[
                DestroyVm(task_id=s_destroy.task_id, title=s_destroy.title, lifecycle=lifecycle, info=loadgen_info),
            ],
        )
        workflow.run()


def build_proxmox_vm_loadtest_plan(
    runner: "E2eRunner",
    request: E2eRequest,
) -> ProxmoxVmLoadtestPlan:
    from controlplane_tool.scenario.catalog import resolve_scenario

    scenario = resolve_scenario("proxmox-vm-loadtest")
    return ProxmoxVmLoadtestPlan(scenario=scenario, request=request, steps=[], runner=runner)
