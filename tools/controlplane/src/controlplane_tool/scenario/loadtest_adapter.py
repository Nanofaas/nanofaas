"""Per-lifecycle loadtest connectivity adapter.

A LoadtestConnectivityAdapter composes a generic ConnectivityStrategy (for the
provision prelude via build_command_tasks) and supplies the loadtest-only pieces
that differ between multipass/proxmox/azure: VM lifecycles, the loadgen install
endpoint, runner/fetcher, control-plane/prometheus URLs, an optional script-upload
hook, lifecycle-specific extra steps, and the display title suffix.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import TYPE_CHECKING, Protocol

from workflow_tasks.loadtest.ports import RemoteFileFetcher
from workflow_tasks.tasks.executors import VmCommandRunner
from workflow_tasks.vm.multipass import _find_ssh_private_key_path

from multipass import find_ssh_public_key

from controlplane_tool.infra.vm_lifecycle_adapters import MultipassVmAdapter
from controlplane_tool.loadtest.loadtest_adapters import (
    OrchestratorVmRunner,
    VmFileFetcher,
)
from controlplane_tool.scenario.connectivity import ConnectivityStrategy, MultipassConnectivity
from controlplane_tool.scenario.loadtest_flow import FlowPhase, RunContext
from controlplane_tool.scenario.two_vm_loadtest_config import (
    two_vm_control_plane_url,
    two_vm_prometheus_url,
)

if TYPE_CHECKING:
    from controlplane_tool.e2e.e2e_models import E2eRequest
    from controlplane_tool.e2e.e2e_runner import E2eRunner


@dataclass
class InstallEndpoint:
    """SSH endpoint for install_k6_task. ``port`` is None when the lifecycle uses the default."""

    host: str
    user: str
    private_key: Path | None
    port: int | None = None


class LoadtestConnectivityAdapter(Protocol):
    title_suffix: str
    connectivity: ConnectivityStrategy

    def stack_lifecycle(self): ...
    def loadgen_lifecycle(self): ...
    def loadgen_install_endpoint(self, ctx: RunContext) -> InstallEndpoint: ...
    def loadgen_runner(self, ctx: RunContext) -> VmCommandRunner: ...
    def fetcher(self, ctx: RunContext) -> RemoteFileFetcher: ...
    def control_plane_url(self, ctx: RunContext) -> str: ...
    def prometheus_url(self, ctx: RunContext) -> str: ...
    def prepare_loadgen(self, ctx: RunContext) -> None: ...
    def create_run_dir(self) -> Path: ...
    def extra_steps(self, phase: FlowPhase, ctx: RunContext) -> list: ...
    def extra_step_ids(self, phase: FlowPhase) -> list: ...


@dataclass
class MultipassLoadtestAdapter:
    """Multipass: reproduces two-vm's run() connectivity exactly."""

    runner: "E2eRunner"
    request: "E2eRequest"
    title_suffix: str = ""
    connectivity: ConnectivityStrategy = field(init=False)
    _vm_runner_impl: object = field(default=None, init=False, repr=False)

    def __post_init__(self) -> None:
        self.connectivity = MultipassConnectivity(runner=self.runner, request=self.request)

    def _runner_impl(self):
        if self._vm_runner_impl is None:
            from controlplane_tool.e2e.two_vm_loadtest_runner import TwoVmLoadtestRunner

            self._vm_runner_impl = TwoVmLoadtestRunner(repo_root=self.runner.paths.workspace_root)
        return self._vm_runner_impl

    def stack_lifecycle(self):
        return MultipassVmAdapter(self.runner.vm)

    def loadgen_lifecycle(self):
        return MultipassVmAdapter(self.runner.vm)

    def loadgen_install_endpoint(self, ctx: RunContext) -> InstallEndpoint:
        return InstallEndpoint(
            host=ctx.loadgen_info.host,
            user=self.request.loadgen_vm.user,
            private_key=_find_ssh_private_key_path(find_ssh_public_key()),
            port=None,
        )

    def loadgen_runner(self, ctx: RunContext) -> VmCommandRunner:
        return OrchestratorVmRunner(self._runner_impl().vm, self.request.loadgen_vm)

    def fetcher(self, ctx: RunContext) -> RemoteFileFetcher:
        return VmFileFetcher(vm=self._runner_impl().vm, request=self.request.loadgen_vm)

    def control_plane_url(self, ctx: RunContext) -> str:
        return two_vm_control_plane_url(self.request.vm, host=ctx.stack_info.host)

    def prometheus_url(self, ctx: RunContext) -> str:
        return two_vm_prometheus_url(self.request.vm, host=ctx.stack_info.host)

    def prepare_loadgen(self, ctx: RunContext) -> None:
        self._runner_impl().prepare_loadgen(self.request, ctx.remote_paths)

    def create_run_dir(self) -> Path:
        return self._runner_impl()._create_run_dir()  # noqa: SLF001

    def extra_steps(self, phase: FlowPhase, ctx: RunContext) -> list:
        return []

    def extra_step_ids(self, phase: FlowPhase) -> list:
        return []
