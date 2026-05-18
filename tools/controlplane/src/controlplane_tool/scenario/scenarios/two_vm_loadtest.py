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
from workflow_tasks.loadtest.models import K6Config, K6Stage, PrometheusQuery
from workflow_tasks.vm.models import VmConfig

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.infra.vm_lifecycle_adapters import MultipassVmAdapter
from controlplane_tool.loadtest.loadtest_adapters import HttpPrometheusClient, VmFileFetcher
from controlplane_tool.scenario.catalog import ScenarioDefinition
from controlplane_tool.scenario.components.executor import ScenarioPlanStep
from controlplane_tool.scenario.two_vm_loadtest_config import (
    two_vm_control_plane_url,
    two_vm_load_stages,
    two_vm_prometheus_url,
    two_vm_remote_paths,
    two_vm_target_function,
)

if TYPE_CHECKING:
    from controlplane_tool.e2e.e2e_runner import E2eRunner


_PROMETHEUS_QUERIES: tuple[PrometheusQuery, ...] = (
    PrometheusQuery("function_dispatch_total", "function_dispatch_total", required=True),
    PrometheusQuery("function_success_total", "function_success_total", required=True),
    PrometheusQuery("function_error_total", "function_error_total"),
    PrometheusQuery("function_latency_ms", "function_latency_ms"),
    PrometheusQuery("function_e2e_latency_ms", "function_e2e_latency_ms"),
    PrometheusQuery("process_cpu_usage", "process_cpu_usage"),
    PrometheusQuery("jvm_memory_used_bytes", "jvm_memory_used_bytes"),
)

_STATIC_TASK_IDS: tuple[str, ...] = (
    "vm.stack.ensure_running",
    "vm.loadgen.ensure_running",
    "loadgen.install_k6",
    "loadgen.run_k6",
    "loadgen.fetch_results",
    "metrics.prometheus_snapshot",
    "loadtest.write_report",
    "vm.loadgen.destroy",
)


@dataclass
class TwoVmLoadtestPlan:
    scenario: ScenarioDefinition
    request: E2eRequest
    steps: list[ScenarioPlanStep]
    runner: "E2eRunner" = field(repr=False, compare=False)

    @property
    def task_ids(self) -> list[str]:
        return list(_STATIC_TASK_IDS)

    def run(self, event_listener=None) -> None:
        from controlplane_tool.e2e.two_vm_loadtest_runner import TwoVmLoadtestRunner

        request = self.request
        vm_runner_impl = TwoVmLoadtestRunner(repo_root=self.runner.paths.workspace_root)
        lifecycle = MultipassVmAdapter(vm_runner_impl.vm)

        stack_config = VmConfig(
            name=request.vm.name,
            cpus=getattr(request.vm, "cpus", 4),
            memory=getattr(request.vm, "memory", "12G"),
            disk=getattr(request.vm, "disk", "40G"),
        )
        loadgen_config = VmConfig(
            name=request.loadgen_vm.name,
            cpus=getattr(request.loadgen_vm, "cpus", 2),
            memory=getattr(request.loadgen_vm, "memory", "2G"),
            disk=getattr(request.loadgen_vm, "disk", "10G"),
        )

        ensure_stack = EnsureVmRunning(
            task_id="vm.stack.ensure_running",
            title="Ensure stack VM running",
            lifecycle=lifecycle,
            config=stack_config,
        )
        ensure_loadgen = EnsureVmRunning(
            task_id="vm.loadgen.ensure_running",
            title="Ensure loadgen VM running",
            lifecycle=lifecycle,
            config=loadgen_config,
        )

        with workflow_step(task_id=ensure_stack.task_id, title=ensure_stack.title):
            stack_info = ensure_stack.run()
        with workflow_step(task_id=ensure_loadgen.task_id, title=ensure_loadgen.title):
            loadgen_info = ensure_loadgen.run()

        remote_home = loadgen_info.home
        remote_paths = two_vm_remote_paths(
            remote_home,
            payload_name=request.k6_payload.name if request.k6_payload is not None else None,
        )
        run_dir = vm_runner_impl._create_run_dir()

        k6_config = K6Config(
            script_path=Path(remote_paths.script_path),
            target_url=two_vm_control_plane_url(request.vm, host=stack_info.host),
            summary_output_path=Path(remote_paths.summary_path),
            stages=tuple(
                K6Stage(duration=d, target=t)
                for d, t in two_vm_load_stages(request)
            ),
            env={
                "NANOFAAS_URL": two_vm_control_plane_url(request.vm, host=stack_info.host),
                "NANOFAAS_FUNCTION": two_vm_target_function(request),
                **(
                    {"NANOFAAS_PAYLOAD": str(remote_paths.payload_path)}
                    if remote_paths.payload_path
                    else {}
                ),
            },
            vus=getattr(request, "k6_vus", None),
            duration=getattr(request, "k6_duration", None),
            payload_path=Path(remote_paths.payload_path) if remote_paths.payload_path else None,
        )

        vm = vm_runner_impl.vm
        loadgen_request = request.loadgen_vm

        class _LoadgenVmRunner:
            def run_vm_command(self_, argv, *, env, remote_dir, dry_run):  # noqa: N805
                return vm.exec_argv(
                    loadgen_request,
                    argv,
                    env=env or None,
                    cwd=remote_dir,
                    dry_run=dry_run,
                )

        loadgen_runner = _LoadgenVmRunner()
        fetcher = VmFileFetcher(vm=vm, request=loadgen_request)
        prom_client = HttpPrometheusClient(
            url=two_vm_prometheus_url(request.vm, host=stack_info.host)
        )

        k6_task = RunK6(
            task_id="loadgen.run_k6",
            title="Run k6 loadtest",
            runner=loadgen_runner,
            config=k6_config,
            remote_dir=remote_home,
        )

        workflow = Workflow(
            tasks=[
                InstallK6(
                    task_id="loadgen.install_k6",
                    title="Install k6 on loadgen VM",
                    runner=loadgen_runner,
                    remote_dir=remote_home,
                ),
                k6_task,
                FetchVmResults(
                    task_id="loadgen.fetch_results",
                    title="Fetch k6 results from loadgen VM",
                    fetcher=fetcher,
                    remote_source=remote_paths.summary_path,
                    local_dest=run_dir,
                ),
                CapturePrometheusSnapshot(
                    task_id="metrics.prometheus_snapshot",
                    title="Capture Prometheus snapshots",
                    client=prom_client,
                    queries=_PROMETHEUS_QUERIES,
                    window=lambda: TimeWindow(
                        start=k6_task.result.started_at,
                        end=k6_task.result.ended_at,
                    ),
                    output_dir=run_dir,
                ),
                WriteK6Report(
                    task_id="loadtest.write_report",
                    title="Write loadtest report",
                    data_dir=run_dir,
                    output_dir=run_dir,
                ),
            ],
            cleanup_tasks=[
                DestroyVm(
                    task_id="vm.loadgen.destroy",
                    title="Destroy loadgen VM",
                    lifecycle=lifecycle,
                    info=loadgen_info,
                ),
            ],
        )
        workflow.run()


def build_two_vm_loadtest_plan(
    runner: "E2eRunner",
    request: E2eRequest,
) -> TwoVmLoadtestPlan:
    from controlplane_tool.scenario.catalog import resolve_scenario

    scenario = resolve_scenario("two-vm-loadtest")
    return TwoVmLoadtestPlan(scenario=scenario, request=request, steps=[], runner=runner)
