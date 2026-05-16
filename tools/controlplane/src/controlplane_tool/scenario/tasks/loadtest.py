from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from controlplane_tool.e2e.two_vm_loadtest_runner import TwoVmK6Result, TwoVmLoadtestRunner
from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.infra.vm.azure_vm_adapter import AzureVmOrchestrator
from controlplane_tool.infra.vm.vm_adapter import VmOrchestrator
from controlplane_tool.infra.vm.vm_models import VmRequest

_VmRunner = VmOrchestrator | AzureVmOrchestrator


@dataclass
class K6MatrixResult:
    results: list[TwoVmK6Result]

    @property
    def window(self) -> tuple[object, object] | None:
        if not self.results:
            return None
        return self.results[0].started_at, self.results[-1].ended_at


@dataclass
class InstallK6:
    task_id: str
    title: str
    vm: _VmRunner
    request: VmRequest
    remote_dir: str

    def run(self) -> None:
        result = self.vm.exec_argv(
            self.request,
            ("bash", "-lc", "which k6 || (curl -fsSL https://pkg.k6.io/key.gpg | sudo gpg --dearmor -o /usr/share/keyrings/k6-archive-keyring.gpg && echo 'deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main' | sudo tee /etc/apt/sources.list.d/k6.list && sudo apt-get update -qq && sudo apt-get install -y k6)"),
            cwd=self.remote_dir,
        )
        if result.return_code != 0:
            raise RuntimeError(result.stderr or result.stdout or f"exit {result.return_code}")


@dataclass
class RunK6Matrix:
    """Run k6 against ALL selected function targets. Fixes the [0]-truncation bug."""

    task_id: str
    title: str
    runner: TwoVmLoadtestRunner
    request: E2eRequest

    def run(self) -> K6MatrixResult:
        resolved = self.request.resolved_scenario
        targets: list[str] = (
            list(resolved.function_keys)
            if resolved is not None and resolved.function_keys
            else (self.request.functions or ["word-stats-java"])
        )
        results: list[TwoVmK6Result] = []
        for fn_key in targets:
            results.append(self.runner.run_k6_for_function(self.request, fn_key))
        return K6MatrixResult(results=results)


@dataclass
class CapturePrometheus:
    task_id: str
    title: str
    runner: TwoVmLoadtestRunner
    request: E2eRequest
    k6_matrix_result: K6MatrixResult

    def run(self) -> Path:
        if not self.k6_matrix_result.results:
            raise RuntimeError("CapturePrometheus requires at least one k6 result")
        first = self.k6_matrix_result.results[0]
        return self.runner.capture_prometheus_snapshots(self.request, first)


@dataclass
class WriteLoadtestReport:
    task_id: str
    title: str
    runner: TwoVmLoadtestRunner
    request: E2eRequest
    k6_matrix_result: K6MatrixResult
    prometheus_snapshot_path: Path

    def run(self) -> None:
        if not self.k6_matrix_result.results:
            raise RuntimeError("WriteLoadtestReport requires at least one k6 result")
        first = self.k6_matrix_result.results[0]
        self.runner.write_report(self.request, first, self.prometheus_snapshot_path)
