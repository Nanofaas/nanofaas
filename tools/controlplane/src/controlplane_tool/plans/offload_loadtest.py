from __future__ import annotations

from dataclasses import dataclass, replace
import json
from pathlib import Path
from typing import Any, Literal, cast
import urllib.request

from multipass import MultipassClient
from workflow_tasks.core.workflow import Workflow
from workflow_tasks.execution.bindings import RoleBindings
from workflow_tasks.loadtest.models import K6Config
from workflow_tasks.loadtest.offload_conservation import evaluate_conservation
from workflow_tasks.loadtest.ports import RemoteFileFetcher
from workflow_tasks.loadtest.tasks import FetchVmResults, RunK6
from workflow_tasks.tasks.command_task import CommandTask
from workflow_tasks.tasks.models import CommandTaskSpec
from workflow_tasks.vm.models import VmRequest
from workflow_tasks.vm.multipass import resolve_connection_host
from workflow_tasks.workflows.offload_loadtest import (
    OffloadLoadtestRequest,
    cloud_deployment_specs,
    edge_deployment_specs,
    offload_cleanup_specs,
    offload_registration_specs,
)

from controlplane_tool.config.environment import EnvironmentConfig
from controlplane_tool.config.scenario import ScenarioConfig
from controlplane_tool.plans._assembly import workflow_from_specs
from controlplane_tool.plans.validate import _resolve_function

_ACTUATOR_PORT = 30081
_CONTROL_PLANE_PORT = 30080
# The pressure-offload trigger fires in bursts correlated with the control
# plane's 10s throughput window, not smoothly — a lower offloadable rate keeps
# each burst small enough for the cloud's own admission control to absorb.
_OFFLOADABLE_RATE = "10"
_CONTROL_RATE = "20"
_K6_DURATION = "60s"

Role = Literal["stack", "cloud"]


def _role_host(environment: EnvironmentConfig, role: Role, *, dry_run: bool) -> str:
    target = environment.target(role)
    if environment.provider == "local":
        return "127.0.0.1"
    if environment.provider == "multipass":
        return resolve_connection_host(
            VmRequest(lifecycle="multipass", name=target.name),
            MultipassClient(),
            dry_run=dry_run,
        )
    if target.host:
        return target.host
    raise ValueError(f"{environment.provider} {role} requires a host or explicit URLs")


class _RoleRunner:
    """Adapt a role-bound CommandTaskExecutor to the VmCommandRunner protocol RunK6 expects."""

    def __init__(self, bindings: RoleBindings, role: Literal["stack", "loadgen"]) -> None:
        self._executor = bindings.executor_for(role)
        self._role: Literal["stack", "loadgen"] = role

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
                task_id="offload-loadtest.run_k6.inner",
                summary="Run k6",
                argv=argv,
                role=self._role,
                env=env,
                remote_dir=remote_dir,
            ),
            dry_run=dry_run,
        )


def _fetch_text(url: str, *, timeout: float = 10.0) -> str:
    with urllib.request.urlopen(url, timeout=timeout) as response:  # noqa: S310
        return response.read().decode("utf-8")


@dataclass
class EvaluateOffloadConservation:
    task_id: str
    title: str
    k6_summary_path: Path
    edge_metrics_url: str
    cloud_metrics_url: str
    offloadable: str
    control: str
    output_path: Path

    def run(self) -> dict[str, object]:
        k6_summary = json.loads(self.k6_summary_path.read_text(encoding="utf-8"))
        edge_metrics = _fetch_text(self.edge_metrics_url)
        cloud_metrics = _fetch_text(self.cloud_metrics_url)
        report = evaluate_conservation(
            k6_summary=k6_summary,
            edge_metrics=edge_metrics,
            cloud_metrics=cloud_metrics,
            offloadable=self.offloadable,
            control=self.control,
        )
        self.output_path.parent.mkdir(parents=True, exist_ok=True)
        self.output_path.write_text(
            json.dumps(
                {
                    "passed": report.passed,
                    "failures": list(report.failures),
                    "numbers": report.numbers,
                },
                indent=2,
            ),
            encoding="utf-8",
        )
        if not report.passed:
            raise RuntimeError(
                "offload conservation check failed: " + "; ".join(report.failures)
            )
        return {"passed": report.passed}


def build_offload_loadtest_plan(
    config: ScenarioConfig,
    environment: EnvironmentConfig,
    bindings: RoleBindings,
    *,
    run_dir: Path,
    repo_root: Path | None = None,
    fetcher: RemoteFileFetcher | None = None,
    dry_run: bool = False,
) -> Workflow:
    if config.workflow != "offload-loadtest":
        raise ValueError("offload load-test plan requires an offload-loadtest scenario")
    if len(config.functions) != 2:
        raise ValueError(
            "offload-loadtest requires exactly two functions: [offloadable, control]"
        )
    offloadable_key, control_key = config.functions
    # timeout_ms is also the offload gateway's remote-call budget (edge gives up
    # locally once it elapses) — the default 5s is too tight for a cross-VM hop
    # to a function pod that may still be warming up under real load.
    offloadable = replace(
        _resolve_function(config, offloadable_key),
        concurrency=2,
        queue_size=8,
        timeout_ms=15000,
    )
    control = replace(_resolve_function(config, control_key), concurrency=2, queue_size=8)
    request = OffloadLoadtestRequest(offloadable=offloadable, control=control, build=config.build)

    root = repo_root or Path.cwd()
    edge_host = _role_host(environment, "stack", dry_run=dry_run)
    cloud_host = _role_host(environment, "cloud", dry_run=dry_run)
    edge_url = f"http://{edge_host}:{_CONTROL_PLANE_PORT}"
    cloud_url = f"http://{cloud_host}:{_CONTROL_PLANE_PORT}"

    deployment_specs = (
        cloud_deployment_specs(request)
        + edge_deployment_specs(request, cloud_url)
        + offload_registration_specs(request)
    )
    workflow = workflow_from_specs(deployment_specs, bindings, cwd=root)

    dedicated_loadgen = "loadgen" in environment.roles
    k6_role: Literal["stack", "loadgen"] = "loadgen" if dedicated_loadgen else "stack"
    remote = dedicated_loadgen and environment.provider != "local"
    if remote:
        role_target = environment.target("loadgen")
        home = role_target.home or (
            "/root" if role_target.user == "root" else f"/home/{role_target.user}"
        )
        script_path = Path(home) / "nanofaas/tools/controlplane/assets/k6/offload-mixed.js"
        summary_path = Path(home) / "nanofaas-loadtest/k6-summary.json"
    else:
        script_path = root / "tools/controlplane/assets/k6/offload-mixed.js"
        summary_path = run_dir / "k6-summary.json"

    preflight_spec = CommandTaskSpec(
        task_id="offload-loadtest.k6.preflight",
        summary="Check k6 is installed",
        argv=("k6", "version"),
        role=k6_role,
    )
    prepare_spec = CommandTaskSpec(
        task_id="offload-loadtest.k6.prepare",
        summary="Prepare k6 summary directory",
        argv=("mkdir", "-p", str(summary_path.parent)),
        role=k6_role,
    )
    executor = cast(Any, bindings.executor_for(k6_role))
    workflow.tasks.extend(
        (
            CommandTask(
                task_id=preflight_spec.task_id,
                title=preflight_spec.summary,
                spec=preflight_spec,
                executor=executor,
            ),
            CommandTask(
                task_id=prepare_spec.task_id,
                title=prepare_spec.summary,
                spec=prepare_spec,
                executor=executor,
            ),
            RunK6(
                task_id="offload-loadtest.run_k6",
                title="Run mixed-policy offload k6 script",
                runner=_RoleRunner(bindings, k6_role),
                config=K6Config(
                    script_path=script_path,
                    target_url=edge_url,
                    summary_output_path=summary_path,
                    env={
                        "NANOFAAS_URL": edge_url,
                        "OFFLOADABLE_FUNCTION": offloadable.name,
                        "CONTROL_FUNCTION": control.name,
                        "OFFLOADABLE_RATE": _OFFLOADABLE_RATE,
                        "CONTROL_RATE": _CONTROL_RATE,
                        "DURATION": _K6_DURATION,
                    },
                ),
                remote_dir=".",
            ),
        )
    )

    local_summary_path = summary_path
    if remote:
        if fetcher is None:
            raise ValueError("fetcher is required to retrieve remote k6 results")
        workflow.tasks.append(
            FetchVmResults(
                task_id="offload-loadtest.fetch_results",
                title="Fetch k6 results",
                fetcher=fetcher,
                remote_source=str(summary_path),
                local_dest=run_dir,
            )
        )
        local_summary_path = run_dir / summary_path.name

    workflow.tasks.append(
        EvaluateOffloadConservation(
            task_id="offload-loadtest.evaluate_conservation",
            title="Evaluate offload conservation",
            k6_summary_path=local_summary_path,
            edge_metrics_url=f"http://{edge_host}:{_ACTUATOR_PORT}/actuator/prometheus",
            cloud_metrics_url=f"http://{cloud_host}:{_ACTUATOR_PORT}/actuator/prometheus",
            offloadable=offloadable.name,
            control=control.name,
            output_path=run_dir / "offload-report.json",
        )
    )

    workflow.cleanup_tasks = workflow_from_specs(
        offload_cleanup_specs(request), bindings, cwd=root
    ).tasks
    return workflow
