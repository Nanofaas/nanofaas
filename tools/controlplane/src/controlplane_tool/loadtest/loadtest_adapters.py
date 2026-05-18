from __future__ import annotations

from pathlib import Path
from typing import TYPE_CHECKING

from workflow_tasks.loadtest.models import TimeWindow

from controlplane_tool.loadtest.metrics import query_prometheus_range_series

if TYPE_CHECKING:
    from controlplane_tool.infra.vm.azure_vm_adapter import AzureVmOrchestrator
    from controlplane_tool.infra.vm.vm_adapter import VmOrchestrator
    from controlplane_tool.infra.vm.vm_models import VmRequest


class VmFileFetcher:
    """Implements RemoteFileFetcher using VmOrchestrator.transfer_from()."""

    def __init__(self, vm: "VmOrchestrator", request: "VmRequest") -> None:
        self._vm = vm
        self._request = request

    def fetch_from(self, remote: str, local: Path) -> None:
        result = self._vm.transfer_from(self._request, source=remote, destination=local)
        return_code = getattr(result, "return_code", 0)
        if return_code != 0:
            stderr = getattr(result, "stderr", "") or ""
            stdout = getattr(result, "stdout", "") or ""
            raise RuntimeError(stderr or stdout or f"transfer failed (exit {return_code})")


class OrchestratorVmRunner:
    """Adapts any orchestrator's exec_argv to the VmCommandRunner protocol."""

    def __init__(
        self,
        orchestrator: "VmOrchestrator | AzureVmOrchestrator",
        request: "VmRequest",
    ) -> None:
        self._orch = orchestrator
        self._request = request

    def run_vm_command(
        self,
        argv: tuple[str, ...],
        *,
        env: dict[str, str],
        remote_dir: str | None,
        dry_run: bool,
    ) -> object:
        return self._orch.exec_argv(
            self._request, argv, env=env or None, cwd=remote_dir, dry_run=dry_run
        )


class HttpPrometheusClient:
    """Implements PrometheusClient using the Prometheus HTTP API."""

    def __init__(self, url: str) -> None:
        self._url = url

    def query_range(
        self,
        expr: str,
        window: TimeWindow,
        step_seconds: int = 5,
    ) -> list[dict[str, float | str]]:
        return query_prometheus_range_series(
            self._url, expr, window.start, window.end, step_seconds
        )
