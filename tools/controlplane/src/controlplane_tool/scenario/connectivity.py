"""Per-lifecycle connectivity for scenario command execution.

A ConnectivityStrategy supplies the two things that differ between VM lifecycles
when turning a recipe into honest CommandTasks:
  - resolve_host_operation: rewrite a host operation's argv/env (ansible inventory,
    rsync endpoint) to target the VM's SSH endpoint for this lifecycle.
  - vm_runner: an OrchestratorVmRunner wrapping this lifecycle's orchestrator,
    used for vm-target operations (helm/docker/gradlew/cli).
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Protocol

from workflow_tasks.components.operations import RemoteCommandOperation
from workflow_tasks.vm.orchestrator import VmOrchestrator
from workflow_tasks.vm.runners import OrchestratorVmRunner

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.scenario.command_resolver import CommandResolver

if TYPE_CHECKING:
    from controlplane_tool.e2e.e2e_runner import E2eRunner


def resolve_host_operation(
    operation: RemoteCommandOperation,
    *,
    resolver: CommandResolver,
    request: E2eRequest,
    vm: VmOrchestrator,
    ip_cache: dict[str, str],
) -> RemoteCommandOperation:
    """Substitute <multipass-ip:NAME> placeholders in a host operation's argv/env."""
    argv = resolver._resolve_command(list(operation.argv), request.vm, ip_cache, vm)  # noqa: SLF001
    env = resolver._resolve_env(dict(operation.env), request.vm, ip_cache, vm)  # noqa: SLF001
    return RemoteCommandOperation(
        operation_id=operation.operation_id,
        summary=operation.summary,
        argv=tuple(argv),
        env=env,
        execution_target=operation.execution_target,
    )


class ConnectivityStrategy(Protocol):
    def resolve_host_operation(self, operation: RemoteCommandOperation) -> RemoteCommandOperation: ...
    def vm_runner(self, request: object) -> OrchestratorVmRunner: ...
    def remote_dir(self, request: object) -> str: ...


@dataclass
class MultipassConnectivity:
    """Current behavior: multipass IP placeholder resolution + multipass orchestrator."""

    runner: "E2eRunner"
    request: E2eRequest
    _ip_cache: dict[str, str] = field(default_factory=dict, init=False, repr=False)

    def resolve_host_operation(self, operation: RemoteCommandOperation) -> RemoteCommandOperation:
        resolver = CommandResolver(host_resolver=self.runner._host_resolver)  # noqa: SLF001
        return resolve_host_operation(
            operation,
            resolver=resolver,
            request=self.request,
            vm=self.runner.vm,
            ip_cache=self._ip_cache,
        )

    def vm_runner(self, request: object) -> OrchestratorVmRunner:
        return OrchestratorVmRunner(self.runner.vm, request)

    def remote_dir(self, request: object) -> str:
        return self.runner.vm.remote_project_dir(request)
