from __future__ import annotations

from collections.abc import Callable
from pathlib import Path
import shlex
from typing import cast

from multipass import MultipassClient
from workflow_tasks.execution.bindings import RetargetingCommandTaskExecutor, RoleBindings
from workflow_tasks.tasks.executors import (
    HostCommandRunner,
    HostCommandTaskExecutor,
    VmCommandResult,
    VmCommandTaskExecutor,
)
from workflow_tasks.vm.models import VmRequest, vm_remote_home
from workflow_tasks.vm.multipass import resolve_connection_host
from workflow_tasks.vm.runners import OrchestratorVmRunner, VmFileFetcher

from controlplane_tool.cli.vm_provider import (
    vm_provider_for_environment,
    vm_request_for_role,
)
from controlplane_tool.config.environment import EnvironmentConfig, RoleTarget
from controlplane_tool.core.task_shell_adapter import ShellCommandTaskRunner
from controlplane_tool.workspace.paths import default_tool_paths


StackHostResolver = Callable[[RoleTarget], str]


def resolve_loadtest_urls(
    environment: EnvironmentConfig,
    *,
    control_plane_url: str | None = None,
    prometheus_url: str | None = None,
    dry_run: bool = False,
    host_resolver: StackHostResolver | None = None,
    vm_provider: object | None = None,
) -> tuple[str, str]:
    if control_plane_url is not None and prometheus_url is not None:
        return control_plane_url, prometheus_url

    target = environment.target("stack")
    if environment.provider == "local":
        host = "127.0.0.1"
    elif environment.provider == "multipass":
        if host_resolver is not None:
            host = host_resolver(target)
        else:
            host = resolve_connection_host(
                VmRequest(lifecycle="multipass", name=target.name),
                MultipassClient(),
                dry_run=dry_run,
            )
    elif environment.provider in {"azure", "proxmox"}:
        request = vm_request_for_role(environment, "stack", loadtest=True)
        if dry_run:
            if environment.provider == "azure":
                host = f"<azure-ip:{request.name}>"
                discovered_prometheus = f"http://{host}:30090"
            else:
                host = f"<proxmox-guest-ip:{request.name}>"
                discovered_prometheus = f"http://<proxmox-prometheus:{request.name}>"
        else:
            provider = vm_provider or vm_provider_for_environment(
                environment, default_tool_paths().workspace_root
            )
            if environment.provider == "azure":
                host = provider.connection_host(request)  # type: ignore[attr-defined]
                discovered_prometheus = f"http://{host}:30090"
            else:
                host = provider.guest_host(request)  # type: ignore[attr-defined]
                metrics_host, metrics_port = provider.publish_port(  # type: ignore[attr-defined]
                    request, service="PROMETHEUS_HTTP", guest_port=30090
                )
                discovered_prometheus = f"http://{metrics_host}:{metrics_port}"
        return (
            control_plane_url or f"http://{host}:30080",
            prometheus_url or discovered_prometheus,
        )
    elif target.host:
        host = target.host
    else:
        raise ValueError(f"{environment.provider} stack requires a host or explicit load-test URLs")

    return (
        control_plane_url or f"http://{host}:30080",
        prometheus_url or f"http://{host}:30090",
    )


def _home(target: RoleTarget) -> str:
    return target.home or ("/root" if target.user == "root" else f"/home/{target.user}")


def _command(argv: tuple[str, ...], env: dict[str, str], cwd: str) -> str:
    command = f"cd {shlex.quote(cwd)} && "
    if env:
        assignments = shlex.join([f"{key}={value}" for key, value in env.items()])
        command += f"env {assignments} "
    return command + shlex.join(argv)


class _RemoteRunner:
    def __init__(
        self,
        runner: HostCommandRunner,
        target: RoleTarget,
        provider: str,
        default_env: dict[str, str] | None = None,
    ) -> None:
        self._runner = runner
        self._target = target
        self._provider = provider
        self._default_env = default_env or {}

    def run_vm_command(self, argv, *, env, remote_dir, dry_run):
        command = _command(
            argv,
            {**self._default_env, **env},
            remote_dir or f"{_home(self._target)}/nanofaas",
        )
        if self._provider == "multipass":
            if not self._target.name:
                raise ValueError("Multipass role requires an instance name")
            outer = ["multipass", "exec", self._target.name, "--", "bash", "-lc", command]
        else:
            if not self._target.host:
                raise ValueError(f"{self._provider} role requires a reachable SSH host")
            outer = [
                "ssh",
                "-o",
                "BatchMode=yes",
                f"{self._target.user}@{self._target.host}",
                f"bash -lc {shlex.quote(command)}",
            ]
        return self._runner.run(outer, cwd=None, env={}, dry_run=dry_run)


class _RemoteFetcher:
    def __init__(self, runner: HostCommandRunner, target: RoleTarget, provider: str) -> None:
        self._runner = runner
        self._target = target
        self._provider = provider

    def fetch_from(self, remote: str, local: Path) -> None:
        if self._provider == "multipass":
            argv = ["multipass", "transfer", f"{self._target.name}:{remote}", str(local)]
        else:
            argv = ["scp", f"{self._target.user}@{self._target.host}:{remote}", str(local)]
        result = self._runner.run(argv, cwd=None, env={}, dry_run=False)
        if result.return_code != 0:
            raise RuntimeError(result.stderr or result.stdout or "remote file transfer failed")


class _ProviderRunner:
    def __init__(
        self,
        runner: OrchestratorVmRunner,
        *,
        default_env: dict[str, str],
        default_dir: str,
    ) -> None:
        self._runner = runner
        self._default_env = default_env
        self._default_dir = default_dir

    def run_vm_command(
        self,
        argv: tuple[str, ...],
        *,
        env: dict[str, str],
        remote_dir: str | None,
        dry_run: bool,
    ) -> VmCommandResult:
        return cast(
            VmCommandResult,
            self._runner.run_vm_command(
                argv,
                env={**self._default_env, **env},
                remote_dir=remote_dir or self._default_dir,
                dry_run=dry_run,
            ),
        )


def build_role_bindings(
    environment: EnvironmentConfig,
    *,
    runner: HostCommandRunner | None = None,
    vm_provider: object | None = None,
    repo_root: Path | None = None,
) -> tuple[RoleBindings, _RemoteFetcher | VmFileFetcher | None]:
    command_runner = runner or ShellCommandTaskRunner()
    host = HostCommandTaskExecutor(command_runner)
    if environment.provider == "local":
        return RoleBindings(host=host, stack=host, loadgen=host, cloud=host), None

    if environment.provider in {"azure", "proxmox"}:
        provider = vm_provider or vm_provider_for_environment(
            environment, repo_root or default_tool_paths().workspace_root
        )

        def provider_remote(role: str):
            request = vm_request_for_role(environment, role)  # type: ignore[arg-type]
            default_env = (
                {
                    "KUBECONFIG": environment.target(role).kubeconfig
                    or f"{vm_remote_home(request)}/.kube/config"
                }
                if role in ("stack", "cloud")
                else {}
            )
            executor = VmCommandTaskExecutor(
                _ProviderRunner(
                    OrchestratorVmRunner(provider, request),
                    default_env=default_env,
                    default_dir=f"{vm_remote_home(request)}/nanofaas",
                )
            )
            return RetargetingCommandTaskExecutor(executor, "vm"), request

        stack, stack_request = provider_remote("stack")
        loadgen_result = provider_remote("loadgen") if "loadgen" in environment.roles else None
        loadgen = loadgen_result[0] if loadgen_result else None
        cloud_result = provider_remote("cloud") if "cloud" in environment.roles else None
        cloud = cloud_result[0] if cloud_result else None
        fetch_request = loadgen_result[1] if loadgen_result else stack_request
        return (
            RoleBindings(host=host, stack=stack, loadgen=loadgen, cloud=cloud),
            VmFileFetcher(provider, fetch_request),
        )

    def remote(role: str):
        target = environment.target(role)  # type: ignore[arg-type]
        default_env = (
            {"KUBECONFIG": target.kubeconfig or f"{_home(target)}/.kube/config"}
            if role in ("stack", "cloud")
            else None
        )
        executor = VmCommandTaskExecutor(
            _RemoteRunner(command_runner, target, environment.provider, default_env)
        )
        return RetargetingCommandTaskExecutor(executor, "vm")

    stack = remote("stack")
    loadgen = remote("loadgen") if "loadgen" in environment.roles else None
    cloud = remote("cloud") if "cloud" in environment.roles else None
    fetch_target = environment.target("loadgen" if loadgen is not None else "stack")
    return (
        RoleBindings(host=host, stack=stack, loadgen=loadgen, cloud=cloud),
        _RemoteFetcher(command_runner, fetch_target, environment.provider),
    )
