from __future__ import annotations

from collections.abc import Callable
from pathlib import Path
import shlex

from multipass import MultipassClient
from workflow_tasks.execution.bindings import RetargetingCommandTaskExecutor, RoleBindings
from workflow_tasks.tasks.executors import (
    HostCommandRunner,
    HostCommandTaskExecutor,
    VmCommandTaskExecutor,
)
from workflow_tasks.vm.models import VmRequest
from workflow_tasks.vm.multipass import resolve_connection_host

from controlplane_tool.config.environment import EnvironmentConfig, RoleTarget
from controlplane_tool.core.task_shell_adapter import ShellCommandTaskRunner


StackHostResolver = Callable[[RoleTarget], str]


def resolve_loadtest_urls(
    environment: EnvironmentConfig,
    *,
    control_plane_url: str | None = None,
    prometheus_url: str | None = None,
    dry_run: bool = False,
    host_resolver: StackHostResolver | None = None,
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
    elif target.host:
        host = target.host
    else:
        raise ValueError(
            f"{environment.provider} stack requires a host or explicit load-test URLs"
        )

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
                "ssh", "-o", "BatchMode=yes", f"{self._target.user}@{self._target.host}",
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


def build_role_bindings(
    environment: EnvironmentConfig,
    *,
    runner: HostCommandRunner | None = None,
) -> tuple[RoleBindings, _RemoteFetcher | None]:
    command_runner = runner or ShellCommandTaskRunner()
    host = HostCommandTaskExecutor(command_runner)
    if environment.provider == "local":
        return RoleBindings(host=host, stack=host, loadgen=host), None

    def remote(role: str):
        target = environment.target(role)  # type: ignore[arg-type]
        default_env = (
            {"KUBECONFIG": target.kubeconfig or f"{_home(target)}/.kube/config"}
            if role == "stack"
            else None
        )
        executor = VmCommandTaskExecutor(
            _RemoteRunner(command_runner, target, environment.provider, default_env)
        )
        return RetargetingCommandTaskExecutor(executor, "vm")

    stack = remote("stack")
    loadgen = remote("loadgen") if "loadgen" in environment.roles else None
    fetch_target = environment.target("loadgen" if loadgen is not None else "stack")
    return (
        RoleBindings(host=host, stack=stack, loadgen=loadgen),
        _RemoteFetcher(command_runner, fetch_target, environment.provider),
    )
