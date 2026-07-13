from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any, cast

from workflow_tasks.core.workflow import Workflow
from workflow_tasks.execution.bindings import RoleBindings
from workflow_tasks.execution.roles import ExecutionRole
from workflow_tasks.loadtest.models import K6Config, K6Stage, PrometheusQuery, TimeWindow
from workflow_tasks.loadtest.autoscaling import (
    ReplicaProbe,
    ReplicaWatcher,
    RunK6WithReplicaWatch,
    VerifyAutoscalingReplicas,
)
from workflow_tasks.loadtest.ports import PrometheusClient, RemoteFileFetcher
from workflow_tasks.loadtest.tasks import (
    CapturePrometheusSnapshot,
    FetchVmResults,
    RunK6,
    WriteK6Report,
)
from workflow_tasks.tasks.command_task import CommandTask
from workflow_tasks.tasks.models import CommandTaskSpec

DEFAULT_PROMETHEUS_QUERIES = (
    PrometheusQuery("function_dispatch_total", "function_dispatch_total", required=True),
    PrometheusQuery("function_success_total", "function_success_total", required=True),
    PrometheusQuery("function_error_total", "function_error_total"),
    PrometheusQuery("function_latency_ms", "function_latency_ms"),
    PrometheusQuery("process_cpu_usage", "process_cpu_usage"),
)


@dataclass(frozen=True, slots=True)
class LoadtestWorkflowRequest:
    control_plane_url: str
    function_name: str
    script_path: Path
    summary_path: Path
    run_dir: Path
    stages: tuple[tuple[str, int], ...]
    prometheus_queries: tuple[PrometheusQuery, ...]
    dedicated_loadgen: bool = False
    fetch_results: bool = False
    payload_path: Path | None = None
    vus: int | None = None
    duration: str | None = None
    autoscaling: bool = False
    namespace: str = "nanofaas-e2e"


class _RoleRunner:
    def __init__(self, bindings: RoleBindings, role: ExecutionRole) -> None:
        self._executor = bindings.executor_for(role)
        self._role: ExecutionRole = role

    def run_vm_command(
        self,
        argv: tuple[str, ...],
        *,
        env: dict[str, str],
        remote_dir: str | None,
        dry_run: bool,
    ) -> Any:
        return self._executor.run(
            CommandTaskSpec(
                task_id="loadgen.run_k6.inner",
                summary="Run k6",
                argv=argv,
                role=self._role,
                env=env,
                remote_dir=remote_dir,
            ),
            dry_run=dry_run,
        )


@dataclass
class _EvaluateK6Gate:
    task_id: str
    title: str
    run_k6: RunK6

    def run(self) -> None:
        if not self.run_k6.result.passed:
            raise RuntimeError("k6 thresholds failed; see report.html")


def _command(
    bindings: RoleBindings,
    role: ExecutionRole,
    task_id: str,
    *argv: str,
) -> CommandTask:
    spec = CommandTaskSpec(
        task_id=task_id,
        summary=task_id.replace(".", " "),
        argv=argv,
        role=role,
    )
    return CommandTask(
        task_id=task_id,
        title=spec.summary,
        spec=spec,
        executor=cast(Any, bindings.executor_for(role)),
    )


def build_loadtest_workflow(
    request: LoadtestWorkflowRequest,
    bindings: RoleBindings,
    *,
    prometheus_client: PrometheusClient,
    fetcher: RemoteFileFetcher | None = None,
) -> Workflow:
    role: ExecutionRole = "loadgen" if request.dedicated_loadgen else "stack"
    if request.fetch_results and fetcher is None:
        raise ValueError("fetcher is required for remote loadgen results")

    preflight = _command(bindings, role, "loadgen.preflight", "k6", "version")
    prepare = _command(
        bindings,
        role,
        "loadgen.prepare",
        "mkdir",
        "-p",
        str(request.summary_path.parent),
    )
    run_k6 = RunK6(
        task_id="loadgen.run_k6.inner" if request.autoscaling else "loadgen.run_k6",
        title="Run k6 load test",
        runner=_RoleRunner(bindings, role),
        config=K6Config(
            script_path=request.script_path,
            target_url=request.control_plane_url,
            summary_output_path=request.summary_path,
            stages=tuple(
                K6Stage(duration=duration, target=target) for duration, target in request.stages
            ),
            env={
                "NANOFAAS_URL": request.control_plane_url,
                "NANOFAAS_FUNCTION": request.function_name,
            },
            vus=request.vus,
            duration=request.duration,
            payload_path=request.payload_path,
        ),
        remote_dir=".",
    )
    watcher: ReplicaWatcher | None = None
    run_task: Any = run_k6
    if request.autoscaling:
        watcher = ReplicaWatcher(
            ReplicaProbe(
                runner=_RoleRunner(bindings, "stack"),
                namespace=request.namespace,
                deployment_name=f"fn-{request.function_name}",
                remote_dir=".",
            )
        )
        run_task = RunK6WithReplicaWatch(
            task_id="loadgen.run_k6",
            title="Run autoscaling k6 load test",
            run_k6=run_k6,
            watcher=watcher,
        )
    tasks: list[Any] = [preflight, prepare, run_task]
    if request.fetch_results:
        assert fetcher is not None
        tasks.append(
            FetchVmResults(
                task_id="loadgen.fetch_results",
                title="Fetch k6 results",
                fetcher=fetcher,
                remote_source=str(request.summary_path),
                local_dest=request.run_dir,
            )
        )
    if watcher is not None:
        tasks.append(
            VerifyAutoscalingReplicas(
                task_id="autoscaling.verify_replicas",
                title="Verify autoscaling replica lifecycle",
                runner=_RoleRunner(bindings, "stack"),
                namespace=request.namespace,
                deployment_name=f"fn-{request.function_name}",
                remote_dir=".",
                watcher=watcher,
            )
        )
    tasks.extend(
        (
            CapturePrometheusSnapshot(
                task_id="metrics.prometheus_snapshot",
                title="Capture Prometheus snapshot",
                client=prometheus_client,
                queries=request.prometheus_queries,
                window=lambda: TimeWindow(
                    start=run_k6.result.started_at,
                    end=run_k6.result.ended_at,
                ),
                output_dir=request.run_dir,
            ),
            WriteK6Report(
                task_id="loadtest.write_report",
                title="Write load-test report",
                data_dir=request.run_dir,
                output_dir=request.run_dir,
            ),
            _EvaluateK6Gate(
                task_id="metrics.evaluate_gate",
                title="Evaluate k6 thresholds",
                run_k6=run_k6,
            ),
        )
    )
    return Workflow(tasks=tasks)
