from __future__ import annotations

import shlex
from pathlib import Path
from typing import TYPE_CHECKING

from multipass import MultipassClient, MultipassCommandError, VmNotFoundError, find_ssh_public_key

from controlplane_tool.paths import ToolPaths
from controlplane_tool.shell_backend import (
    ShellBackend,
    ShellExecutionResult,
    SubprocessShell,
)
from controlplane_tool.vm_models import VmRequest

if TYPE_CHECKING:
    from controlplane_tool.ansible_adapter import AnsibleAdapter


def _find_ssh_private_key_path(public_key: str | None = None) -> Path | None:
    """Return the private key path matching the chosen public key, or the first usable key."""
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


def _vm_name(request: VmRequest) -> str:
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
        return f"<multipass-ip:{_vm_name(request)}>"

    try:
        info = client.get_vm(_vm_name(request)).info()
    except VmNotFoundError:
        raise RuntimeError(f"Unable to resolve Multipass VM '{_vm_name(request)}'")

    if info.ipv4:
        return info.ipv4[0]
    raise RuntimeError(f"Multipass VM '{_vm_name(request)}' has no IPv4 address")


class VmOrchestrator:
    def __init__(
        self,
        repo_root: Path,
        shell: ShellBackend | None = None,
        ansible: "AnsibleAdapter | None" = None,
        multipass_client: MultipassClient | None = None,
    ) -> None:
        self.repo_root = Path(repo_root)
        self.paths = ToolPaths.repo_root(self.repo_root)
        self.shell = shell or SubprocessShell()
        self._client = multipass_client or MultipassClient()
        self._ssh_public_key: str | None = find_ssh_public_key()
        self._private_key_path: Path | None = _find_ssh_private_key_path(self._ssh_public_key)
        if ansible is None:
            from controlplane_tool.ansible_adapter import AnsibleAdapter

            ansible = AnsibleAdapter(
                self.repo_root,
                shell=self.shell,
                host_resolver=self.connection_host,
                private_key_path=self._private_key_path,
            )
        self.ansible = ansible

    def _vm_name(self, request: VmRequest) -> str:
        return _vm_name(request)

    def _remote_home(self, request: VmRequest) -> str:
        if request.home:
            return request.home
        if request.user == "root":
            return "/root"
        return f"/home/{request.user}"

    def _remote_project_dir(self, request: VmRequest) -> str:
        return f"{self._remote_home(request)}/nanofaas"

    def _kubeconfig_path(self, request: VmRequest) -> str:
        return f"{self._remote_home(request)}/.kube/config"

    def vm_name(self, request: VmRequest) -> str:
        return self._vm_name(request)

    def remote_home(self, request: VmRequest) -> str:
        return self._remote_home(request)

    def remote_project_dir(self, request: VmRequest) -> str:
        return self._remote_project_dir(request)

    def kubeconfig_path(self, request: VmRequest) -> str:
        return self._kubeconfig_path(request)

    def remote_path_for_local(
        self,
        request: VmRequest,
        local_path: Path,
        *,
        local_root: Path | None = None,
        fallback_subdir: str | None = None,
    ) -> str:
        path = Path(local_path).resolve()
        root = Path(local_root or self.paths.workspace_root).resolve()
        remote_dir = self._remote_project_dir(request)

        try:
            relative = path.relative_to(root)
            return f"{remote_dir}/{relative.as_posix()}"
        except ValueError:
            if fallback_subdir:
                fallback = fallback_subdir.strip("/")
                return f"{remote_dir}/{fallback}/{path.name}"
            return f"{remote_dir}/{path.name}"

    def resolve_multipass_ipv4(self, request: VmRequest, *, dry_run: bool = False) -> str:
        return resolve_connection_host(request, self._client, dry_run=dry_run)

    def connection_host(self, request: VmRequest, *, dry_run: bool = False) -> str:
        return resolve_connection_host(request, self._client, dry_run=dry_run)

    def _shell_run(self, command: list[str], *, dry_run: bool = False) -> ShellExecutionResult:
        return self.shell.run(command, cwd=self.paths.workspace_root, dry_run=dry_run)

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

    def ensure_running(self, request: VmRequest, *, dry_run: bool = False) -> ShellExecutionResult:
        if request.lifecycle == "external":
            return self._shell_run(["ssh", f"{request.user}@{request.host}", "true"], dry_run=dry_run)

        name = self._vm_name(request)
        launch_cmd = ["multipass", "launch", "--name", name,
                      "--cpus", str(request.cpus), "--memory", request.memory, "--disk", request.disk]

        if dry_run:
            return _ok(launch_cmd)

        cloud_init_config = (
            {"ssh_authorized_keys": [self._ssh_public_key]}
            if self._ssh_public_key
            else None
        )
        self._client.ensure_running(
            name,
            cpus=request.cpus,
            memory=request.memory,
            disk=request.disk,
            cloud_init_config=cloud_init_config,
        )
        self._ensure_multipass_authorized_key(request)
        return _ok(launch_cmd)

    def sync_project(
        self,
        request: VmRequest,
        *,
        source_dir: Path | None = None,
        remote_dir: str | None = None,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        source = Path(source_dir or self.paths.workspace_root)
        destination = remote_dir or self._remote_project_dir(request)

        if request.lifecycle == "external":
            return self._shell_run(
                ["rsync", "-az", "--delete", f"{source}/", f"{request.user}@{request.host}:{destination}/"],
                dry_run=dry_run,
            )

        name = self._vm_name(request)
        transfer_cmd = ["multipass", "transfer", "-r", str(source), f"{name}:{destination}"]

        if dry_run:
            return _ok(transfer_cmd)

        try:
            self._client.get_vm(name).transfer(str(source), f"{name}:{destination}")
        except MultipassCommandError as e:
            return _sdk_error(e)
        return _ok(transfer_cmd)

    def install_dependencies(
        self,
        request: VmRequest,
        *,
        install_helm: bool = False,
        helm_version: str = "3.16.4",
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        return self.ansible.provision_base(
            request,
            install_helm=install_helm,
            helm_version=helm_version,
            dry_run=dry_run,
        )

    def install_k3s(
        self,
        request: VmRequest,
        *,
        kubeconfig_path: str | None = None,
        k3s_version: str | None = None,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        return self.ansible.provision_k3s(
            request,
            kubeconfig_path=kubeconfig_path or self._kubeconfig_path(request),
            k3s_version=k3s_version,
            dry_run=dry_run,
        )

    def setup_registry(
        self,
        request: VmRequest,
        *,
        registry: str = "localhost:5000",
        container_name: str = "nanofaas-e2e-registry",
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        ensure_result = self.ensure_registry_container(
            request,
            registry=registry,
            container_name=container_name,
            dry_run=dry_run,
        )
        if ensure_result.return_code != 0:
            return ensure_result
        return self.configure_k3s_registry(
            request,
            registry=registry,
            dry_run=dry_run,
        )

    def ensure_registry_container(
        self,
        request: VmRequest,
        *,
        registry: str = "localhost:5000",
        container_name: str = "nanofaas-e2e-registry",
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        return self.ansible.ensure_registry_container(
            request,
            registry=registry,
            container_name=container_name,
            dry_run=dry_run,
        )

    def configure_k3s_registry(
        self,
        request: VmRequest,
        *,
        registry: str = "localhost:5000",
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        return self.ansible.configure_k3s_registry(
            request,
            registry=registry,
            dry_run=dry_run,
        )

    def teardown(self, request: VmRequest, *, dry_run: bool = False) -> ShellExecutionResult:
        if request.lifecycle == "external":
            return self._shell_run(
                ["echo", "Skipping teardown for external VM lifecycle"],
                dry_run=dry_run,
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
            return self._shell_run(["ssh", f"{request.user}@{request.host}", "hostname"], dry_run=dry_run)

        name = self._vm_name(request)
        if dry_run:
            return _ok(["multipass", "info", name])

        try:
            info = self._client.get_vm(name).info()
            stdout = (
                f"Name:  {info.name}\n"
                f"State: {info.state.value}\n"
                f"IPv4:  {', '.join(info.ipv4) or '-'}\n"
                f"Image: {info.image}\n"
            )
            return _ok(["multipass", "info", name], stdout=stdout)
        except MultipassCommandError as e:
            return _sdk_error(e)

    def export_kubeconfig(
        self,
        request: VmRequest,
        *,
        destination: Path,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        kubeconfig_path = self._kubeconfig_path(request)
        if request.lifecycle == "external":
            return self._shell_run(
                ["scp", f"{request.user}@{request.host}:{kubeconfig_path}", str(destination)],
                dry_run=dry_run,
            )

        name = self._vm_name(request)
        transfer_cmd = ["multipass", "transfer", f"{name}:{kubeconfig_path}", str(destination)]
        if dry_run:
            return _ok(transfer_cmd)

        try:
            self._client.get_vm(name).transfer(f"{name}:{kubeconfig_path}", str(destination))
        except MultipassCommandError as e:
            return _sdk_error(e)
        return _ok(transfer_cmd)

    def exec_argv(
        self,
        request: VmRequest,
        argv: tuple[str, ...] | list[str],
        *,
        env: dict[str, str] | None = None,
        cwd: str | None = None,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        """Execute a structured command inside the VM without bash string construction.

        Builds the minimal bash prologue (cd + export) from structured arguments so
        callers never need to construct shell strings themselves.  The bash boundary
        is confined to this single method.
        """
        parts: list[str] = []
        if cwd:
            parts.append(f"cd {shlex.quote(cwd)}")
        for k, v in (env or {}).items():
            parts.append(f"export {k}={shlex.quote(v)}")
        parts.append(shlex.join(list(argv)))
        command = " && ".join(parts)
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
                ["ssh", f"{request.user}@{request.host}", command],
                dry_run=dry_run,
            )

        name = self._vm_name(request)
        exec_cmd = ["multipass", "exec", name, "--", "bash", "-lc", command]
        if dry_run:
            return _ok(exec_cmd)
        return self._shell_run(exec_cmd, dry_run=False)
