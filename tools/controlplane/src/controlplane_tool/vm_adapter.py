from __future__ import annotations

import json
import tempfile
from pathlib import Path
from typing import TYPE_CHECKING

from controlplane_tool.paths import ToolPaths
from controlplane_tool.shell_backend import (
    ShellBackend,
    ShellExecutionResult,
    SubprocessShell,
)
from controlplane_tool.vm_models import VmRequest

if TYPE_CHECKING:
    from controlplane_tool.ansible_adapter import AnsibleAdapter


def _find_user_public_key() -> str | None:
    """Return the first SSH public key found in ~/.ssh/, or None."""
    ssh_dir = Path.home() / ".ssh"
    for name in ("id_ed25519.pub", "id_rsa.pub", "id_ecdsa.pub", "id_dsa.pub"):
        candidate = ssh_dir / name
        if candidate.exists():
            return candidate.read_text(encoding="utf-8").strip()
    return None


def _vm_name(request: VmRequest) -> str:
    return request.name or "nanofaas-e2e"


def _multipass_info_command(request: VmRequest) -> list[str]:
    return ["multipass", "info", _vm_name(request), "--format", "json"]


def _parse_multipass_instance(stdout: str, request: VmRequest) -> dict[str, object] | None:
    if not stdout.strip():
        return None

    try:
        payload = json.loads(stdout)
    except json.JSONDecodeError:
        return None

    info = payload.get("info")
    if not isinstance(info, dict):
        return None

    instance = info.get(_vm_name(request))
    if not isinstance(instance, dict):
        return None
    return instance


def _read_multipass_instance(
    shell: ShellBackend,
    request: VmRequest,
) -> tuple[ShellExecutionResult, dict[str, object] | None]:
    result = shell.run(_multipass_info_command(request))
    if result.return_code != 0:
        return result, None
    return result, _parse_multipass_instance(result.stdout, request)


def resolve_connection_host(
    request: VmRequest,
    shell: ShellBackend,
    *,
    dry_run: bool = False,
) -> str:
    if request.lifecycle == "external":
        if not request.host:
            raise RuntimeError("external VM lifecycle requires a host")
        return request.host

    if dry_run:
        return f"<multipass-ip:{_vm_name(request)}>"

    _result, instance = _read_multipass_instance(shell, request)
    if instance is None:
        raise RuntimeError(f"Unable to resolve Multipass VM '{_vm_name(request)}'")

    ipv4 = instance.get("ipv4")
    if isinstance(ipv4, list):
        for candidate in ipv4:
            if isinstance(candidate, str) and candidate.strip():
                return candidate
    raise RuntimeError(f"Multipass VM '{_vm_name(request)}' has no IPv4 address")


class VmOrchestrator:
    def __init__(
        self,
        repo_root: Path,
        shell: ShellBackend | None = None,
        ansible: AnsibleAdapter | None = None,
    ) -> None:
        self.repo_root = Path(repo_root)
        self.paths = ToolPaths.repo_root(self.repo_root)
        self.shell = shell or SubprocessShell()
        if ansible is None:
            from controlplane_tool.ansible_adapter import AnsibleAdapter

            ansible = AnsibleAdapter(
                self.repo_root,
                shell=self.shell,
                host_resolver=self.connection_host,
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
        return resolve_connection_host(request, self.shell, dry_run=dry_run)

    def connection_host(self, request: VmRequest, *, dry_run: bool = False) -> str:
        return resolve_connection_host(request, self.shell, dry_run=dry_run)

    def _run(self, command: list[str], *, dry_run: bool = False) -> ShellExecutionResult:
        return self.shell.run(command, cwd=self.paths.workspace_root, dry_run=dry_run)

    def _launch_command(self, request: VmRequest) -> list[str]:
        return [
            "multipass",
            "launch",
            "--name",
            self._vm_name(request),
            "--cpus",
            str(request.cpus),
            "--memory",
            request.memory,
            "--disk",
            request.disk,
        ]

    def _launch_with_cloud_init(self, request: VmRequest) -> ShellExecutionResult:
        """Launch a new Multipass VM, injecting the user's SSH public key via cloud-init."""
        base_command = self._launch_command(request)
        public_key = _find_user_public_key()
        if public_key is None:
            return self._run(base_command)
        cloud_init = f"#cloud-config\nssh_authorized_keys:\n  - {public_key}\n"
        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".yaml", prefix="nanofaas-cloud-init-", delete=False
        ) as f:
            f.write(cloud_init)
            cloud_init_path = f.name
        try:
            return self._run([*base_command, "--cloud-init", cloud_init_path])
        finally:
            Path(cloud_init_path).unlink(missing_ok=True)

    def ensure_running(self, request: VmRequest, *, dry_run: bool = False) -> ShellExecutionResult:
        if request.lifecycle == "external":
            command = ["ssh", f"{request.user}@{request.host}", "true"]
            return self._run(command, dry_run=dry_run)

        if dry_run:
            return self._run(self._launch_command(request), dry_run=True)

        info_result, instance = _read_multipass_instance(self.shell, request)
        if instance is None:
            return self._launch_with_cloud_init(request)

        state = str(instance.get("state", "")).strip().lower()
        if state == "running":
            return info_result

        return self._run(["multipass", "start", self._vm_name(request)], dry_run=False)

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
            command = [
                "rsync",
                "-az",
                "--delete",
                f"{source}/",
                f"{request.user}@{request.host}:{destination}/",
            ]
            return self._run(command, dry_run=dry_run)

        command = [
            "multipass",
            "transfer",
            "-r",
            str(source),
            f"{self._vm_name(request)}:{destination}",
        ]
        return self._run(command, dry_run=dry_run)

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
        return self.ansible.configure_registry(
            request,
            registry=registry,
            container_name=container_name,
            dry_run=dry_run,
        )

    def teardown(self, request: VmRequest, *, dry_run: bool = False) -> ShellExecutionResult:
        if request.lifecycle == "external":
            return self._run(
                ["echo", "Skipping teardown for external VM lifecycle"],
                dry_run=dry_run,
            )

        command = ["multipass", "delete", self._vm_name(request)]
        return self._run(command, dry_run=dry_run)

    def inspect(self, request: VmRequest, *, dry_run: bool = False) -> ShellExecutionResult:
        if request.lifecycle == "external":
            return self._run(["ssh", f"{request.user}@{request.host}", "hostname"], dry_run=dry_run)
        return self._run(["multipass", "info", self._vm_name(request)], dry_run=dry_run)

    def export_kubeconfig(
        self,
        request: VmRequest,
        *,
        destination: Path,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        kubeconfig_path = self._kubeconfig_path(request)
        if request.lifecycle == "external":
            command = [
                "scp",
                f"{request.user}@{request.host}:{kubeconfig_path}",
                str(destination),
            ]
            return self._run(command, dry_run=dry_run)

        command = [
            "multipass",
            "transfer",
            f"{self._vm_name(request)}:{kubeconfig_path}",
            str(destination),
        ]
        return self._run(command, dry_run=dry_run)

    def remote_exec(
        self,
        request: VmRequest,
        *,
        command: str,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        if request.lifecycle == "external":
            return self._run(
                ["ssh", f"{request.user}@{request.host}", command],
                dry_run=dry_run,
            )
        return self._run(
            [
                "multipass",
                "exec",
                self._vm_name(request),
                "--",
                "bash",
                "-lc",
                command,
            ],
            dry_run=dry_run,
        )
