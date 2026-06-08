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
from pathlib import Path
from typing import TYPE_CHECKING, Protocol

from workflow_tasks.components.operations import RemoteCommandOperation
from workflow_tasks.vm.multipass import repo_rsync_command, repo_sync_ssh_rsh
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


@dataclass
class ProxmoxConnectivity:
    """Proxmox: rewrite host-ops onto the published NAT SSH endpoint.

    Ansible inventory -> NAT host + ansible_port + key; repo.sync rsync rebuilt over
    host:port with the key. vm-ops run via the proxmox orchestrator. Constructed with
    a resolved (or placeholder, for display) endpoint by the proxmox plan.
    """

    orchestrator: object
    request: object
    host: str
    port: int
    key: "Path | None"
    repo_root: Path
    remote_dir_value: str

    def _rewrite_ansible(self, argv: tuple[str, ...]) -> list[str]:
        rewritten = list(argv)
        if "-i" in rewritten:
            rewritten[rewritten.index("-i") + 1] = f"{self.host},"
        rewritten.extend(["-e", f"ansible_port={self.port}"])
        if self.key is not None:
            if "--private-key" in rewritten:
                rewritten[rewritten.index("--private-key") + 1] = str(self.key)
            else:
                rewritten.extend(["--private-key", str(self.key)])
        return rewritten

    def _repo_sync_command(self) -> list[str]:
        return repo_rsync_command(
            source=self.repo_root,
            user=self.request.user,
            host=self.host,
            destination=self.remote_dir_value,
            ssh_rsh=repo_sync_ssh_rsh(self.key, port=self.port),
        )

    def resolve_host_operation(self, operation: RemoteCommandOperation) -> RemoteCommandOperation:
        if operation.argv and operation.argv[0] == "ansible-playbook":
            new_argv: list[str] = self._rewrite_ansible(operation.argv)
        elif operation.operation_id == "repo.sync_to_vm":
            new_argv = self._repo_sync_command()
        else:
            return operation
        return RemoteCommandOperation(
            operation_id=operation.operation_id,
            summary=operation.summary,
            argv=tuple(new_argv),
            env=operation.env,
            execution_target=operation.execution_target,
        )

    def vm_runner(self, request: object) -> OrchestratorVmRunner:
        # vm-ops target the proxmox stack VM (self.request), regardless of the
        # setup-derived request build_command_tasks passes in.
        return OrchestratorVmRunner(self.orchestrator, self.request)

    def remote_dir(self, request: object) -> str:
        return self.remote_dir_value


@dataclass
class AzureConnectivity:
    """Azure: rewrite host-ops onto the public SSH endpoint (no NAT port).

    Azure VMs are reachable directly on the default SSH port, so there is no
    ansible_port or port plumbing. Ansible inventory -> public host + key;
    repo.sync rsync rebuilt over host with the key. vm-ops run via the azure
    orchestrator. Constructed with a resolved (or placeholder, for display)
    endpoint by the azure plan.
    """

    orchestrator: object
    request: object
    host: str
    key: "Path | None"
    repo_root: Path
    remote_dir_value: str

    def _rewrite_ansible(self, argv: tuple[str, ...]) -> list[str]:
        rewritten = list(argv)
        if "-i" in rewritten:
            rewritten[rewritten.index("-i") + 1] = f"{self.host},"
        if self.key is not None:
            if "--private-key" in rewritten:
                rewritten[rewritten.index("--private-key") + 1] = str(self.key)
            else:
                rewritten.extend(["--private-key", str(self.key)])
        return rewritten

    def _repo_sync_command(self) -> list[str]:
        return repo_rsync_command(
            source=self.repo_root,
            user=self.request.user,
            host=self.host,
            destination=self.remote_dir_value,
            ssh_rsh=repo_sync_ssh_rsh(self.key),
        )

    def resolve_host_operation(self, operation: RemoteCommandOperation) -> RemoteCommandOperation:
        if operation.argv and operation.argv[0] == "ansible-playbook":
            new_argv: list[str] = self._rewrite_ansible(operation.argv)
        elif operation.operation_id == "repo.sync_to_vm":
            new_argv = self._repo_sync_command()
        else:
            return operation
        return RemoteCommandOperation(
            operation_id=operation.operation_id,
            summary=operation.summary,
            argv=tuple(new_argv),
            env=operation.env,
            execution_target=operation.execution_target,
        )

    def vm_runner(self, request: object) -> OrchestratorVmRunner:
        # vm-ops target the azure stack VM (self.request), regardless of the
        # setup-derived request build_command_tasks passes in.
        return OrchestratorVmRunner(self.orchestrator, self.request)

    def remote_dir(self, request: object) -> str:
        return self.remote_dir_value
