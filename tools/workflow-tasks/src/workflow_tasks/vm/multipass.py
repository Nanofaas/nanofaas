from __future__ import annotations

import shlex
from pathlib import Path

from multipass import MultipassClient, MultipassCommandError, VmNotFoundError, find_ssh_public_key
from shellcraft.backend import ShellBackend, ShellExecutionResult, SubprocessShell

from workflow_tasks.vm.models import VmRequest, vm_remote_home


REPO_SYNC_EXCLUDE_PATTERNS = (
    ".git", ".git/", ".gitnexus", ".gradle/", ".gradle-local/", ".DS_Store",
    ".idea/", ".vscode/", ".env", "*.log", "*.class", ".worktrees/",
    "__pycache__/", "*.egg-info/", "*.pyc", "*.pyo", "*.pyd",
    ".pytest_cache/", ".venv/", ".uv/", "node_modules/", "dist/",
    "/building/", "out/", "target/", "building-test/", "k6/results/",
    "experiments/k6/results/", "experiments/loadtest/results/",
    "experiments/.image-cache/", "tooling/runs/", "tools/controlplane/runs/",
    "recovery/",
)


def _vm_name_default(request: VmRequest) -> str:
    return request.name or "nanofaas-e2e"


def _ok(command: list[str], *, stdout: str = "") -> ShellExecutionResult:
    return ShellExecutionResult(command=command, return_code=0, stdout=stdout)


def _sdk_error(e: MultipassCommandError) -> ShellExecutionResult:
    return ShellExecutionResult(
        command=e.args_list,
        return_code=e.returncode,
        stdout=e.stdout,
        stderr=e.stderr,
    )


def _find_ssh_private_key_path(public_key: str | None = None) -> Path | None:
    ssh_dir = Path.home() / ".ssh"
    normalized_public_key = public_key.strip() if public_key else None
    for name in ("id_ed25519", "id_rsa", "id_ecdsa", "id_dsa"):
        pub = ssh_dir / f"{name}.pub"
        priv = ssh_dir / name
        if pub.exists() and priv.exists():
            if normalized_public_key is not None:
                if pub.read_text(encoding="utf-8").strip() == normalized_public_key:
                    return priv
                continue
            return priv
    return None


def resolve_connection_host(
    request: VmRequest,
    client: MultipassClient,
    *,
    dry_run: bool = False,
) -> str:
    if request.lifecycle == "external":
        if not request.host:
            raise RuntimeError("external VM lifecycle requires a host")
        return request.host
    if dry_run:
        return f"<multipass-ip:{_vm_name_default(request)}>"
    try:
        info = client.get_vm(_vm_name_default(request)).info()
    except VmNotFoundError:
        raise RuntimeError(f"Unable to resolve Multipass VM '{_vm_name_default(request)}'")
    if info.ipv4:
        return info.ipv4[0]
    raise RuntimeError(f"Multipass VM '{_vm_name_default(request)}' has no IPv4 address")


def repo_sync_ssh_rsh(
    private_key_path: Path | None = None,
    *,
    port: int | None = None,
) -> str:
    parts = ["ssh", "-o", "StrictHostKeyChecking=no", "-o", "UserKnownHostsFile=/dev/null"]
    if port is not None:
        parts.extend(["-p", str(port)])
    if private_key_path is not None:
        parts.extend(["-i", str(private_key_path)])
    return shlex.join(parts)


def repo_rsync_command(
    *,
    source: Path,
    user: str,
    host: str,
    destination: str,
    ssh_rsh: str | None = None,
) -> list[str]:
    command = [
        "rsync", "-az", "--delete", "--delete-excluded",
        *(f"--exclude={pattern}" for pattern in REPO_SYNC_EXCLUDE_PATTERNS),
    ]
    if ssh_rsh is not None:
        command.extend(["-e", ssh_rsh])
    command.extend([f"{source}/", f"{user}@{host}:{destination.rstrip('/')}/"])
    return command


class MultipassVmProvider:
    """Generic multipass VM provider: lifecycle, command execution, file transfer.

    Subclass to add project-specific operations (see VmOrchestrator in workflow_tasks.vm.orchestrator).
    Takes workspace_root directly — no ToolPaths dependency.
    """

    def __init__(
        self,
        workspace_root: Path,
        shell: ShellBackend | None = None,
        multipass_client: MultipassClient | None = None,
    ) -> None:
        self.workspace_root = Path(workspace_root)
        self.shell = shell or SubprocessShell()
        self._client = multipass_client or MultipassClient()
        self._ssh_public_key: str | None = find_ssh_public_key()
        self._private_key_path: Path | None = _find_ssh_private_key_path(self._ssh_public_key)

    def _vm_name(self, request: VmRequest) -> str:
        return _vm_name_default(request)

    def _remote_home(self, request: VmRequest) -> str:
        return vm_remote_home(request)

    def _shell_run(self, command: list[str], *, dry_run: bool = False) -> ShellExecutionResult:
        return self.shell.run(command, cwd=self.workspace_root, dry_run=dry_run)

    def _ensure_multipass_authorized_key(self, request: VmRequest) -> None:
        if not self._ssh_public_key:
            return
        name = self._vm_name(request)
        remote_home = self._remote_home(request)
        authorized_keys = f"{remote_home}/.ssh/authorized_keys"
        quoted_key = shlex.quote(self._ssh_public_key)
        if request.user == "root":
            command = (
                f"install -d -m 700 {shlex.quote(remote_home)}/.ssh && "
                f"touch {shlex.quote(authorized_keys)} && "
                f"chmod 600 {shlex.quote(authorized_keys)} && "
                f"grep -qxF {quoted_key} {shlex.quote(authorized_keys)} || "
                f"printf '%s\\n' {quoted_key} >> {shlex.quote(authorized_keys)}"
            )
        else:
            command = (
                f"sudo install -d -m 700 -o {shlex.quote(request.user)} -g {shlex.quote(request.user)} {shlex.quote(remote_home)}/.ssh && "
                f"sudo touch {shlex.quote(authorized_keys)} && "
                f"sudo chown {shlex.quote(request.user)}:{shlex.quote(request.user)} {shlex.quote(authorized_keys)} && "
                f"sudo chmod 600 {shlex.quote(authorized_keys)} && "
                f"sudo -u {shlex.quote(request.user)} bash -lc "
                f"\"grep -qxF {quoted_key} {shlex.quote(authorized_keys)} || printf '%s\\\\n' {quoted_key} >> {shlex.quote(authorized_keys)}\""
            )
        self._client.get_vm(name).exec(["bash", "-lc", command])

    @staticmethod
    def _build_exec_script(
        argv: tuple[str, ...] | list[str],
        *,
        env: dict[str, str] | None = None,
        cwd: str | None = None,
    ) -> str:
        parts: list[str] = []
        if cwd:
            parts.append(f"cd {shlex.quote(cwd)}")
        for k, v in (env or {}).items():
            parts.append(f"export {k}={shlex.quote(v)}")
        parts.append(shlex.join(list(argv)))
        return " && ".join(parts)

    def vm_name(self, request: VmRequest) -> str:
        return self._vm_name(request)

    def remote_home(self, request: VmRequest) -> str:
        return self._remote_home(request)

    def resolve_multipass_ipv4(self, request: VmRequest, *, dry_run: bool = False) -> str:
        return resolve_connection_host(request, self._client, dry_run=dry_run)

    def connection_host(self, request: VmRequest, *, dry_run: bool = False) -> str:
        return resolve_connection_host(request, self._client, dry_run=dry_run)

    def ensure_running(self, request: VmRequest, *, dry_run: bool = False) -> ShellExecutionResult:
        if request.lifecycle == "external":
            return self._shell_run(
                ["ssh", f"{request.user}@{request.host}", "true"], dry_run=dry_run
            )
        name = self._vm_name(request)
        launch_cmd = [
            "multipass", "launch", "--name", name,
            "--cpus", str(request.cpus), "--memory", request.memory, "--disk", request.disk,
        ]
        if dry_run:
            return _ok(launch_cmd)
        cloud_init_config = (
            {"ssh_authorized_keys": [self._ssh_public_key]} if self._ssh_public_key else None
        )
        self._client.ensure_running(
            name, cpus=request.cpus, memory=request.memory,
            disk=request.disk, cloud_init_config=cloud_init_config,
        )
        self._ensure_multipass_authorized_key(request)
        return _ok(launch_cmd)

    def teardown(self, request: VmRequest, *, dry_run: bool = False) -> ShellExecutionResult:
        if request.lifecycle == "external":
            return self._shell_run(
                ["echo", "Skipping teardown for external VM lifecycle"], dry_run=dry_run
            )
        name = self._vm_name(request)
        if dry_run:
            return _ok(["multipass", "delete", name])
        try:
            self._client.get_vm(name).delete()
        except (VmNotFoundError, MultipassCommandError) as e:
            if isinstance(e, MultipassCommandError):
                return _sdk_error(e)
        return _ok(["multipass", "delete", name])

    def inspect(self, request: VmRequest, *, dry_run: bool = False) -> ShellExecutionResult:
        if request.lifecycle == "external":
            return self._shell_run(
                ["ssh", f"{request.user}@{request.host}", "hostname"], dry_run=dry_run
            )
        name = self._vm_name(request)
        if dry_run:
            return _ok(["multipass", "info", name])
        try:
            info = self._client.get_vm(name).info()
            stdout = (
                f"Name:  {info.name}\n"
                f"State: {info.state.value}\n"
                f"IPv4:  {', '.join(info.ipv4) or '-'}\n"
            )
            return _ok(["multipass", "info", name], stdout=stdout)
        except MultipassCommandError as e:
            return _sdk_error(e)

    def exec_argv(
        self,
        request: VmRequest,
        argv: tuple[str, ...] | list[str],
        *,
        env: dict[str, str] | None = None,
        cwd: str | None = None,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        command = self._build_exec_script(argv, env=env, cwd=cwd)
        return self.remote_exec(request, command=command, dry_run=dry_run)

    def remote_exec(
        self,
        request: VmRequest,
        *,
        command: str,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        if request.lifecycle == "external":
            return self._shell_run(
                ["ssh", f"{request.user}@{request.host}", command], dry_run=dry_run
            )
        name = self._vm_name(request)
        exec_cmd = ["multipass", "exec", name, "--", "bash", "-lc", command]
        if dry_run:
            return _ok(exec_cmd)
        return self._shell_run(exec_cmd, dry_run=False)

    def transfer_to(
        self,
        request: VmRequest,
        *,
        source: Path,
        destination: str,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        if request.lifecycle == "external":
            return self._shell_run(
                ["scp", str(source), f"{request.user}@{request.host}:{destination}"],
                dry_run=dry_run,
            )
        command = ["multipass", "transfer", str(source), f"{self._vm_name(request)}:{destination}"]
        return self._shell_run(command, dry_run=dry_run)

    def transfer_from(
        self,
        request: VmRequest,
        *,
        source: str,
        destination: Path,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        if request.lifecycle == "external":
            return self._shell_run(
                ["scp", f"{request.user}@{request.host}:{source}", str(destination)],
                dry_run=dry_run,
            )
        command = ["multipass", "transfer", f"{self._vm_name(request)}:{source}", str(destination)]
        return self._shell_run(command, dry_run=dry_run)
