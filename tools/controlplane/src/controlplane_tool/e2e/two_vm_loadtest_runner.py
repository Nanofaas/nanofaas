from __future__ import annotations

import json
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path

from controlplane_tool.core.shell_backend import ShellBackend, SubprocessShell
from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.infra.vm.azure_vm_adapter import AzureVmOrchestrator
from controlplane_tool.infra.vm.vm_adapter import VmOrchestrator
from controlplane_tool.infra.vm.vm_models import VmRequest
from controlplane_tool.loadtest.prometheus_snapshots import capture_prometheus_snapshots
from controlplane_tool.loadtest.report import render_report
from controlplane_tool.loadtest.remote_k6 import RemoteK6RunConfig, build_k6_command
from controlplane_tool.scenario.two_vm_loadtest_config import (
    two_vm_control_plane_url,
    two_vm_load_stages,
    two_vm_prometheus_url,
    two_vm_remote_paths,
    two_vm_target_function,
)
from controlplane_tool.workspace.paths import ToolPaths


@dataclass(frozen=True, slots=True)
class TwoVmK6Result:
    run_dir: Path
    k6_summary_path: Path
    target_function: str
    started_at: datetime
    ended_at: datetime


@dataclass(frozen=True, slots=True)
class TwoVmLoadtestReport:
    final_status: str
    target_function: str
    control_plane_url: str
    prometheus_url: str
    k6_summary_path: Path
    prometheus_snapshot_path: Path
    script_path: Path
    payload_path: Path | None = None


def _metrics_from_prometheus_snapshot(path: Path) -> dict[str, list[dict[str, float | str]]]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError):
        return {}
    queries = payload.get("queries", {})
    if not isinstance(queries, dict):
        return {}
    metrics: dict[str, list[dict[str, float | str]]] = {}
    for name, entry in queries.items():
        if not isinstance(name, str) or not isinstance(entry, dict):
            continue
        points = entry.get("points", [])
        if isinstance(points, list):
            metrics[name] = points
    return metrics


def write_two_vm_report(report: TwoVmLoadtestReport, run_dir: Path) -> Path:
    summary = {
        "profile_name": "two-vm-loadtest",
        "run_dir": str(run_dir),
        "final_status": report.final_status,
        "steps": [
            {
                "name": "load_k6",
                "status": report.final_status,
                "duration_ms": 0,
                "detail": f"k6 summary: {report.k6_summary_path}",
            },
            {
                "name": "prometheus_snapshot",
                "status": "passed",
                "duration_ms": 0,
                "detail": f"prometheus snapshots: {report.prometheus_snapshot_path}",
            },
        ],
        "metrics": _metrics_from_prometheus_snapshot(report.prometheus_snapshot_path),
        "two_vm_loadtest": {
            "target_function": report.target_function,
            "control_plane_url": report.control_plane_url,
            "prometheus_url": report.prometheus_url,
            "k6_summary_path": str(report.k6_summary_path),
            "prometheus_snapshot_path": str(report.prometheus_snapshot_path),
            "script_path": str(report.script_path),
            "payload_path": str(report.payload_path) if report.payload_path else None,
        },
    }
    summary_path = run_dir / "summary.json"
    summary_path.write_text(json.dumps(summary, indent=2), encoding="utf-8")
    render_report(summary=summary, output_dir=run_dir)
    return summary_path


class TwoVmLoadtestRunner:
    def __init__(
        self,
        *,
        repo_root: Path,
        vm: VmOrchestrator | AzureVmOrchestrator | None = None,
        shell: ShellBackend | None = None,
        host_resolver: Callable[[VmRequest], str] | None = None,
        runs_root: Path | None = None,
    ) -> None:
        self.repo_root = Path(repo_root)
        self.paths = ToolPaths.repo_root(self.repo_root)
        self.shell = shell or SubprocessShell()
        self.vm = vm or VmOrchestrator(self.repo_root, shell=self.shell)
        self.host_resolver = host_resolver
        self.runs_root = Path(runs_root) if runs_root is not None else self.paths.runs_dir

    def run_k6(self, request: E2eRequest) -> TwoVmK6Result:
        if request.vm is None:
            raise ValueError("two-vm-loadtest requires a stack VM request")
        if request.loadgen_vm is None:
            raise ValueError("two-vm-loadtest requires a loadgen VM request")

        run_dir = self._create_run_dir()
        remote_paths = two_vm_remote_paths(
            self.vm.remote_home(request.loadgen_vm),
            payload_name=request.k6_payload.name if request.k6_payload is not None else None,
        )

        self._check(
            self.vm.exec_argv(
                request.loadgen_vm,
                ("mkdir", "-p", remote_paths.scripts_dir, remote_paths.payloads_dir, remote_paths.results_dir),
            )
        )
        self._check(
            self.vm.transfer_to(
                request.loadgen_vm,
                source=self._script_path(request),
                destination=remote_paths.script_path,
            )
        )

        if request.k6_payload is not None and remote_paths.payload_path is not None:
            self._check(
                self.vm.transfer_to(
                    request.loadgen_vm,
                    source=request.k6_payload,
                    destination=remote_paths.payload_path,
                )
            )

        target_function = two_vm_target_function(request)
        command = build_k6_command(
            RemoteK6RunConfig(
                script_path=Path(remote_paths.script_path),
                summary_path=Path(remote_paths.summary_path),
                control_plane_url=two_vm_control_plane_url(request.vm, host=self._host(request.vm)),
                function_name=target_function,
                payload_path=Path(remote_paths.payload_path) if remote_paths.payload_path is not None else None,
                stages=two_vm_load_stages(request),
                custom_script=request.k6_script is not None,
                vus=request.k6_vus,
                duration=request.k6_duration,
            )
        )
        started_at = datetime.now(timezone.utc)
        self._check(self.vm.exec_argv(request.loadgen_vm, command, cwd=remote_paths.root_dir))
        ended_at = datetime.now(timezone.utc)
        if ended_at <= started_at:
            ended_at = started_at + timedelta(seconds=1)

        local_summary = run_dir / "k6-summary.json"
        self._check(
            self.vm.transfer_from(
                request.loadgen_vm,
                source=remote_paths.summary_path,
                destination=local_summary,
            )
        )
        return TwoVmK6Result(
            run_dir=run_dir,
            k6_summary_path=local_summary,
            target_function=target_function,
            started_at=started_at,
            ended_at=ended_at,
        )

    def capture_prometheus_snapshots(self, request: E2eRequest, k6_result: TwoVmK6Result) -> Path:
        if request.vm is None:
            raise ValueError("two-vm-loadtest requires a stack VM request")
        return capture_prometheus_snapshots(
            prometheus_url=two_vm_prometheus_url(request.vm, host=self._host(request.vm)),
            output_dir=k6_result.run_dir,
            start=k6_result.started_at,
            end=k6_result.ended_at,
        )

    def write_report(
        self,
        request: E2eRequest,
        k6_result: TwoVmK6Result,
        prometheus_snapshot_path: Path,
    ) -> Path:
        if request.vm is None:
            raise ValueError("two-vm-loadtest requires a stack VM request")
        return write_two_vm_report(
            TwoVmLoadtestReport(
                final_status="passed",
                target_function=k6_result.target_function,
                control_plane_url=two_vm_control_plane_url(request.vm, host=self._host(request.vm)),
                prometheus_url=two_vm_prometheus_url(request.vm, host=self._host(request.vm)),
                k6_summary_path=k6_result.k6_summary_path,
                prometheus_snapshot_path=prometheus_snapshot_path,
                script_path=self._script_path(request),
                payload_path=request.k6_payload,
            ),
            k6_result.run_dir,
        )

    def _create_run_dir(self) -> Path:
        timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        run_dir = self.runs_root / f"{timestamp}-two-vm-loadtest"
        run_dir.mkdir(parents=True, exist_ok=True)
        return run_dir

    def _script_path(self, request: E2eRequest) -> Path:
        if request.k6_script is not None:
            return request.k6_script
        return self.paths.tool_root / "assets" / "k6" / "two-vm-function-invoke.js"

    def _host(self, request: VmRequest) -> str:
        if self.host_resolver is not None:
            return self.host_resolver(request)
        return self.vm.connection_host(request)

    @staticmethod
    def _check(result: object) -> None:
        return_code = getattr(result, "return_code", 0)
        if return_code == 0:
            return
        stdout = getattr(result, "stdout", "")
        stderr = getattr(result, "stderr", "")
        raise RuntimeError(stderr or stdout or f"exit {return_code}")
